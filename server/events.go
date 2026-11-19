package main

import "time"

// Alerts ("events") are kept briefly on the server and picked up by phones the
// next time they talk to it, either when sending their location or when the map
// is open. This needs no push service; the delay is at most one update interval.

const (
	EventBatteryLow = "battery_low"

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
	Battery  int    `json:"battery,omitempty"` // battery_low: the level when it happened
	Level    int    `json:"level,omitempty"`   // battery_low: which alert level was crossed
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
	for _, e := range s.d.Events {
		if e.ID > since && e.MemberID != memberID {
			out = append(out, e)
		}
	}
	return out, cursor
}
