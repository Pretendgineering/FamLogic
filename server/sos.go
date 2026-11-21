package main

import (
	"strings"
	"time"
	"unicode/utf8"
)

// SOS and check-ins: people telling the family something right now.

const maxNoteLen = 140

// applyFixLocked records a location the phone sent with an SOS or check-in, if any.
func (s *Store) applyFixLocked(m *Member, loc *Location) {
	if loc == nil || m.Paused {
		return
	}
	if m.Loc == nil || loc.Time >= m.Loc.Time {
		l := *loc
		// SOS and check-ins don't carry the battery level: keep the last known one.
		l.Battery, l.Charging = -1, false
		if m.Loc != nil {
			l.Battery, l.Charging = m.Loc.Battery, m.Loc.Charging
		}
		m.Loc = &l
	}
}

// StartSOS marks the member as needing help and alerts everyone. SOS also
// un-pauses sharing: someone asking for help wants to be found. Pressing SOS
// again while one is active re-sends the alert (e.g. if nobody reacted).
func (s *Store) StartSOS(id string, loc *Location) (*Member, error) {
	return s.StartSOSWith(id, loc, SOSDetails{})
}

// SOSDetails is extra information an SOS can carry.
type SOSDetails struct {
	Note      string // e.g. what was said into the ring
	Source    string // "ring", "watch", "fall" or "" (the phone)
	HeartRate int
}

// StartSOSWith is StartSOS with extra details.
func (s *Store) StartSOSWith(id string, loc *Location, d SOSDetails) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, ErrNotFound
	}
	m.Paused = false
	s.applyFixLocked(m, loc)
	if m.SOSSince == 0 {
		m.SOSSince = time.Now().UnixMilli()
		m.SOSSeenBy = nil
	}
	e := Event{Type: EventSOS, MemberID: m.ID, Name: m.Name, Note: cleanNote(d.Note), Kind: d.Source}
	if d.HeartRate > 20 && d.HeartRate < 250 {
		e.HeartRate = d.HeartRate
	}
	if m.Loc != nil {
		e.Lat, e.Lon, e.Acc = m.Loc.Lat, m.Loc.Lon, m.Loc.Accuracy
	}
	s.addEventLocked(e)
	cp := *m
	return &cp, s.saveLocked()
}

// EndSOS clears an active SOS and tells everyone the person is OK.
func (s *Store) EndSOS(id string) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, ErrNotFound
	}
	if m.SOSSince != 0 {
		m.SOSSince = 0
		m.SOSSeenBy = nil
		s.addEventLocked(Event{Type: EventSOSEnd, MemberID: m.ID, Name: m.Name})
	}
	cp := *m
	return &cp, s.saveLocked()
}

// CheckIn sends "I'm here" with an optional note. The location goes in the alert
// even if the person has paused sharing: checking in is choosing to share it.
func (s *Store) CheckIn(id, note string, loc *Location) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	s.applyFixLocked(m, loc)
	e := Event{Type: EventCheckIn, MemberID: m.ID, Name: m.Name, Note: cleanNote(note)}
	if loc != nil {
		e.Lat, e.Lon, e.Acc = loc.Lat, loc.Lon, loc.Accuracy
	} else if m.Loc != nil {
		e.Lat, e.Lon, e.Acc = m.Loc.Lat, m.Loc.Lon, m.Loc.Accuracy
	}
	s.addEventLocked(e)
	return s.saveLocked()
}

func cleanNote(n string) string {
	n = strings.Join(strings.Fields(n), " ")
	if utf8.RuneCountInString(n) > maxNoteLen {
		n = string([]rune(n)[:maxNoteLen])
	}
	return n
}
