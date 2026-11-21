package main

import (
	"testing"
	"time"
)

func TestDriveStartAlerts(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	_, cur := s.EventsFor(mom, -1)
	fix := func(driving bool) {
		tick++
		if err := s.UpdateLocation(sam, Location{Lat: 40, Lon: -75, Accuracy: 10, Battery: 80, Time: time.Now().UnixMilli() + tick, Driving: driving}); err != nil {
			t.Fatal(err)
		}
	}
	starts := func() int {
		evs, _ := s.EventsFor(mom, cur)
		n := 0
		for _, e := range evs {
			if e.Type == EventDriveStart {
				n++
			}
		}
		return n
	}
	fix(false)
	fix(true)
	fix(true)
	if starts() != 1 {
		t.Fatalf("want one start alert, got %d", starts())
	}
	fix(false) // a short stop...
	fix(true)  // ...and off again: the same drive
	if starts() != 1 {
		t.Fatalf("restart within %v announced again", driveRestartGap)
	}
	fix(false)
	s.d.Members[sam].DriveEndedAt = time.Now().Add(-time.Hour).UnixMilli()
	fix(true)
	if starts() != 2 {
		t.Fatalf("a new drive later wasn't announced: %d", starts())
	}
}
