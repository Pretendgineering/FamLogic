package main

import "time"

// Trips: a trail split into separate journeys ("Home → School, 12 min, 4.2 km")
// at the places where someone stayed put for a while. Worked out from the trail
// points whenever asked, so nothing extra is stored.

const (
	stayRadius   = 150.0           // metres: staying within this counts as one place...
	stayMinTime  = 5 * time.Minute // ...for at least this long
	tripMinMoved = 300.0           // metres: shorter "trips" are just wandering about
	// A trip whose last point is newer than this may still be under way.
	tripOngoing = 15 * time.Minute
)

// Trip is one journey between two stops.
type Trip struct {
	Start    int64        `json:"start"` // unix ms
	End      int64        `json:"end"`
	Distance float64      `json:"distance"` // metres along the way
	From     string       `json:"from,omitempty"`
	To       string       `json:"to,omitempty"` // fence names, when it started or ended in one
	Ongoing  bool         `json:"ongoing,omitempty"`
	Points   []TrailPoint `json:"points"`
}

// splitTrips finds the trips in a trail (oldest point first).
func splitTrips(points []TrailPoint, now time.Time) []Trip {
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
			trips = append(trips, t)
		}
		i = end - 1 // the next search starts at that stay
		if j >= n {
			break
		}
	}
	return trips
}

// TripsFor returns member `id`'s trips as `viewerID` may see them ("" = the
// admin), newest first, with fence names for where they started and ended.
func (s *Store) TripsFor(viewerID, id string) ([]Trip, bool) {
	points, ok := s.TrailFor(viewerID, id)
	if !ok {
		return nil, false
	}
	trips := splitTrips(points, time.Now())
	s.mu.RLock()
	defer s.mu.RUnlock()
	viewer := s.d.Members[viewerID]
	placeAt := func(p TrailPoint) string {
		for _, pl := range s.d.Places {
			if viewer != nil && !contains(viewer.Circles, pl.CircleID) {
				continue
			}
			if distanceMeters(p.Lat, p.Lon, pl.Lat, pl.Lon) <= pl.Radius+exitMargin(pl.Radius) {
				return pl.Name
			}
		}
		return ""
	}
	out := make([]Trip, 0, len(trips))
	for i := len(trips) - 1; i >= 0; i-- {
		t := trips[i]
		t.From = placeAt(t.Points[0])
		if !t.Ongoing {
			t.To = placeAt(t.Points[len(t.Points)-1])
		}
		out = append(out, t)
	}
	return out, true
}

func contains(list []string, v string) bool {
	for _, x := range list {
		if x == v {
			return true
		}
	}
	return false
}
