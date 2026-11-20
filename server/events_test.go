package main

import (
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func newStoreWithMembers(t *testing.T, names ...string) (*Store, []*Member) {
	t.Helper()
	s, err := OpenStore(filepath.Join(t.TempDir(), "thereabouts.json"))
	if err != nil {
		t.Fatal(err)
	}
	s.EnsureDefaultCircle("Family") // as the server does at start-up
	var ms []*Member
	for _, n := range names {
		inv, _ := s.CreateInvite(n, "", time.Hour)
		m, _, err := s.Join(inv.Code, "", "", "")
		if err != nil {
			t.Fatal(err)
		}
		ms = append(ms, m)
	}
	return s, ms
}

// report sends a fix with the given battery level; each call is newer than the last.
func report(t *testing.T, s *Store, id string, battery int, charging bool) {
	t.Helper()
	tick++
	if err := s.UpdateLocation(id, Location{Lat: 1, Lon: 1, Battery: battery, Charging: charging, Time: tick}); err != nil {
		t.Fatal(err)
	}
}

var tick int64 = 1000

// levelsSeen returns the low-battery levels the viewer can see, e.g. "[15 10]".
func levelsSeen(s *Store, viewer string, since int64) string {
	evs, _ := s.EventsFor(viewer, since)
	var lv []int
	for _, e := range evs {
		if e.Type == EventBatteryLow {
			lv = append(lv, e.Level)
		}
	}
	return fmt.Sprint(lv)
}

func TestLowBatteryAlerts(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Alex", "Bob")
	alex, bob := ms[0].ID, ms[1].ID
	_, start := s.EventsFor(bob, -1) // a fresh phone gets just a cursor

	steps := []struct {
		battery  int
		charging bool
		want     string // all low-battery levels Bob has seen since the start
	}{
		{80, false, "[]"},
		{16, false, "[]"},
		{15, false, "[15]"},         // crossed 15%
		{14, false, "[15]"},         // no repeat
		{12, true, "[15]"},          // charging: nothing
		{11, false, "[15]"},         // still the same discharge
		{9, false, "[15 10]"},       // crossed 10%
		{5, false, "[15 10]"},       // no repeat
		{18, true, "[15 10]"},       // charging, not yet re-armed
		{14, false, "[15 10]"},      // unplugged at 14 without passing 20%: same cycle
		{60, true, "[15 10]"},       // charged past 20%: re-armed
		{8, false, "[15 10 15 10]"}, // new discharge straight to 8%: both levels at once
	}
	for i, st := range steps {
		report(t, s, alex, st.battery, st.charging)
		if got := levelsSeen(s, bob, start); got != st.want {
			t.Fatalf("step %d (battery %d, charging %v): Bob saw %s, want %s", i, st.battery, st.charging, got, st.want)
		}
	}

	// Alex never sees their own alerts.
	if got := levelsSeen(s, alex, start); got != "[]" {
		t.Fatalf("Alex saw own alerts: %s", got)
	}

	// The cursor moves on: asking again with the latest cursor returns nothing new.
	_, cur := s.EventsFor(bob, start)
	if got := levelsSeen(s, bob, cur); got != "[]" {
		t.Fatalf("events repeated after cursor: %s", got)
	}
}

func TestLowBatteryRespectsSwitchAndPause(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Alex", "Bob")
	alex, bob := ms[0].ID, ms[1].ID
	_, start := s.EventsFor(bob, -1)

	off := false
	s.UpdateMember(alex, MemberPatch{ShareLowBattery: &off})
	report(t, s, alex, 50, false)
	report(t, s, alex, 5, false)
	if got := levelsSeen(s, bob, start); got != "[]" {
		t.Fatalf("alert sent although Alex switched it off: %s", got)
	}

	// Paused people send no locations, so no alerts either.
	on, paused := true, true
	s.UpdateMember(alex, MemberPatch{ShareLowBattery: &on})
	report(t, s, alex, 50, false)
	s.UpdateMember(alex, MemberPatch{Paused: &paused})
	report(t, s, alex, 5, false)
	if got := levelsSeen(s, bob, start); got != "[]" {
		t.Fatalf("alert sent while paused: %s", got)
	}
}

func TestEventsSurviveRestartAndResetCursor(t *testing.T) {
	path := filepath.Join(t.TempDir(), "thereabouts.json")
	s, _ := OpenStore(path)
	s.EnsureDefaultCircle("Family")
	inv, _ := s.CreateInvite("Alex", "", time.Hour)
	alex, _, _ := s.Join(inv.Code, "", "", "")
	inv2, _ := s.CreateInvite("Bob", "", time.Hour)
	bob, _, _ := s.Join(inv2.Code, "", "", "")
	report(t, s, alex.ID, 50, false)
	report(t, s, alex.ID, 12, false)

	s2, _ := OpenStore(path)
	evs, cur := s2.EventsFor(bob.ID, -1) // no cursor yet: no backlog, just the cursor
	if len(evs) != 0 || cur != 1 {
		t.Fatalf("fresh phone: got %d events, cursor %d; want 0 events, cursor 1", len(evs), cur)
	}
	if evs, _ := s2.EventsFor(bob.ID, 0); len(evs) != 1 {
		t.Fatalf("cursor 0 means \"seen nothing yet\": want the 1 event, got %d", len(evs))
	}
	// A phone whose cursor is ahead (server data was reset) starts over.
	if evs, cur := s2.EventsFor(bob.ID, 99); len(evs) != 0 || cur != 1 {
		t.Fatalf("cursor ahead: got %d events, cursor %d", len(evs), cur)
	}
}

func TestEventsArePruned(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Alex")
	s.mu.Lock()
	s.addEventLocked(Event{Type: "old", MemberID: ms[0].ID, Time: time.Now().Add(-72 * time.Hour).UnixMilli()})
	for i := 0; i < maxEvents+20; i++ {
		s.addEventLocked(Event{Type: "x", MemberID: ms[0].ID})
	}
	n := len(s.d.Events)
	oldest := s.d.Events[0].Type
	s.mu.Unlock()
	if n != maxEvents || oldest == "old" {
		t.Fatalf("got %d events (oldest %q), want %d and no expired ones", n, oldest, maxEvents)
	}
}

// Regression: on a brand-new server the first cursor is 0, and the very first
// alert must still reach phones holding that cursor.
func TestFirstEverAlertIsDelivered(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Alex", "Bob")
	_, cur := s.EventsFor(ms[1].ID, -1)
	if cur != 0 {
		t.Fatalf("new server cursor = %d, want 0", cur)
	}
	report(t, s, ms[0].ID, 50, false)
	report(t, s, ms[0].ID, 14, false)
	if got := levelsSeen(s, ms[1].ID, cur); got != "[15]" {
		t.Fatalf("first alert lost: Bob saw %s", got)
	}
}
