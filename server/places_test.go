package main

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// A point `meters` north of (lat, lon).
func north(lat, lon, meters float64) (float64, float64) { return lat + meters/111_320, lon }

const homeLat, homeLon = 40.0, -75.0

// at reports a fix `meters` north of home with the given accuracy.
func at(t *testing.T, s *Store, id string, meters, acc float64) {
	t.Helper()
	tick++
	lat, lon := north(homeLat, homeLon, meters)
	if err := s.UpdateLocation(id, Location{Lat: lat, Lon: lon, Accuracy: acc, Battery: 80, Time: tick}); err != nil {
		t.Fatal(err)
	}
}

func placeEvents(s *Store, viewer string, since int64) string {
	evs, _ := s.EventsFor(viewer, since)
	var out []string
	for _, e := range evs {
		if e.Type == EventArrived || e.Type == EventLeft {
			out = append(out, fmt.Sprintf("%s %s %s", e.Name, e.Type, e.Place))
		}
	}
	return strings.Join(out, ", ")
}

// backdate pretends the member's first "outside" fix happened `d` ago.
func backdate(s *Store, id string, d time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, pr := range s.d.Members[id].Presence {
		if pr.OutsideSince != 0 {
			pr.OutsideSince = time.Now().Add(-d).UnixMilli()
		}
	}
}

func TestGeofenceArriveAndLeave(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	family := s.Circles()[0].ID
	_, cur := s.EventsFor(mom, -1)

	at(t, s, sam, 2000, 20) // far away when the place is made
	if _, err := s.CreatePlace(mom, Place{CircleID: family, Name: "Home", Lat: homeLat, Lon: homeLon, Radius: 100}); err != nil {
		t.Fatal(err)
	}
	steps := []struct {
		meters, acc float64
		backdate    time.Duration
		want        string
	}{
		{1500, 20, 0, ""},
		{50, 20, 0, "Sam arrived Home"},
		{80, 20, 0, "Sam arrived Home"},                               // still inside: nothing new
		{120, 20, 0, "Sam arrived Home"},                              // in the buffer zone: not "left"
		{60, 500, 0, "Sam arrived Home"},                              // too inaccurate to count
		{160, 30, 0, "Sam arrived Home"},                              // just outside: wait for another fix...
		{70, 20, 0, "Sam arrived Home"},                               // ...and it was a blip
		{170, 30, 0, "Sam arrived Home"},                              // outside again...
		{180, 30, 2 * time.Minute, "Sam arrived Home, Sam left Home"}, // ...and still, a while later
		{400, 20, 0, "Sam arrived Home, Sam left Home"},               // no repeat
		{40, 15, 0, "Sam arrived Home, Sam left Home, Sam arrived Home"},
		{3000, 20, 0, "Sam arrived Home, Sam left Home, Sam arrived Home, Sam left Home"}, // clearly gone: at once
	}
	for i, st := range steps {
		if st.backdate > 0 {
			backdate(s, sam, st.backdate)
		}
		at(t, s, sam, st.meters, st.acc)
		if got := placeEvents(s, mom, cur); got != st.want {
			t.Fatalf("step %d (%v m, ±%v): got %q, want %q", i, st.meters, st.acc, got, st.want)
		}
	}
}

func TestGeofencePrivacyAndSwitches(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom", "Uncle")
	sam, mom, uncle := ms[0].ID, ms[1].ID, ms[2].ID
	family := s.Circles()[0].ID
	friends, _ := s.CreateCircle("Friends")
	s.SetMembership(uncle, family, false)
	s.SetMembership(uncle, friends.ID, true)
	s.SetMembership(sam, friends.ID, true)

	at(t, s, sam, 40, 20) // already home when the place is made: no "arrived"
	s.CreatePlace(mom, Place{CircleID: family, Name: "Home", Lat: homeLat, Lon: homeLon, Radius: 100})
	_, mcur := s.EventsFor(mom, -1)
	_, ucur := s.EventsFor(uncle, -1)
	at(t, s, sam, 40, 20)
	if got := placeEvents(s, mom, mcur); got != "" {
		t.Fatalf("making a place announced %q", got)
	}

	// Mom sees "At Home"; Uncle isn't in the family circle, so he doesn't.
	for _, v := range mustViews(s, mom) {
		if v.Name == "Sam" && v.Place != "Home" {
			t.Fatalf("Mom sees Sam at %q", v.Place)
		}
	}
	for _, v := range mustViews(s, uncle) {
		if v.Name == "Sam" && v.Place != "" {
			t.Fatalf("Uncle sees Sam at %q", v.Place)
		}
	}

	// Leaving: Mom is told, Uncle (different circle) is not.
	at(t, s, sam, 3000, 20)
	if got := placeEvents(s, mom, mcur); got != "Sam left Home" {
		t.Fatalf("Mom got %q", got)
	}
	if got := placeEvents(s, uncle, ucur); got != "" {
		t.Fatalf("Uncle got %q", got)
	}

	// Sam hides from the family circle: no more place alerts there.
	_, mcur = s.EventsFor(mom, -1)
	s.SetSharingIn(sam, family, false)
	at(t, s, sam, 30, 20)
	if got := placeEvents(s, mom, mcur); got != "" {
		t.Fatalf("alert leaked while hidden: %q", got)
	}
	s.SetSharingIn(sam, family, true)

	// Sam turns "announce arrivals" off.
	off := false
	s.UpdateMember(sam, MemberPatch{ShareArrivals: &off})
	_, mcur = s.EventsFor(mom, -1)
	at(t, s, sam, 3000, 20)
	at(t, s, sam, 30, 20)
	if got := placeEvents(s, mom, mcur); got != "" {
		t.Fatalf("alert sent with announcing off: %q", got)
	}

	// Pausing forgets presence: resuming somewhere else doesn't say "left Home".
	on, paused, resumed := true, true, false
	s.UpdateMember(sam, MemberPatch{ShareArrivals: &on})
	s.UpdateMember(sam, MemberPatch{Paused: &paused})
	s.UpdateMember(sam, MemberPatch{Paused: &resumed})
	_, mcur = s.EventsFor(mom, -1)
	at(t, s, sam, 5000, 20)
	if got := placeEvents(s, mom, mcur); got != "" {
		t.Fatalf("resuming revealed movement: %q", got)
	}
}

func mustViews(s *Store, viewer string) []MemberView {
	v, _ := s.VisibleTo(viewer)
	return v
}

func TestPlacesOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	join := func(name, circle string) (string, map[string]any) {
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name, "circleId": circle})
		_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
		return j["token"].(string), j
	}
	_, adm := e.do("GET", "/api/admin/members", "", nil)
	family := adm["circles"].([]any)[0].(map[string]any)["id"].(string)
	_, c := e.do("POST", "/api/admin/circles", "", map[string]string{"name": "Friends"})
	friends := c["id"].(string)
	mom, _ := join("Mom", family)
	pal, _ := join("Pal", friends)

	st, p := e.status("POST", "/api/places", mom, map[string]any{"circleId": family, "name": " Home ", "lat": homeLat, "lon": homeLon, "radius": 120})
	if st != 200 || p["name"] != "Home" {
		t.Fatalf("create: %d %v", st, p)
	}
	id := p["id"].(string)
	if st, _ := e.status("POST", "/api/places", mom, map[string]any{"circleId": family, "name": "Tiny", "lat": 1, "lon": 1, "radius": 5}); st != 400 {
		t.Fatalf("tiny radius accepted: %d", st)
	}
	// Someone outside the circle can't see, edit or delete it.
	_, list := e.do("GET", "/api/members", pal, nil)
	if len(list["places"].([]any)) != 0 {
		t.Fatal("place visible outside its circle")
	}
	if st, _ := e.status("POST", "/api/places/"+id, pal, map[string]any{"name": "Hacked"}); st != 404 {
		t.Fatalf("edit from outside the circle: %d", st)
	}
	if st, _ := e.status("DELETE", "/api/places/"+id, pal, nil); st != 404 {
		t.Fatalf("delete from outside the circle: %d", st)
	}
	// Members of the circle can edit (POST works for phones that can't PATCH).
	if st, p := e.status("POST", "/api/places/"+id, mom, map[string]any{"radius": 200}); st != 200 || p["radius"] != float64(200) {
		t.Fatalf("resize: %d %v", st, p)
	}
	_, list = e.do("GET", "/api/members", mom, nil)
	if len(list["places"].([]any)) != 1 || list["settings"].(map[string]any)["shareArrivals"] != true {
		t.Fatalf("mom's view: %v / %v", list["places"], list["settings"])
	}
	// The dashboard sees all places and can delete them.
	_, adm2 := e.do("GET", "/api/admin/members", "", nil)
	if len(adm2["places"].([]any)) != 1 {
		t.Fatalf("admin places: %v", adm2["places"])
	}
	if st, _ := e.status("DELETE", "/api/admin/places/"+id, "", nil); st != 204 {
		t.Fatalf("admin delete: %d", st)
	}
}
