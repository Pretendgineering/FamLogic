package main

import (
	"context"
	"time"
)

// Alerts ("events") are kept briefly on the server and picked up by phones the
// next time they talk to it, either when sending their location or when the map
// is open. This needs no push service; the delay is at most one update interval.

const (
	EventBatteryLow = "battery_low"
	EventSOS        = "sos"     // someone pressed SOS
	EventSOSEnd     = "sos_end" // ...and later said they're OK
	EventCheckIn    = "checkin" // "I'm here", with an optional note

	maxEvents = 500
	eventTTL  = 48 * time.Hour

	// A phone re-arms its low-battery alerts once it's back above this level.
	batteryRearmLevel = 20
)

// The levels at which a low-battery alert is raised, highest first. Each phone
// chooses which one it wants to be notified about (15% or 10%).
var batteryAlertLevels = []int{15, 10}

// Event is one alert, e.g. "Alex's battery is at 14%".
type Event struct {
	ID       int64  `json:"id"`
	Time     int64  `json:"time"` // unix ms
	Type     string `json:"type"`
	MemberID string `json:"memberId"`
	Name     string `json:"name"`
	Battery  int    `json:"battery,omitempty"`  // battery_low: the level when it happened
	Level    int    `json:"level,omitempty"`    // battery_low: which alert level was crossed
	Note     string `json:"note,omitempty"`     // checkin: optional message
	CircleID string `json:"circleId,omitempty"` // place alerts: the circle the place belongs to
	PlaceID  string `json:"placeId,omitempty"`
	// chat: the conversation, its title for the recipient ("" for one-to-one) and the message id.
	Conv    string `json:"conv,omitempty"`
	Title   string `json:"title,omitempty"`
	Message int64  `json:"message,omitempty"`
	// trip: where it started (a fence id, if any), how far and how long.
	FromPlaceID string `json:"fromPlaceId,omitempty"`
	// For one person only (seen receipts, requests, reminders); "" = the usual audience.
	To string `json:"to,omitempty"`
	// request: "pickup" or "call". sos: where it came from ("ring", "watch"), if not the phone.
	Kind string `json:"kind,omitempty"`
	// sos: heart rate from the watch, if the person allows it (beats per minute).
	HeartRate int     `json:"heartRate,omitempty"`
	Distance  float64 `json:"distance,omitempty"` // metres
	Duration  int64   `json:"duration,omitempty"` // ms
	Place     string  `json:"place,omitempty"`    // place name, for "Sam arrived at School"
	// Where it happened (sos, checkin), when the phone knew.
	Lat float64 `json:"lat,omitempty"`
	Lon float64 `json:"lon,omitempty"`
	Acc float64 `json:"acc,omitempty"`
}

// addEventLocked records an event. Caller must hold the write lock and save.
func (s *Store) addEventLocked(e Event) {
	s.d.LastEventID++
	e.ID = s.d.LastEventID
	if e.Time == 0 {
		e.Time = time.Now().UnixMilli()
	}
	s.d.Events = append(s.d.Events, e)

	cutoff := time.Now().Add(-eventTTL).UnixMilli()
	drop := 0
	for drop < len(s.d.Events) && (s.d.Events[drop].Time < cutoff || len(s.d.Events)-drop > maxEvents) {
		drop++
	}
	s.d.Events = s.d.Events[drop:]

	// Wake everyone waiting in WaitForEvents.
	close(s.changed)
	s.changed = make(chan struct{})
}

// eventsChanged returns a channel that is closed when the next event is added.
func (s *Store) eventsChanged() <-chan struct{} {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.changed
}

// WaitForEvents is EventsFor, but if there's nothing new it waits (up to the
// context's deadline) for something to happen. Phones keep one of these open
// so alerts such as SOS arrive within seconds, with no push service needed.
func (s *Store) WaitForEvents(ctx context.Context, memberID string, since int64) ([]Event, int64) {
	for {
		changed := s.eventsChanged() // grab before checking, so nothing slips between
		events, cursor := s.EventsFor(memberID, since)
		if len(events) > 0 || since < 0 || since > cursor {
			return events, cursor
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return events, cursor
		}
	}
}

// checkBatteryLocked raises low-battery alerts: once per level per discharge,
// never while charging, and only if the person shares low-battery alerts.
func (s *Store) checkBatteryLocked(m *Member, loc Location) {
	if loc.Battery < 0 {
		return
	}
	if loc.Battery > batteryRearmLevel {
		m.BatteryAlertedAt = 0
		return
	}
	if loc.Charging || !m.SharesLowBattery() {
		return
	}
	for _, level := range batteryAlertLevels {
		if loc.Battery <= level && (m.BatteryAlertedAt == 0 || level < m.BatteryAlertedAt) {
			s.addEventLocked(Event{Type: EventBatteryLow, MemberID: m.ID, Name: m.Name, Battery: loc.Battery, Level: level})
			m.BatteryAlertedAt = level
		}
	}
}

// EventsFor returns the events another phone hasn't seen yet (newer than
// `since`), leaving out the asker's own. It also returns the cursor to send
// next time. A phone without a cursor yet (since < 0) gets no backlog, just the
// cursor, so a freshly joined phone isn't flooded with old alerts. (0 is a real
// cursor: "nothing has happened yet".) If the server's data was reset (since is
// ahead of us), the phone starts over too.
func (s *Store) EventsFor(memberID string, since int64) ([]Event, int64) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	cursor := s.d.LastEventID
	out := []Event{}
	if since < 0 || since > cursor {
		return out, cursor
	}
	viewer, ok := s.d.Members[memberID]
	if !ok {
		return out, cursor
	}
	for _, e := range s.d.Events {
		if e.ID > since && s.eventVisibleLocked(e, viewer) {
			out = append(out, e)
		}
	}
	return out, cursor
}

// ChatEvents tells each recipient about a new chat message (one event each).
func (s *Store) ChatEvents(to []string, fromID, fromName, conv, title, preview string, msgID int64) {
	if len(to) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, id := range to {
		if _, ok := s.d.Members[id]; ok {
			s.addEventLocked(Event{Type: EventChat, To: id, MemberID: fromID, Name: fromName, Conv: conv, Title: title, Note: preview, Message: msgID})
		}
	}
	_ = s.saveLocked()
}
