package main

import (
	"time"
)

// Trails: an opt-in history of where someone has been over the last few hours,
// shown as a line on the map. Off unless the person turns it on; turning it off
// deletes it. Only people who can see someone's location can see their trail.

// TrailPoint is one spot on a trail.
type TrailPoint struct {
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
	Time int64   `json:"time"` // unix ms
}

const (
	// A point is kept when someone has moved this far since the last one...
	trailMinMove = 30.0 // metres
	// ...or this long has passed (so time spent in one spot still shows).
	trailMinGap = 10 * time.Minute
	// Fixes less precise than this are left out of trails.
	trailMaxAccuracy = 200.0
)

// TrailHourChoices are the lengths a person can keep their trail for.
var TrailHourChoices = []int{6, 12, 24}

func validTrailHours(h int) bool {
	if h == 0 {
		return true
	}
	for _, c := range TrailHourChoices {
		if h == c {
			return true
		}
	}
	return false
}

// addTrailPointLocked records a fix on the member's trail (if they keep one)
// and drops points older than their chosen length. Caller holds the lock.
func (s *Store) addTrailPointLocked(m *Member, loc Location) {
	if m.TrailHours <= 0 || m.Paused {
		return
	}
	cutoff := time.Now().Add(-time.Duration(m.TrailHours) * time.Hour).UnixMilli()
	trail := s.d.Trails[m.ID]
	drop := 0
	for drop < len(trail) && trail[drop].Time < cutoff {
		drop++
	}
	trail = trail[drop:]
	if loc.Accuracy <= trailMaxAccuracy && loc.Time >= cutoff {
		keep := len(trail) == 0
		if !keep {
			last := trail[len(trail)-1]
			keep = loc.Time > last.Time &&
				(distanceMeters(last.Lat, last.Lon, loc.Lat, loc.Lon) >= trailMinMove ||
					time.Duration(loc.Time-last.Time)*time.Millisecond >= trailMinGap)
		}
		if keep {
			trail = append(trail, TrailPoint{Lat: loc.Lat, Lon: loc.Lon, Time: loc.Time})
		}
	}
	if len(trail) == 0 {
		delete(s.d.Trails, m.ID)
	} else {
		s.d.Trails[m.ID] = trail
	}
}

// SetTrailHours turns a member's trail on (6/12/24 hours) or off (0). Turning it
// off deletes the trail; shortening it drops the points that no longer fit.
func (s *Store) SetTrailHours(id string, hours int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	m.TrailHours = hours
	if hours == 0 {
		delete(s.d.Trails, id)
	} else if m.Loc != nil {
		s.addTrailPointLocked(m, *m.Loc) // prunes, and starts the trail where they are
	}
	return s.saveLocked()
}

// TrailFor returns member `id`'s trail as `viewerID` may see it ("" = the
// admin), oldest first. ok is false if the viewer may not see it.
func (s *Store) TrailFor(viewerID, id string) ([]TrailPoint, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, false
	}
	if viewerID != "" && viewerID != id {
		viewer, ok := s.d.Members[viewerID]
		if !ok || !sharesLocationWith(m, viewer) {
			return nil, false
		}
	}
	if m.TrailHours <= 0 || (m.Paused && viewerID != id) {
		return []TrailPoint{}, true
	}
	cutoff := time.Now().Add(-time.Duration(m.TrailHours) * time.Hour).UnixMilli()
	out := []TrailPoint{}
	for _, p := range s.d.Trails[id] {
		if p.Time >= cutoff {
			out = append(out, p)
		}
	}
	return out, true
}
