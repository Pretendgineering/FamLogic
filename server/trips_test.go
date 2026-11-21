package main

import (
	"testing"
	"time"
)

// A day: home 08:00-08:30, drive 3 km to school (10 min), stay until 15:00,
// a short walk around the playground, then drive home.
func dayOfPoints(start time.Time) []TrailPoint {
	var pts []TrailPoint
	t := start
	add := func(meters float64, d time.Duration) {
		lat, lon := north(homeLat, homeLon, meters)
		pts = append(pts, TrailPoint{Lat: lat, Lon: lon, Time: t.UnixMilli()})
		t = t.Add(d)
	}
	for i := 0; i < 4; i++ { // at home, a point every 10 min
		add(0, 10*time.Minute)
	}
	for m := 300.0; m <= 3000; m += 300 { // driving
		add(m, time.Minute)
	}
	for i := 0; i < 6; i++ { // at school
		add(3000, 10*time.Minute)
	}
	add(3080, 2*time.Minute) // a little walk (not a trip)
	add(3000, 10*time.Minute)
	add(3000, 10*time.Minute)
	for m := 2700.0; m >= 0; m -= 300 { // home again
		add(m, time.Minute)
	}
	add(0, 10*time.Minute)
	add(0, 10*time.Minute)
	return pts
}

func TestSplitTrips(t *testing.T) {
	start := time.Date(2026, 11, 20, 8, 0, 0, 0, time.UTC)
	trips := splitTrips(dayOfPoints(start), start.Add(48*time.Hour))
	if len(trips) != 2 {
		t.Fatalf("want 2 trips, got %d: %+v", len(trips), trips)
	}
	out, back := trips[0], trips[1]
	if d := out.Distance; d < 2900 || d > 3100 {
		t.Fatalf("first trip distance %v", d)
	}
	if mins := time.Duration(out.End-out.Start) * time.Millisecond; mins < 9*time.Minute || mins > 12*time.Minute {
		t.Fatalf("first trip took %v", mins)
	}
	if back.Start <= out.End || back.Ongoing || out.Ongoing {
		t.Fatalf("trips out of order or ongoing: %+v %+v", out, back)
	}
}

func TestOngoingTrip(t *testing.T) {
	now := time.Now()
	pts := []TrailPoint{}
	for i := 0; i <= 3; i++ { // home for 30 min
		lat, lon := north(homeLat, homeLon, 0)
		pts = append(pts, TrailPoint{Lat: lat, Lon: lon, Time: now.Add(time.Duration(i*10-45) * time.Minute).UnixMilli()})
	}
	for m := 300.0; m <= 1500; m += 300 { // still driving
		lat, lon := north(homeLat, homeLon, m)
		pts = append(pts, TrailPoint{Lat: lat, Lon: lon, Time: now.Add(time.Duration(m/300-6) * time.Minute).UnixMilli()})
	}
	trips := splitTrips(pts, now)
	if len(trips) != 1 || !trips[0].Ongoing {
		t.Fatalf("want one ongoing trip: %+v", trips)
	}
}

func TestTripsNameFences(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	family := s.Circles()[0].ID
	s.SetTripDays(sam, 3)
	school := func() (float64, float64) { return north(homeLat, homeLon, 3000) }
	slat, slon := school()
	s.CreatePlace(mom, Place{CircleID: family, Name: "Home", Lat: homeLat, Lon: homeLon, Radius: 100})
	s.CreatePlace(mom, Place{CircleID: family, Name: "School", Lat: slat, Lon: slon, Radius: 100})
	s.mu.Lock()
	s.d.Trails[sam] = dayOfPoints(time.Now().Add(-10 * time.Hour))
	s.mu.Unlock()
	trips, ok := s.TripsFor(mom, sam)
	if !ok || len(trips) != 2 {
		t.Fatalf("trips: %v %+v", ok, trips)
	}
	// Newest first: School -> Home, then Home -> School.
	if trips[0].From != "School" || trips[0].To != "Home" || trips[1].From != "Home" || trips[1].To != "School" {
		t.Fatalf("names: %q->%q, %q->%q", trips[0].From, trips[0].To, trips[1].From, trips[1].To)
	}
}

func TestTripAlertAndRetention(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	family := s.Circles()[0].ID
	slat, slon := north(homeLat, homeLon, 3000)
	school, _ := s.CreatePlace(mom, Place{CircleID: family, Name: "School", Lat: slat, Lon: slon, Radius: 100})
	s.SetTripDays(sam, 1)
	_, cur := s.EventsFor(mom, -1)
	now := time.Now()
	// The fixes below are backdated; pretend trips were switched on before them.
	s.d.Members[sam].LastTripEnd = now.Add(-2 * time.Hour).UnixMilli()

	// Drive from home to school, then stay put: a trip alert once the stay is long enough.
	fix := func(meters float64, at time.Time) {
		lat, lon := north(homeLat, homeLon, meters)
		if err := s.UpdateLocation(sam, Location{Lat: lat, Lon: lon, Accuracy: 10, Battery: 80, Time: at.UnixMilli(), Speed: 13}); err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i <= 3; i++ {
		fix(0, now.Add(time.Duration(i*10-60)*time.Minute))
	}
	for m := 300.0; m <= 3000; m += 300 {
		fix(m, now.Add(time.Duration(m/300-30)*time.Minute))
	}
	fix(3000, now.Add(-12*time.Minute)) // arrived ~18 min ago and stayed
	fix(3000, now.Add(-2*time.Minute))
	evs, _ := s.EventsFor(mom, cur)
	var trips []Event
	for _, e := range evs {
		if e.Type == EventTrip {
			trips = append(trips, e)
		}
	}
	if len(trips) != 1 || trips[0].PlaceID != school.ID || trips[0].Distance < 2900 {
		t.Fatalf("trip alerts: %+v", trips)
	}
	fix(3000, now.Add(-time.Minute))
	evs, _ = s.EventsFor(mom, cur)
	n := 0
	for _, e := range evs {
		if e.Type == EventTrip {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("trip announced %d times", n)
	}

	// Trips keep their points even with the trail off; the trail itself shows nothing.
	if tr, _ := s.TrailFor(mom, sam); len(tr) != 0 {
		t.Fatalf("trail shown while off: %d points", len(tr))
	}
	got, _ := s.TripsFor(mom, sam)
	if len(got) != 1 || got[0].To != "School" || got[0].TopSpeed < 12 || got[0].AvgSpeed <= 0 {
		t.Fatalf("trips: %+v", got)
	}
	// Turning trips off deletes the points.
	s.SetTripDays(sam, 0)
	if got, _ := s.TripsFor(mom, sam); len(got) != 0 || len(s.d.Trails[sam]) != 0 {
		t.Fatalf("trips kept after turning off: %d trips, %d points", len(got), len(s.d.Trails[sam]))
	}
}

func TestGapIsNotATrip(t *testing.T) {
	now := time.Now()
	lat1, lon1 := north(homeLat, homeLon, 0)
	lat2, lon2 := north(homeLat, homeLon, 2000)
	pts := []TrailPoint{
		{Lat: lat1, Lon: lon1, Time: now.Add(-9 * time.Hour).UnixMilli()},
		{Lat: lat2, Lon: lon2, Time: now.Add(-1 * time.Hour).UnixMilli()}, // 8 hours later, 2 km away
		{Lat: lat2, Lon: lon2, Time: now.Add(-50 * time.Minute).UnixMilli()},
	}
	if trips := splitTrips(pts, now); len(trips) != 0 {
		t.Fatalf("a gap became a trip: %+v", trips)
	}
}
