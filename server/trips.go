package main

import "time"

const EventTrip = "trip" // someone finished a trip

// Trips: a trail split into separate journeys ("Home → School, 12 min, 4.2 km")
// at the places where someone stayed put for a while. Worked out from the trail
// points whenever asked, so nothing extra is stored.

const (
	stayRadius   = 150.0           // metres: staying within this counts as one place...
	stayMinTime  = 5 * time.Minute // ...for at least this long
	tripMinMoved = 300.0           // metres: shorter "trips" are just wandering about
	// A trip whose last point is newer than this may still be under way.
	tripOngoing = 15 * time.Minute
	// No points for this long (phone off, paused): we don't know how they got
	// from one spot to the next, so that's not a trip.
	tripMaxGap = 45 * time.Minute
)

// Trip is one journey between two stops.
type Trip struct {
	Start    int64   `json:"start"` // unix ms
	End      int64   `json:"end"`
	Distance float64 `json:"distance"` // metres along the way
	From     string  `json:"from,omitempty"`
	To       string  `json:"to,omitempty"` // fence names, when it started or ended in one
	// Nearest street, when it didn't start or end in a fence (filled in by the server's geocoder).
	FromNear string       `json:"fromNear,omitempty"`
	ToNear   string       `json:"toNear,omitempty"`
	AvgSpeed float64      `json:"avgSpeed"` // m/s, over the whole trip
	TopSpeed float64      `json:"topSpeed"` // m/s, the fastest the phone reported (or worked out)
	Ongoing  bool         `json:"ongoing,omitempty"`
	Points   []TrailPoint `json:"points"`
	// A stop has been seen after it (so it has certainly ended).
	stopped bool
}

// speeds works out a trip's average and top speed.
func (t *Trip) speeds() {
	if secs := float64(t.End-t.Start) / 1000; secs > 0 {
		t.AvgSpeed = t.Distance / secs
	}
	top := 0.0
	for i, p := range t.Points {
		top = max(top, p.Speed)
		if i > 0 && top == 0 {
			// No speed from the phone: work it out between points at least 20 s apart.
			q := t.Points[i-1]
			if dt := float64(p.Time-q.Time) / 1000; dt >= 20 {
				top = max(top, distanceMeters(q.Lat, q.Lon, p.Lat, p.Lon)/dt)
			}
		}
	}
	t.TopSpeed = max(top, t.AvgSpeed)
}

// splitTrips finds the trips in a trail (oldest point first).
func splitTrips(points []TrailPoint, now time.Time) []Trip {
	var trips []Trip
	from := 0
	for i := 1; i <= len(points); i++ {
		if i == len(points) || time.Duration(points[i].Time-points[i-1].Time)*time.Millisecond > tripMaxGap {
			trips = append(trips, splitRun(points[from:i], now)...)
			from = i
		}
	}
	return trips
}

// splitRun finds the trips in a stretch of points with no long gaps.
func splitRun(points []TrailPoint, now time.Time) []Trip {
	// Mark stays: runs of points that remain within stayRadius of the run's
	// first point for at least stayMinTime.
	n := len(points)
	still := make([]bool, n)
	for i := 0; i < n; {
		j := i + 1
		for j < n && distanceMeters(points[i].Lat, points[i].Lon, points[j].Lat, points[j].Lon) <= stayRadius {
			j++
		}
		if time.Duration(points[j-1].Time-points[i].Time)*time.Millisecond >= stayMinTime {
			for k := i; k < j; k++ {
				still[k] = true
			}
			i = j
		} else {
			i++
		}
	}
	// A trip runs from the last point of one stay to the first point of the next.
	var trips []Trip
	for i := 0; i < n; i++ {
		if still[i] && (i+1 >= n || still[i+1]) {
			continue
		}
		start := i // the last still point before moving (or the very first point)
		j := i + 1
		for j < n && !still[j] {
			j++
		}
		end := min(j, n-1) // the first point of the next stay (or the last point)
		seg := points[start : end+1]
		dist := 0.0
		for k := 1; k < len(seg); k++ {
			dist += distanceMeters(seg[k-1].Lat, seg[k-1].Lon, seg[k].Lat, seg[k].Lon)
		}
		if len(seg) >= 2 && distanceMeters(seg[0].Lat, seg[0].Lon, seg[len(seg)-1].Lat, seg[len(seg)-1].Lon) >= tripMinMoved {
			// It started when they first moved: the stay's last point can be up to
			// ten minutes older (still points are only kept every ten minutes).
			began := seg[0].Time
			if still[start] {
				began = seg[1].Time
			}
			t := Trip{Start: began, End: seg[len(seg)-1].Time, Distance: dist, Points: append([]TrailPoint{}, seg...)}
			t.Ongoing = j >= n && now.Sub(time.UnixMilli(t.End)) < tripOngoing
			t.stopped = j < n
			t.speeds()
			trips = append(trips, t)
		}
		i = end - 1 // the next search starts at that stay
		if j >= n {
			break
		}
	}
	return trips
}

// tripWindow is how far back a member's trips go.
func tripWindow(m *Member) time.Duration { return time.Duration(m.TripDays) * 24 * time.Hour }

// placeAtLocked returns a fence at a point (among `viewer`'s groups; nil = any).
func (s *Store) placeAtLocked(p TrailPoint, viewer *Member) *Place {
	for _, pl := range s.d.Places {
		if viewer != nil && !contains(viewer.Circles, pl.CircleID) {
			continue
		}
		if distanceMeters(p.Lat, p.Lon, pl.Lat, pl.Lon) <= pl.Radius+exitMargin(pl.Radius) {
			return pl
		}
	}
	return nil
}

// TripsFor returns member `id`'s trips as `viewerID` may see them ("" = the
// admin), newest first, with fence names for where they started and ended.
func (s *Store) TripsFor(viewerID, id string) ([]Trip, bool) {
	points, ok := s.pointsFor(viewerID, id, tripWindow)
	if !ok {
		return nil, false
	}
	trips := splitTrips(points, time.Now())
	s.mu.RLock()
	defer s.mu.RUnlock()
	viewer := s.d.Members[viewerID]
	name := func(p TrailPoint) string {
		if pl := s.placeAtLocked(p, viewer); pl != nil {
			return pl.Name
		}
		return ""
	}
	out := make([]Trip, 0, len(trips))
	for i := len(trips) - 1; i >= 0; i-- {
		t := trips[i]
		t.From = name(t.Points[0])
		if !t.Ongoing {
			t.To = name(t.Points[len(t.Points)-1])
		}
		out = append(out, t)
	}
	return out, true
}

// checkTripEndedLocked raises a "trip" alert when a member's latest trip has
// just ended (they've now stayed put for a few minutes). Caller holds the lock.
func (s *Store) checkTripEndedLocked(m *Member) {
	now := time.Now()
	since := now.Add(-6 * time.Hour).UnixMilli()
	var recent []TrailPoint
	for _, p := range s.d.Trails[m.ID] {
		if p.Time >= since {
			recent = append(recent, p)
		}
	}
	trips := splitTrips(recent, now)
	for i := len(trips) - 1; i >= 0; i-- {
		t := trips[i]
		if !t.stopped || t.End <= m.LastTripEnd {
			continue
		}
		if now.Sub(time.UnixMilli(t.End)) > time.Hour {
			break // too old to be news
		}
		m.LastTripEnd = t.End
		e := Event{Type: EventTrip, MemberID: m.ID, Name: m.Name, Distance: t.Distance, Duration: t.End - t.Start}
		last := t.Points[len(t.Points)-1]
		e.Lat, e.Lon = last.Lat, last.Lon
		// Fence ids only: each phone shows the names of fences it knows.
		if pl := s.placeAtLocked(t.Points[0], m); pl != nil {
			e.FromPlaceID = pl.ID
		}
		if pl := s.placeAtLocked(last, m); pl != nil {
			e.PlaceID = pl.ID
		}
		s.addEventLocked(e)
		return
	}
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}
