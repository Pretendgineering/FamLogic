package main

import (
	"errors"
	"math"
	"slices"
	"sort"
	"time"
)

// Places and geofences: named areas such as Home or School that belong to one
// circle, with "Sam arrived at School" / "Alex left Work" alerts.

// Place is a named area on the map that belongs to one circle.
type Place struct {
	ID        string  `json:"id"`
	CircleID  string  `json:"circleId"`
	Name      string  `json:"name"`
	Lat       float64 `json:"lat"`
	Lon       float64 `json:"lon"`
	Radius    float64 `json:"radius"` // metres
	CreatedBy string  `json:"createdBy,omitempty"`
	CreatedAt int64   `json:"createdAt"`
}

// Presence is whether a member is inside a place, as last decided.
type Presence struct {
	Inside bool `json:"inside"`
	// First time a fix put them outside while they were counted as inside. A
	// departure only counts once it has lasted a little while (a GPS blip
	// shouldn't announce "left Home").
	OutsideSince int64 `json:"outsideSince,omitempty"`
}

const (
	EventArrived = "arrived"
	EventLeft    = "left"

	minPlaceRadius = 30
	maxPlaceRadius = 5000
	// Fixes less precise than this are ignored for arrivals and departures.
	maxGeofenceAccuracy = 250
	// How long someone must stay outside (and how many fixes) before "left" is announced.
	departureDelay = 60 * time.Second
)

var ErrBadPlace = errors.New("places need a name and a radius between 30 m and 5 km")

// exitMargin is the buffer zone around a place: you arrive when inside the
// radius, but only leave once you're this much further out, so standing near
// the edge doesn't flip back and forth.
func exitMargin(radius float64) float64 { return math.Max(40, radius*0.25) }

// distanceMeters is the great-circle distance between two points.
func distanceMeters(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371000.0
	toRad := func(d float64) float64 { return d * math.Pi / 180 }
	dLat, dLon := toRad(lat2-lat1), toRad(lon2-lon1)
	a := math.Sin(dLat/2)*math.Sin(dLat/2) + math.Cos(toRad(lat1))*math.Cos(toRad(lat2))*math.Sin(dLon/2)*math.Sin(dLon/2)
	return 2 * r * math.Asin(math.Min(1, math.Sqrt(a)))
}

func validPlace(p *Place) bool {
	return p.Name != "" && p.Radius >= minPlaceRadius && p.Radius <= maxPlaceRadius &&
		p.Lat >= -90 && p.Lat <= 90 && p.Lon >= -180 && p.Lon <= 180
}

// PlacesFor returns the places in a member's circles, by name.
func (s *Store) PlacesFor(memberID string) []Place {
	s.mu.RLock()
	defer s.mu.RUnlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return nil
	}
	return s.placesLocked(func(p *Place) bool { return slices.Contains(m.Circles, p.CircleID) })
}

// AllPlaces is for the dashboard.
func (s *Store) AllPlaces() []Place {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.placesLocked(func(*Place) bool { return true })
}

func (s *Store) placesLocked(keep func(*Place) bool) []Place {
	out := []Place{}
	for _, p := range s.d.Places {
		if keep(p) {
			out = append(out, *p)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// CreatePlace adds a place to a circle. byMember must be in that circle ("" = the
// admin). Everyone currently inside is counted as already there, so making a
// place doesn't announce a burst of arrivals.
func (s *Store) CreatePlace(byMember string, p Place) (*Place, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validPlace(&p) {
		return nil, ErrBadPlace
	}
	if _, ok := s.d.Circles[p.CircleID]; !ok {
		return nil, ErrNotFound
	}
	if byMember != "" {
		m, ok := s.d.Members[byMember]
		if !ok || !slices.Contains(m.Circles, p.CircleID) {
			return nil, ErrNotFound
		}
	}
	p.ID = randomString(6)
	p.CreatedBy = byMember
	p.CreatedAt = time.Now().UnixMilli()
	s.d.Places[p.ID] = &p
	for _, m := range s.d.Members {
		if m.Loc != nil && !m.Paused && slices.Contains(m.Circles, p.CircleID) {
			inside := distanceMeters(m.Loc.Lat, m.Loc.Lon, p.Lat, p.Lon) <= p.Radius
			setPresence(m, p.ID, Presence{Inside: inside})
		}
	}
	cp := p
	return &cp, s.saveLocked()
}

// UpdatePlace renames, moves or resizes a place. byMember must be in its circle.
func (s *Store) UpdatePlace(byMember, id string, name *string, lat, lon, radius *float64) (*Place, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.d.Places[id]
	if !ok || !s.mayEditPlaceLocked(byMember, p) {
		return nil, ErrNotFound
	}
	next := *p
	if name != nil {
		next.Name = *name
	}
	if lat != nil && lon != nil {
		next.Lat, next.Lon = *lat, *lon
	}
	if radius != nil {
		next.Radius = *radius
	}
	if !validPlace(&next) {
		return nil, ErrBadPlace
	}
	*p = next
	cp := *p
	return &cp, s.saveLocked()
}

func (s *Store) DeletePlace(byMember, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.d.Places[id]
	if !ok || !s.mayEditPlaceLocked(byMember, p) {
		return ErrNotFound
	}
	delete(s.d.Places, id)
	for _, m := range s.d.Members {
		delete(m.Presence, id)
	}
	return s.saveLocked()
}

func (s *Store) mayEditPlaceLocked(byMember string, p *Place) bool {
	if byMember == "" {
		return true // the admin
	}
	m, ok := s.d.Members[byMember]
	return ok && slices.Contains(m.Circles, p.CircleID)
}

func setPresence(m *Member, placeID string, pr Presence) {
	if m.Presence == nil {
		m.Presence = map[string]*Presence{}
	}
	m.Presence[placeID] = &pr
}

// checkPlacesLocked updates where a member is after a new fix and raises
// arrived/left alerts. Caller holds the lock and saves.
func (s *Store) checkPlacesLocked(m *Member, loc Location) {
	if loc.Accuracy > maxGeofenceAccuracy {
		return // too vague to say whether they're in or out
	}
	now := time.Now()
	for _, p := range s.d.Places {
		if !slices.Contains(m.Circles, p.CircleID) {
			continue
		}
		d := distanceMeters(loc.Lat, loc.Lon, p.Lat, p.Lon)
		pr, known := m.Presence[p.ID]
		switch {
		case !known:
			// First fix since the place exists (or since joining the circle): just note it.
			setPresence(m, p.ID, Presence{Inside: d <= p.Radius})
		case !pr.Inside && d <= p.Radius:
			pr.Inside, pr.OutsideSince = true, 0
			s.placeEventLocked(EventArrived, m, p)
		case pr.Inside && d > p.Radius+exitMargin(p.Radius):
			clearlyOut := d-loc.Accuracy > p.Radius+2*exitMargin(p.Radius)
			if pr.OutsideSince == 0 && !clearlyOut {
				pr.OutsideSince = now.UnixMilli() // might be a GPS blip: wait for another fix
			} else if clearlyOut || now.Sub(time.UnixMilli(pr.OutsideSince)) >= departureDelay {
				pr.Inside, pr.OutsideSince = false, 0
				s.placeEventLocked(EventLeft, m, p)
			}
		case pr.Inside:
			pr.OutsideSince = 0 // back inside (or in the buffer zone): no departure
		}
	}
}

func (s *Store) placeEventLocked(kind string, m *Member, p *Place) {
	if !m.SharesArrivals() {
		return
	}
	s.addEventLocked(Event{
		Type: kind, MemberID: m.ID, Name: m.Name,
		CircleID: p.CircleID, PlaceID: p.ID, Place: p.Name,
		Lat: p.Lat, Lon: p.Lon,
	})
}

// AdminPlaceNames maps each member to a place they're in (for the dashboard).
func (s *Store) AdminPlaceNames() map[string]string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := map[string]string{}
	for _, m := range s.d.Members {
		if m.Paused {
			continue
		}
		var names []string
		for id, pr := range m.Presence {
			if p, ok := s.d.Places[id]; ok && pr.Inside {
				names = append(names, p.Name)
			}
		}
		if len(names) > 0 {
			sort.Strings(names)
			out[m.ID] = names[0]
		}
	}
	return out
}

// placeNameForLocked returns the name of a place `m` is in, as `viewer` would see it:
// only places in circles where `m` shares their location with the viewer.
func (s *Store) placeNameForLocked(m, viewer *Member) string {
	var names []string
	for id, pr := range m.Presence {
		p, ok := s.d.Places[id]
		if !ok || !pr.Inside || !slices.Contains(viewer.Circles, p.CircleID) ||
			!slices.Contains(m.Circles, p.CircleID) || slices.Contains(m.HiddenFrom, p.CircleID) {
			continue
		}
		names = append(names, p.Name)
	}
	if len(names) == 0 {
		return ""
	}
	sort.Strings(names)
	return names[0]
}
