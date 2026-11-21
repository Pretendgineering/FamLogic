package main

import (
	"context"
	"slices"
	"time"
)

// Phone health: each phone tells the server which app version it runs and what
// could stop it from sharing (no "Allow all the time", battery restrictions...),
// so the dashboard can show problems before they matter. And if a phone that
// shares its location goes quiet for hours (switched off, flat, app killed),
// the people it shares with get one "hasn't been in touch" alert.

const EventSilent = "silent"

// The problems a phone can report.
var knownIssues = []string{
	"location",      // no location permission
	"background",    // no "Allow all the time"
	"battery",       // restricted by battery optimisation
	"notifications", // notifications off, so it won't show alerts
	"location_off",  // location turned off in the phone's settings
}

// SetStatus records what a phone reports about itself.
func (s *Store) SetStatus(id, app string, issues []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	clean := []string{}
	for _, i := range issues {
		if slices.Contains(knownIssues, i) && !slices.Contains(clean, i) {
			clean = append(clean, i)
		}
	}
	if len(app) > 20 {
		app = app[:20]
	}
	m.App, m.Issues = app, clean
	m.StatusAt = time.Now().UnixMilli()
	return s.saveLocked()
}

// Touch notes that a phone was just in touch. It's kept in memory and written
// with the next save, except when it ends a silence (then saved right away).
func (s *Store) Touch(id string) {
	now := time.Now().UnixMilli()
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return
	}
	m.LastContact = now
	if m.SilentSince != 0 {
		m.SilentSince = 0
		_ = s.saveLocked()
	}
}

// silenceSince is when the server started watching: nobody counts as quiet for
// longer than that, so a restart (which loses unsaved contact times) never
// raises false alerts.
func (s *Store) startContactGrace() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.watchStart = time.Now().UnixMilli()
}

// CheckSilence raises a "silent" alert for each sharing phone that hasn't been
// in touch for longer than `after`. Once per silence.
func (s *Store) CheckSilence(after time.Duration) {
	cutoff := time.Now().Add(-after).UnixMilli()
	s.mu.Lock()
	defer s.mu.Unlock()
	changed := false
	for _, m := range s.d.Members {
		// Paused people may legitimately be quiet; people who never shared have nothing to miss.
		if m.Paused || m.Loc == nil || m.SilentSince != 0 || max(m.LastContact, s.watchStart) > cutoff {
			continue
		}
		m.SilentSince = time.Now().UnixMilli()
		s.addEventLocked(Event{Type: EventSilent, MemberID: m.ID, Name: m.Name, Lat: m.Loc.Lat, Lon: m.Loc.Lon, Acc: m.Loc.Accuracy,
			Battery: m.Loc.Battery, Time: m.SilentSince})
		changed = true
	}
	if changed {
		_ = s.saveLocked()
	}
}

// WatchSilence checks every few minutes until ctx ends. after <= 0 turns it off.
func (s *Store) WatchSilence(ctx context.Context, after time.Duration) {
	if after <= 0 {
		return
	}
	s.startContactGrace()
	t := time.NewTicker(5 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.CheckSilence(after)
		}
	}
}
