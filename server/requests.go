package main

import (
	"errors"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

// Things people send to one person: "I've seen your SOS", "pick me up", "call
// me", and reminders tied to a place ("when I get home, take the bins out").

const (
	EventSOSSeen  = "sos_seen" // to the person with the SOS: someone has seen it
	EventRequest  = "request"  // to one person: Kind "pickup" or "call"
	EventReminder = "reminder" // to the person who set it, on arriving or leaving
)

var (
	ErrNoSOS       = errors.New("they don't have an SOS right now")
	ErrBadRequest  = errors.New("requests are \"pickup\" or \"call\"")
	ErrBadReminder = errors.New("a reminder needs a fence and 1-140 characters of text")
)

// SOSSeen tells the person with an SOS that `viewerID` has seen it (once per person per SOS).
func (s *Store) SOSSeen(viewerID, sosID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	viewer, ok1 := s.d.Members[viewerID]
	m, ok2 := s.d.Members[sosID]
	if !ok1 || !ok2 || len(sharedCircles(viewer, m)) == 0 {
		return ErrNotFound
	}
	if m.SOSSince == 0 {
		return ErrNoSOS
	}
	if slices.Contains(m.SOSSeenBy, viewerID) {
		return nil
	}
	m.SOSSeenBy = append(m.SOSSeenBy, viewerID)
	s.addEventLocked(Event{Type: EventSOSSeen, MemberID: viewer.ID, Name: viewer.Name, To: m.ID})
	return s.saveLocked()
}

// SOSSeenNames are the names of who has seen member id's SOS.
func (s *Store) sosSeenNamesLocked(m *Member) []string {
	out := []string{}
	for _, id := range m.SOSSeenBy {
		if v, ok := s.d.Members[id]; ok {
			out = append(out, v.Name)
		}
	}
	return out
}

// Request asks one person to pick the sender up or call them. It carries the
// sender's last location, so the other person knows where to go.
func (s *Store) Request(fromID, toID, kind string, loc *Location) error {
	if kind != "pickup" && kind != "call" {
		return ErrBadRequest
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	from, ok1 := s.d.Members[fromID]
	to, ok2 := s.d.Members[toID]
	if !ok1 || !ok2 || fromID == toID || len(sharedCircles(from, to)) == 0 {
		return ErrNotFound
	}
	s.applyFixLocked(from, loc)
	e := Event{Type: EventRequest, MemberID: from.ID, Name: from.Name, To: to.ID, Kind: kind}
	if from.Loc != nil {
		e.Lat, e.Lon, e.Acc = from.Loc.Lat, from.Loc.Lon, from.Loc.Accuracy
	}
	s.addEventLocked(e)
	return s.saveLocked()
}

// Reminder is a note to yourself for when you arrive at (or leave) a fence. It fires once.
type Reminder struct {
	ID        string `json:"id"`
	MemberID  string `json:"memberId"`
	PlaceID   string `json:"placeId"`
	OnLeave   bool   `json:"onLeave,omitempty"`
	Text      string `json:"text"`
	CreatedAt int64  `json:"createdAt"`
}

func cleanReminderText(t string) (string, bool) {
	t = strings.Join(strings.Fields(t), " ")
	return t, t != "" && utf8.RuneCountInString(t) <= maxNoteLen
}

// AddReminder sets a reminder for a fence in one of the member's groups.
func (s *Store) AddReminder(memberID, placeID, text string, onLeave bool) (*Reminder, error) {
	text, ok := cleanReminderText(text)
	if !ok {
		return nil, ErrBadReminder
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	p, ok2 := s.d.Places[placeID]
	if !ok || !ok2 || !slices.Contains(m.Circles, p.CircleID) {
		return nil, ErrBadReminder
	}
	r := &Reminder{ID: randomString(6), MemberID: memberID, PlaceID: placeID, OnLeave: onLeave, Text: text, CreatedAt: time.Now().UnixMilli()}
	s.d.Reminders[r.ID] = r
	cp := *r
	return &cp, s.saveLocked()
}

// RemindersFor lists a member's reminders, oldest first.
func (s *Store) RemindersFor(memberID string) []Reminder {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []Reminder{}
	for _, r := range s.d.Reminders {
		if r.MemberID == memberID {
			out = append(out, *r)
		}
	}
	slices.SortFunc(out, func(a, b Reminder) int { return int(a.CreatedAt - b.CreatedAt) })
	return out
}

func (s *Store) DeleteReminder(memberID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.d.Reminders[id]
	if !ok || r.MemberID != memberID {
		return ErrNotFound
	}
	delete(s.d.Reminders, id)
	return s.saveLocked()
}

// fireRemindersLocked sends (and removes) m's reminders for arriving at or
// leaving p. They're private, so they ignore alert hours and sharing settings.
func (s *Store) fireRemindersLocked(m *Member, p *Place, leaving bool) {
	for id, r := range s.d.Reminders {
		if r.MemberID != m.ID || r.PlaceID != p.ID || r.OnLeave != leaving {
			continue
		}
		s.addEventLocked(Event{Type: EventReminder, MemberID: m.ID, Name: m.Name, To: m.ID, Note: r.Text,
			PlaceID: p.ID, Place: p.Name, Kind: map[bool]string{false: "arrive", true: "leave"}[leaving]})
		delete(s.d.Reminders, id)
	}
}

// deletePlaceRemindersLocked drops reminders for a fence that's gone.
func (s *Store) deletePlaceRemindersLocked(placeID string) {
	for id, r := range s.d.Reminders {
		if r.PlaceID == placeID {
			delete(s.d.Reminders, id)
		}
	}
}
