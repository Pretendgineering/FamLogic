package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCleanEmoji(t *testing.T) {
	ok := []string{"", "🐶", "👩🏽‍🚀", "🇩🇪", "❤️", " 🦊 "}
	bad := []string{"A", "AB", "hi 🐶", "?!", "🐶🐶🐶🐶🐶🐶🐶🐶🐶🐶🐶", "🐶 x"}
	for _, e := range ok {
		if _, valid := cleanEmoji(e); !valid {
			t.Errorf("%q rejected", e)
		}
	}
	for _, e := range bad {
		if _, valid := cleanEmoji(e); valid {
			t.Errorf("%q accepted", e)
		}
	}
}

func TestSilenceAlert(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom", "Paused")
	sam, mom, paused := ms[0].ID, ms[1].ID, ms[2].ID
	report(t, s, sam, 80, false)
	report(t, s, paused, 80, false)
	s.UpdateMember(paused, MemberPatch{Paused: ptr(true)})
	_, cur := s.EventsFor(mom, -1)
	s.Touch(mom)
	s.startContactGrace()

	silentFor := func() []string {
		evs, _ := s.EventsFor(mom, cur)
		var out []string
		for _, e := range evs {
			if e.Type == EventSilent {
				out = append(out, e.Name)
			}
		}
		return out
	}
	s.CheckSilence(time.Hour)
	if got := silentFor(); len(got) != 0 {
		t.Fatalf("alert right after start: %v", got)
	}
	// Seven hours without contact (and the server has been watching that long).
	s.mu.Lock()
	s.watchStart = time.Now().Add(-8 * time.Hour).UnixMilli()
	for _, m := range s.d.Members {
		m.LastContact = time.Now().Add(-7 * time.Hour).UnixMilli()
	}
	s.mu.Unlock()
	s.CheckSilence(6 * time.Hour)
	s.CheckSilence(6 * time.Hour) // only once
	if got := silentFor(); len(got) != 1 || got[0] != "Sam" {
		t.Fatalf("silence alerts: %v (want just Sam: Mom never shared, the paused one is excused)", got)
	}
	// Back in touch, then quiet again: a new alert.
	s.Touch(sam)
	if s.d.Members[sam].SilentSince != 0 {
		t.Fatal("contact didn't end the silence")
	}
	s.d.Members[sam].LastContact = time.Now().Add(-7 * time.Hour).UnixMilli()
	s.CheckSilence(6 * time.Hour)
	if got := silentFor(); len(got) != 2 {
		t.Fatalf("second silence not announced: %v", got)
	}
}

func ptr[T any](v T) *T { return &v }

func TestScheduleActive(t *testing.T) {
	berlin, _ := time.LoadLocation("Europe/Berlin")
	at := func(day, hm string) time.Time { // day: 2026-11-16 is a Monday
		tm, _ := time.ParseInLocation("2006-01-02 15:04", day+" "+hm, berlin)
		return tm
	}
	weekdays := Schedule{Days: []int{1, 2, 3, 4, 5}, From: "07:00", To: "17:00", TZ: "Europe/Berlin"}
	night := Schedule{Days: []int{5}, From: "22:00", To: "06:00", TZ: "Europe/Berlin"} // Friday night
	allDay := Schedule{Days: []int{0}, From: "00:00", To: "00:00", TZ: "Europe/Berlin"}
	cases := []struct {
		sc   Schedule
		t    time.Time
		want bool
	}{
		{weekdays, at("2026-11-16", "08:00"), true},
		{weekdays, at("2026-11-16", "06:59"), false},
		{weekdays, at("2026-11-16", "17:00"), false},
		{weekdays, at("2026-11-21", "10:00"), false}, // Saturday
		{weekdays, at("2026-11-16", "08:00").UTC(), true},
		{night, at("2026-11-20", "23:00"), true},  // Friday 23:00
		{night, at("2026-11-21", "05:00"), true},  // Saturday 05:00, still Friday night
		{night, at("2026-11-21", "23:00"), false}, // Saturday night isn't chosen
		{allDay, at("2026-11-22", "13:00"), true},
		{allDay, at("2026-11-23", "13:00"), false},
	}
	for i, c := range cases {
		if got := c.sc.Active(c.t); got != c.want {
			t.Errorf("case %d: got %v, want %v", i, got, c.want)
		}
	}
	if (&Schedule{Days: []int{1}, From: "7", To: "17:00", TZ: "Europe/Berlin"}).valid() ||
		(&Schedule{Days: []int{}, From: "07:00", To: "17:00", TZ: "Europe/Berlin"}).valid() ||
		(&Schedule{Days: []int{1}, From: "07:00", To: "17:00", TZ: "Mars/Olympus"}).valid() {
		t.Fatal("bad schedule accepted")
	}
}

func TestScheduledFenceIsQuietOutsideHours(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	family := s.Circles()[0].ID
	at(t, s, sam, 2000, 20)
	// A schedule that is never on right now: yesterday only.
	yesterday := int(time.Now().UTC().Add(-24 * time.Hour).Weekday())
	p, err := s.CreatePlace(mom, Place{CircleID: family, Name: "School", Lat: homeLat, Lon: homeLon, Radius: 100,
		Schedule: &Schedule{Days: []int{yesterday}, From: "00:00", To: "00:00", TZ: "UTC"}})
	if err != nil {
		t.Fatal(err)
	}
	_, cur := s.EventsFor(mom, -1)
	at(t, s, sam, 20, 10)
	if got := placeEvents(s, mom, cur); got != "" {
		t.Fatalf("alert outside the schedule: %q", got)
	}
	// Still tracked: Mom sees Sam at School.
	for _, v := range mustViews(s, mom) {
		if v.Name == "Sam" && v.Place != "School" {
			t.Fatalf("presence not tracked: %q", v.Place)
		}
	}
	// Clearing the schedule turns alerts back on.
	if _, err := s.UpdatePlace(mom, p.ID, PlaceUpdate{SetSchedule: true}); err != nil {
		t.Fatal(err)
	}
	at(t, s, sam, 3000, 10)
	if got := placeEvents(s, mom, cur); got != "Sam left School" {
		t.Fatalf("after clearing the schedule: %q", got)
	}
}

func TestDailyBackups(t *testing.T) {
	s, _ := newStoreWithMembers(t, "Sam")
	dir := filepath.Join(t.TempDir(), "backups")
	os.MkdirAll(dir, 0o700)
	for _, d := range []string{"2026-01-01", "2026-01-02", "2026-01-03"} {
		os.WriteFile(filepath.Join(dir, "thereabouts-"+d+".json"), []byte("{}"), 0o600)
	}
	if err := s.DailyBackup(dir, 2); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 2 || entries[1].Name() != "thereabouts-"+time.Now().Format("2006-01-02")+".json" {
		t.Fatalf("backups: %v", entries)
	}
	b, _ := os.ReadFile(filepath.Join(dir, entries[1].Name()))
	if len(b) < 50 {
		t.Fatalf("backup looks empty: %s", b)
	}
}

func TestHealthEmojiScheduleOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Alice"})
	_, joined := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
	tok := joined["token"].(string)

	if r, _ := e.do("POST", "/api/me/status", tok, map[string]any{"app": "0.9.0", "issues": []string{"battery", "bogus"}}); r.StatusCode != 204 {
		t.Fatalf("status: %d", r.StatusCode)
	}
	if r, _ := e.do("POST", "/api/me", tok, map[string]any{"emoji": "Bob"}); r.StatusCode != 400 {
		t.Fatalf("text accepted as emoji: %d", r.StatusCode)
	}
	if r, m := e.do("POST", "/api/me", tok, map[string]any{"emoji": "🦊"}); r.StatusCode != 200 || m["emoji"] != "🦊" {
		t.Fatalf("emoji: %d %v", r.StatusCode, m)
	}
	_, data := e.do("GET", "/api/admin/members", "", nil)
	alice := data["members"].([]any)[0].(map[string]any)
	if alice["app"] != "0.9.0" || len(alice["issues"].([]any)) != 1 || alice["emoji"] != "🦊" || alice["lastContact"] == nil {
		t.Fatalf("admin view: %v", alice)
	}
	// The admin can clear it.
	if r, m := e.do("PATCH", "/api/admin/members/"+alice["id"].(string), "", map[string]any{"emoji": ""}); r.StatusCode != 200 || m["emoji"] != nil {
		t.Fatalf("clear emoji: %d %v", r.StatusCode, m)
	}

	// Fences with a schedule.
	circle := data["circles"].([]any)[0].(map[string]any)["id"]
	sched := map[string]any{"days": []int{1, 2, 3, 4, 5}, "from": "07:00", "to": "17:00", "tz": "America/New_York"}
	r, p := e.do("POST", "/api/places", tok, map[string]any{"circleId": circle, "name": "School", "lat": 1, "lon": 2, "radius": 100, "schedule": sched})
	if r.StatusCode != 200 || p["schedule"] == nil {
		t.Fatalf("create with schedule: %d %v", r.StatusCode, p)
	}
	id := p["id"].(string)
	if r, p := e.do("POST", "/api/places/"+id, tok, map[string]any{"name": "School 2"}); r.StatusCode != 200 || p["schedule"] == nil {
		t.Fatalf("rename dropped the schedule: %d %v", r.StatusCode, p)
	}
	if r, _ := e.do("POST", "/api/places/"+id, tok, map[string]any{"schedule": map[string]any{"days": []int{9}, "from": "x", "to": "y", "tz": "?"}}); r.StatusCode != 400 {
		t.Fatalf("bad schedule: %d", r.StatusCode)
	}
	if r, p := e.do("POST", "/api/places/"+id, tok, map[string]any{"schedule": nil}); r.StatusCode != 200 || p["schedule"] != nil {
		t.Fatalf("clear schedule: %d %v", r.StatusCode, p)
	}

	// Backup download.
	resp, _ := e.do("GET", "/api/admin/backup", "", nil)
	if resp.StatusCode != 200 || resp.Header.Get("Content-Disposition") == "" {
		t.Fatalf("backup: %d", resp.StatusCode)
	}
}
