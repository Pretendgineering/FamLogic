package main

import (
	"testing"
	"time"
)

// fixAt reports a fix `meters` north of home, `ago` in the past.
func fixAt(t *testing.T, s *Store, id string, meters float64, ago time.Duration) {
	t.Helper()
	lat, lon := north(homeLat, homeLon, meters)
	if err := s.UpdateLocation(id, Location{Lat: lat, Lon: lon, Accuracy: 15, Battery: 80, Time: time.Now().Add(-ago).UnixMilli()}); err != nil {
		t.Fatal(err)
	}
}

func trailLen(t *testing.T, s *Store, viewer, id string) int {
	t.Helper()
	pts, ok := s.TrailFor(viewer, id)
	if !ok {
		return -1
	}
	return len(pts)
}

func TestTrails(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom", "Stranger")
	sam, mom, stranger := ms[0].ID, ms[1].ID, ms[2].ID
	family := s.Circles()[0].ID
	other, _ := s.CreateCircle("Other")
	s.SetMembership(stranger, family, false)
	s.SetMembership(stranger, other.ID, true)

	// Off by default: nothing is kept.
	fixAt(t, s, sam, 0, 50*time.Minute)
	fixAt(t, s, sam, 500, 40*time.Minute)
	if n := trailLen(t, s, mom, sam); n != 0 {
		t.Fatalf("trail kept while off: %d points", n)
	}

	// On for 6 hours. Turning it on starts the trail where Sam is.
	if err := s.SetTrailHours(sam, 6); err != nil {
		t.Fatal(err)
	}
	fixAt(t, s, sam, 510, 39*time.Minute) // 10 m and 1 min later: not worth a point
	fixAt(t, s, sam, 800, 30*time.Minute) // moved 300 m: kept
	fixAt(t, s, sam, 805, 15*time.Minute) // barely moved, but 15 minutes later: kept
	fixAt(t, s, sam, 806, 14*time.Minute) // nothing new
	if n := trailLen(t, s, mom, sam); n != 3 {
		t.Fatalf("trail has %d points, want 3", n)
	}

	// Only people who can see Sam's location see the trail.
	if n := trailLen(t, s, stranger, sam); n != -1 {
		t.Fatalf("stranger sees a trail of %d points", n)
	}
	s.SetSharingIn(sam, family, false)
	if n := trailLen(t, s, mom, sam); n != -1 {
		t.Fatal("trail visible to a circle Sam hid from")
	}
	s.SetSharingIn(sam, family, true)
	paused, resumed := true, false
	s.UpdateMember(sam, MemberPatch{Paused: &paused})
	if n := trailLen(t, s, mom, sam); n > 0 {
		t.Fatal("trail visible while paused")
	}
	s.UpdateMember(sam, MemberPatch{Paused: &resumed})

	// Old points expire with the chosen length.
	s.mu.Lock()
	s.d.Trails[sam] = append([]TrailPoint{{Lat: 1, Lon: 1, Time: time.Now().Add(-7 * time.Hour).UnixMilli()}}, s.d.Trails[sam]...)
	s.mu.Unlock()
	if n := trailLen(t, s, mom, sam); n != 3 {
		t.Fatalf("expired point shown: %d", n)
	}
	fixAt(t, s, sam, 2000, 0)
	s.mu.RLock()
	stored := len(s.d.Trails[sam])
	s.mu.RUnlock()
	if stored != 4 {
		t.Fatalf("expired point not pruned: %d stored", stored)
	}

	// Turning it off deletes it.
	s.SetTrailHours(sam, 0)
	s.mu.RLock()
	_, kept := s.d.Trails[sam]
	s.mu.RUnlock()
	if kept || trailLen(t, s, mom, sam) != 0 {
		t.Fatal("trail not deleted when turned off")
	}
}

func TestTrailsOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	join := func(name string) (string, string) {
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name})
		_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
		return j["token"].(string), j["member"].(map[string]any)["id"].(string)
	}
	sam, samID := join("Sam")
	mom, _ := join("Mom")
	if st, _ := e.status("POST", "/api/me", sam, map[string]any{"trailHours": 5}); st != 400 {
		t.Fatalf("odd trail length accepted: %d", st)
	}
	e.do("POST", "/api/me", sam, map[string]any{"trailHours": 12})
	e.do("POST", "/api/location", sam, map[string]any{"lat": 40, "lon": -75, "acc": 10, "time": time.Now().UnixMilli()})
	_, list := e.do("GET", "/api/members", mom, nil)
	for _, m := range list["members"].([]any) {
		mm := m.(map[string]any)
		if mm["name"] == "Sam" && mm["trail"] != true {
			t.Fatalf("Sam's trail flag missing: %v", mm)
		}
	}
	if _, me := e.do("GET", "/api/members", sam, nil); me["settings"].(map[string]any)["trailHours"] != float64(12) {
		t.Fatalf("trailHours not saved: %v", me["settings"])
	}
	_, tr := e.do("GET", "/api/members/"+samID+"/trail", mom, nil)
	if len(tr["points"].([]any)) != 1 {
		t.Fatalf("trail: %v", tr)
	}
	_, atr := e.do("GET", "/api/admin/members/"+samID+"/trail", "", nil)
	if len(atr["points"].([]any)) != 1 {
		t.Fatalf("admin trail: %v", atr)
	}
}
