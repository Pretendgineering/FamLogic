package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCalendarFeeds(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	day := time.Now().AddDate(0, 0, 2).Format("20060102")
	ics := fmt.Sprintf("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//School//EN\r\n"+
		"BEGIN:VEVENT\r\nUID:term@school\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:%s\r\nSUMMARY:Inset day\r\nEND:VEVENT\r\n"+
		"BEGIN:VEVENT\r\nUID:club@school\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:%sT160000\r\nDTEND:%sT170000\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:Chess club\r\nEND:VEVENT\r\n"+
		"END:VCALENDAR\r\n", day, day, day)
	src := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/bad" {
			fmt.Fprint(w, "<html>not a calendar</html>")
			return
		}
		w.Header().Set("Content-Type", "text/calendar")
		fmt.Fprint(w, ics)
	}))
	defer src.Close()
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "calendar": true}, "setupDone": true})

	if st, _ := e.status("POST", "/api/calendar/feeds", f.teen, map[string]any{"name": "School", "url": src.URL}); st != 403 {
		t.Fatalf("children don't add calendars: %d", st)
	}
	if st, _ := e.status("POST", "/api/calendar/feeds", f.mom, map[string]any{"name": "School", "url": src.URL + "/bad"}); st != 400 {
		t.Fatalf("not a calendar: %d", st)
	}
	st, feed := e.status("POST", "/api/calendar/feeds", f.mom, map[string]any{"name": "School", "url": src.URL, "color": "#ff8800"})
	if st != 200 || feed["events"].(float64) != 2 {
		t.Fatalf("add: %d %v", st, feed)
	}
	// Everyone sees its events, read-only, marked with the calendar's name.
	from := time.Now().Truncate(24 * time.Hour).UnixMilli()
	_, ev := e.status("GET", fmt.Sprintf("/api/events?from=%d&to=%d", from, from+40*86400000), f.kid, nil)
	n := 0
	for _, x := range ev["events"].([]any) {
		if x.(map[string]any)["calendar"] == "School" {
			n++
		}
	}
	if n != 5 { // the inset day and four chess clubs
		t.Fatalf("feed events: %d in %v", n, ev["events"])
	}
	if st, _ := e.status("DELETE", "/api/calendar/feeds/"+feed["id"].(string), f.mom, nil); st != 204 {
		t.Fatalf("remove: %d", st)
	}
	_, ev = e.status("GET", fmt.Sprintf("/api/events?from=%d&to=%d", from, from+40*86400000), f.kid, nil)
	if len(ev["events"].([]any)) != 0 {
		t.Fatalf("gone: %v", ev["events"])
	}
}

func TestProfiles(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "profiles": true}, "setupDone": true})
	_, res := e.status("GET", "/api/profiles", f.kid, nil)
	if len(res["people"].([]any)) < 4 {
		t.Fatalf("everyone sees profiles: %v", res)
	}
	// A parent changes a child's; the younger child can't; the teen only their own.
	if st, _ := e.status("PUT", "/api/profiles/"+f.kidID, f.mom, map[string]any{"profile": map[string]string{"Allergies": "- Peanuts\n- Penicillin", "Birthday": "2019-04-02"}}); st != 200 {
		t.Fatalf("parent edit: %d", st)
	}
	if st, _ := e.status("PUT", "/api/profiles/"+f.kidID, f.kid, map[string]any{"profile": map[string]string{"Likes": "cake"}}); st != 403 {
		t.Fatalf("younger child edits: %d", st)
	}
	if st, _ := e.status("PUT", "/api/profiles/"+f.kidID, f.teen, map[string]any{"profile": map[string]string{"Likes": "cake"}}); st != 403 {
		t.Fatalf("teen edits sibling: %d", st)
	}
	if st, _ := e.status("PUT", "/api/profiles/"+f.teenID, f.teen, map[string]any{"profile": map[string]string{"Likes": "- Chess"}}); st != 200 {
		t.Fatalf("teen own: %d", st)
	}
	if st, _ := e.status("PUT", "/api/profiles/"+f.teenID, f.mom, map[string]any{"profile": map[string]string{"Favourite colour": "x"}}); st != 400 {
		t.Fatalf("unknown section: %d", st)
	}
	_, res = e.status("GET", "/api/profiles", f.dad, nil)
	for _, p := range res["people"].([]any) {
		pm := p.(map[string]any)
		if pm["id"] == f.kidID {
			prof := pm["profile"].(map[string]any)
			if prof["Allergies"] != "- Peanuts\n- Penicillin" || prof["Birthday"] != "2019-04-02" {
				t.Fatalf("kid's profile: %v", prof)
			}
		}
	}
	// The note keeps FamLogic's fields and the profile's sections, and the vault sync leaves them be.
	path, _ := filepath.Glob(filepath.Join(filepath.Dir(e.path), "vault", "People", "Kid*.md"))
	b, _ := os.ReadFile(path[0])
	if !strings.Contains(string(b), "famlogic_id:") || !strings.Contains(string(b), "## Allergies\n- Peanuts") {
		t.Fatalf("note: %s", b)
	}

	// Pets and contacts: notes of their own, which the younger child can't change.
	st, pet := e.status("POST", "/api/profiles/cards", f.teen, map[string]any{"kind": "pet", "name": "Biscuit", "emoji": "🐕", "profile": map[string]string{"Food": "Twice a day"}})
	if st != 200 || pet["id"] == nil {
		t.Fatalf("add pet: %d %v", st, pet)
	}
	if st, _ := e.status("POST", "/api/profiles/cards", f.kid, map[string]any{"kind": "contact", "name": "Gran"}); st != 403 {
		t.Fatalf("younger child adds: %d", st)
	}
	id := pet["id"].(string)
	if st, out := e.status("PUT", "/api/profiles/cards/"+id, f.mom, map[string]any{"name": "Biscuit the Dog", "profile": map[string]string{"Vet": "Dr Paws"}}); st != 200 || out["name"] != "Biscuit the Dog" {
		t.Fatalf("edit pet: %d %v", st, out)
	}
	_, res = e.status("GET", "/api/profiles", f.kid, nil)
	pets := res["pets"].([]any)
	if len(pets) != 1 {
		t.Fatalf("pets: %v", pets)
	}
	pp := pets[0].(map[string]any)["profile"].(map[string]any)
	if pp["Food"] != "Twice a day" || pp["Vet"] != "Dr Paws" {
		t.Fatalf("pet profile: %v", pp)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(e.path), "vault", "Pets", "Biscuit the Dog.md")); err != nil {
		t.Fatal("renamed note missing")
	}
	if st, _ := e.status("DELETE", "/api/profiles/cards/"+id, f.mom, nil); st != 204 {
		t.Fatalf("delete: %d", st)
	}
}
