package main

import (
	"context"
	"fmt"
	"testing"
	"time"
)

func TestWaitForEventsWakesUp(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Alex", "Bob")
	alex, bob := ms[0].ID, ms[1].ID
	_, cur := s.EventsFor(bob, -1)

	// Bob's phone waits; Alex presses SOS half a second later.
	go func() {
		time.Sleep(500 * time.Millisecond)
		s.StartSOS(alex, &Location{Lat: 1, Lon: 2, Time: time.Now().UnixMilli()})
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	start := time.Now()
	evs, next := s.WaitForEvents(ctx, bob, cur)
	if len(evs) != 1 || evs[0].Type != EventSOS || evs[0].Lat != 1 {
		t.Fatalf("got %+v", evs)
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Fatalf("took %v to deliver the SOS", d)
	}
	if next <= cur {
		t.Fatalf("cursor didn't move: %d -> %d", cur, next)
	}

	// Nothing new: it waits until the deadline and returns empty.
	ctx2, cancel2 := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel2()
	start = time.Now()
	if evs, _ := s.WaitForEvents(ctx2, bob, next); len(evs) != 0 || time.Since(start) < 250*time.Millisecond {
		t.Fatalf("expected an empty wait, got %v after %v", evs, time.Since(start))
	}

	// Alex's own SOS doesn't wake Alex's phone.
	ctx3, cancel3 := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel3()
	_, acur := s.EventsFor(alex, -1)
	go s.CheckIn(alex, "hi", nil)
	if evs, _ := s.WaitForEvents(ctx3, alex, acur); len(evs) != 0 {
		t.Fatalf("own event delivered: %v", evs)
	}
}

func TestSOSAndCheckIn(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Alex", "Bob")
	alex, bob := ms[0].ID, ms[1].ID
	_, cur := s.EventsFor(bob, -1)

	// SOS while paused un-pauses: someone asking for help wants to be found.
	paused := true
	s.UpdateMember(alex, MemberPatch{Paused: &paused})
	m, _ := s.StartSOS(alex, &Location{Lat: 5, Lon: 6, Time: time.Now().UnixMilli()})
	if m.Paused || m.SOSSince == 0 || m.Loc == nil || m.Loc.Lat != 5 || m.Loc.Battery != -1 {
		t.Fatalf("after SOS: %+v (battery should be unknown, not 0)", m)
	}
	since := m.SOSSince
	m, _ = s.StartSOS(alex, nil) // pressed again: alert again, same start time
	if m.SOSSince != since {
		t.Fatal("repeat SOS changed the start time")
	}
	m, _ = s.EndSOS(alex)
	if m.SOSSince != 0 {
		t.Fatal("SOS not cleared")
	}
	s.EndSOS(alex) // ending twice doesn't send a second "OK"

	// Check-in while paused: the location goes in the alert, not on the map.
	s.UpdateMember(alex, MemberPatch{Paused: &paused})
	s.CheckIn(alex, "  Made   it home  ", &Location{Lat: 9, Lon: 9, Time: time.Now().UnixMilli()})
	for _, mm := range s.Members() {
		if mm.ID == alex && mm.Loc != nil {
			t.Fatal("check-in revealed a paused member's location on the map")
		}
	}

	evs, _ := s.EventsFor(bob, cur)
	got := ""
	for _, e := range evs {
		got += fmt.Sprintf("%s(%s,%v) ", e.Type, e.Note, e.Lat)
	}
	want := "sos(,5) sos(,5) sos_end(,0) checkin(Made it home,9) "
	if got != want {
		t.Fatalf("events:\n got %s\nwant %s", got, want)
	}
}

func TestSOSOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	join := func(name string) string {
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name})
		_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
		return j["token"].(string)
	}
	alex, bob := join("Alex"), join("Bob")
	_, first := e.do("GET", "/api/events/wait?since=-1", bob, nil) // no cursor yet: answers at once
	cur := int64(first["cursor"].(float64))

	// Bob's phone is waiting when Alex presses SOS.
	got := make(chan map[string]any, 1)
	go func() {
		_, r := e.do("GET", fmt.Sprintf("/api/events/wait?since=%d&wait=20", cur), bob, nil)
		got <- r
	}()
	time.Sleep(300 * time.Millisecond)
	if st, out := e.status("POST", "/api/sos", alex, map[string]any{"loc": map[string]any{"lat": 51.5, "lon": -0.1, "acc": 8}}); st != 200 || out["sosSince"] == nil {
		t.Fatalf("sos: %d %v", st, out)
	}
	select {
	case r := <-got:
		ev := r["events"].([]any)[0].(map[string]any)
		if ev["type"] != "sos" || ev["name"] != "Alex" || ev["lat"] != 51.5 {
			t.Fatalf("unexpected alert %v", ev)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("SOS didn't arrive on the waiting connection")
	}

	// Everyone's map shows the SOS until Alex says they're OK.
	_, list := e.do("GET", "/api/members", bob, nil)
	for _, m := range list["members"].([]any) {
		mm := m.(map[string]any)
		if mm["name"] == "Alex" && mm["sosSince"] == nil {
			t.Fatal("SOS not visible on the map")
		}
	}
	e.do("POST", "/api/sos/end", alex, nil)
	if st, _ := e.status("POST", "/api/checkin", alex, map[string]any{"note": "home"}); st != 204 {
		t.Fatalf("checkin: %d", st)
	}
	// A short wait with nothing new returns empty, quickly.
	_, r := e.do("GET", "/api/events/wait?since=999999&wait=1", bob, nil)
	if len(r["events"].([]any)) != 0 {
		t.Fatalf("expected nothing: %v", r)
	}
}
