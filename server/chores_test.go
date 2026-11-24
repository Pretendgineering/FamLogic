package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestNextDue(t *testing.T) {
	for _, c := range []struct{ every, from, want string }{
		{"day", "2026-11-24", "2026-11-25"},
		{"week", "2026-11-24", "2026-12-01"},
		{"2 weeks", "2026-11-24", "2026-12-08"},
		{"month", "2026-11-24", "2026-12-24"},
		{"weekdays", "2026-11-27", "2026-11-30"}, // Friday → Monday
		{"mon,thu", "2026-11-24", "2026-11-26"},  // Tuesday → Thursday
		{"mon,thu", "2026-11-26", "2026-11-30"},  // Thursday → Monday
	} {
		if got := nextDue(c.every, c.from); got != c.want {
			t.Errorf("nextDue(%q, %s) = %s, want %s", c.every, c.from, got, c.want)
		}
	}
	for _, ok := range []string{"", "day", "weekdays", "3 days", "2 weeks", "sat,sun"} {
		if !validEvery(ok) {
			t.Errorf("%q should be valid", ok)
		}
	}
	for _, bad := range []string{"fortnightly", "0 weeks", "mon,funday"} {
		if validEvery(bad) {
			t.Errorf("%q should be invalid", bad)
		}
	}
}

func TestChores(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	vault := filepath.Join(filepath.Dir(f.e.dataFile()), "vault")

	// A parent asks the kid to unload the dishwasher, every day, for a star.
	st, ch := e.status("POST", "/api/chores", f.mom, map[string]any{"title": "Unload the dishwasher", "who": []string{f.kidID}, "every": "day", "stars": 1, "due": today()})
	if st != 200 {
		t.Fatalf("create: %d %v", st, ch)
	}
	id := ch["id"].(string)
	b, err := os.ReadFile(filepath.Join(vault, "Chores", "Unload the dishwasher.md"))
	if err != nil {
		t.Fatal(err)
	}
	note := string(b)
	for _, want := range []string{"type: chore", "famlogic_id: " + id, `who: ["[[Kid]]"]`, "every: day", "stars: 1", "# Unload the dishwasher", "- [ ] " + today()} {
		if !strings.Contains(note, want) {
			t.Fatalf("note lacks %q:\n%s", want, note)
		}
	}
	// The kid was told.
	_, ev := e.do("GET", "/api/members?since=0", f.kid, nil)
	found := false
	for _, x := range ev["events"].([]any) {
		if m := x.(map[string]any); m["type"] == "chore" && m["kind"] == "new" && m["title"] == "Unload the dishwasher" {
			found = true
		}
	}
	if !found {
		t.Fatal("the kid wasn't told about the chore")
	}

	// Someone else's chore can't be ticked by another child.
	if st, _ := e.status("POST", "/api/chores/"+id+"/done", f.teen, nil); st != 403 {
		t.Fatalf("teen ticking kid's chore: %d", st)
	}
	// The kid ticks it: done today, due tomorrow, a star earned.
	st, ch = e.status("POST", "/api/chores/"+id+"/done", f.kid, nil)
	if st != 200 || ch["due"] != nextDue("day", today()) {
		t.Fatalf("tick: %d %v", st, ch)
	}
	b, _ = os.ReadFile(filepath.Join(vault, "Chores", "Unload the dishwasher.md"))
	if !strings.Contains(string(b), "- [x] "+today()+" done by [[Kid]]") || !strings.Contains(string(b), "- [ ] "+nextDue("day", today())) {
		t.Fatalf("after tick:\n%s", b)
	}
	_, list := e.status("GET", "/api/chores", f.mom, nil)
	if stars := list["stars"].(map[string]any); stars[f.kidID] != float64(1) {
		t.Fatalf("stars: %v", stars)
	}
	// Ticking twice for the same day does nothing more.
	e.status("POST", "/api/chores/"+id+"/done", f.kid, map[string]any{"date": today()})
	_, list = e.status("GET", "/api/chores", f.mom, nil)
	if stars := list["stars"].(map[string]any); stars[f.kidID] != float64(1) {
		t.Fatalf("double tick changed stars: %v", stars)
	}
	// It's in the feed, for the parents.
	_, feed := e.status("GET", "/api/feed", f.mom, nil)
	ok := false
	for _, x := range feed["items"].([]any) {
		if m := x.(map[string]any); m["type"] == "chore_done" && m["name"] == "Kid" {
			ok = true
		}
	}
	if !ok {
		t.Fatalf("feed: %v", feed)
	}

	// A parent takes it back: the star goes, the date comes back.
	if st, ch = e.status("POST", "/api/chores/"+id+"/undo", f.kid, nil); st != 403 {
		t.Fatalf("kid undoing: %d", st)
	}
	st, ch = e.status("POST", "/api/chores/"+id+"/undo", f.mom, map[string]any{"date": today()})
	if st != 200 || ch["due"] != today() {
		t.Fatalf("undo: %d %v", st, ch)
	}
	_, list = e.status("GET", "/api/chores", f.mom, nil)
	if stars := list["stars"].(map[string]any); stars[f.kidID] != nil {
		t.Fatalf("stars after undo: %v", stars)
	}

	// A younger child can't make chores (their preset is "view"); edits are for parents.
	if st, _ := e.status("POST", "/api/chores", f.kid, map[string]any{"title": "Nap", "who": []string{f.kidID}}); st != 403 {
		t.Fatalf("kid creating: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/chores/"+id, f.kid, map[string]any{"stars": 50}); st != 403 {
		t.Fatalf("kid editing: %d", st)
	}
	// A parent renames it: the note follows, and keeps what people wrote in it.
	os.WriteFile(filepath.Join(vault, "Chores", "Unload the dishwasher.md"), []byte(strings.Replace(string(b), "# Unload the dishwasher\n", "# Unload the dishwasher\n\nPlates go on the left.\n", 1)), 0o644)
	st, ch = e.status("PATCH", "/api/chores/"+id, f.mom, map[string]any{"title": "Empty the dishwasher"})
	if st != 200 || ch["title"] != "Empty the dishwasher" || ch["notes"] != "Plates go on the left." {
		t.Fatalf("rename: %d %v", st, ch)
	}
	if _, err := os.Stat(filepath.Join(vault, "Chores", "Empty the dishwasher.md")); err != nil {
		t.Fatal("note not renamed")
	}

	// A chore written by hand (say in Obsidian) is picked up, given an id, and its people found.
	os.WriteFile(filepath.Join(vault, "Chores", "Bins out.md"), []byte("---\ntype: chore\nwho:\n  - \"[[Teen]]\"\nevery: week\ndue: 2026-12-01\npoints: 2\n---\n# Bins out\n\n- [ ] 2026-12-01\n"), 0o644)
	_, list = e.status("GET", "/api/chores", f.teen, nil)
	var bins map[string]any
	for _, x := range list["chores"].([]any) {
		if m := x.(map[string]any); m["title"] == "Bins out" {
			bins = m
		}
	}
	if bins == nil || bins["id"] == "" || bins["stars"] != float64(2) || len(bins["who"].([]any)) != 1 || bins["who"].([]any)[0] != f.teenID {
		t.Fatalf("hand-written chore: %v", bins)
	}

	// Retired: hidden, but the note stays.
	if st, _ := e.status("DELETE", "/api/chores/"+id, f.mom, nil); st != 204 {
		t.Fatalf("archive: %d", st)
	}
	_, list = e.status("GET", "/api/chores", f.mom, nil)
	for _, x := range list["chores"].([]any) {
		if x.(map[string]any)["id"] == id {
			t.Fatal("archived chore still listed")
		}
	}
	if _, err := os.Stat(filepath.Join(vault, "Chores", "Empty the dishwasher.md")); err != nil {
		t.Fatal("archived note was deleted")
	}

	// Stars: a parent spends them.
	e.status("POST", "/api/members/"+f.kidID+"/stars", f.mom, map[string]any{"add": 5})
	if st, _ := e.status("POST", "/api/members/"+f.kidID+"/stars", f.kid, map[string]any{"add": 5}); st != 403 {
		t.Fatalf("kid giving stars: %d", st)
	}
	e.status("POST", "/api/members/"+f.kidID+"/stars", f.mom, map[string]any{"set": 2})
	_, list = e.status("GET", "/api/chores", f.mom, nil)
	if list["stars"].(map[string]any)[f.kidID] != float64(2) {
		t.Fatalf("stars after spending: %v", list["stars"])
	}
	_ = time.Now
}

func TestAgentKeys(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.cookie = f.owner
	// Keys only work once the family switches Steward on.
	_, k := e.status("POST", "/api/admin/keys", "", map[string]any{"name": "Hermes", "scopes": []string{"chores", "people"}})
	key, _ := k["key"].(string)
	if !strings.HasPrefix(key, "fl_") {
		t.Fatalf("key: %v", k)
	}
	e.cookie = nil
	if st, _ := e.status("GET", "/api/v1/people", key, nil); st != 403 {
		t.Fatalf("Steward off: %d", st)
	}
	e.cookie = f.owner
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chat": true, "chores": true, "agents": true}, "setupDone": true})
	e.cookie = nil

	// "Tell Kid to unload the dishwasher."
	_, people := e.status("GET", "/api/v1/people", key, nil)
	if len(people["people"].([]any)) != 4 {
		t.Fatalf("people: %v", people)
	}
	st, ch := e.status("POST", "/api/chores", key, map[string]any{"title": "Unload the dishwasher", "who": []string{f.kidID}})
	if st != 200 {
		t.Fatalf("agent creating a chore: %d %v", st, ch)
	}
	// The kid hears about it from "Hermes".
	_, ev := e.do("GET", "/api/members?since=0", f.kid, nil)
	told := false
	for _, x := range ev["events"].([]any) {
		if m := x.(map[string]any); m["type"] == "chore" && m["name"] == "Hermes" {
			told = true
		}
	}
	if !told {
		t.Fatal("kid not told")
	}
	// "I finished unloading the dishwasher": the agent ticks it off for the kid.
	if st, ch = e.status("POST", "/api/chores/"+ch["id"].(string)+"/done", key, map[string]any{"by": f.kidID}); st != 200 || len(ch["done"].([]any)) != 1 {
		t.Fatalf("agent ticking: %d %v", st, ch)
	}
	// Outside its scopes: refused.
	for _, p := range []string{"/api/feed", "/api/members", "/api/chat"} {
		if st, _ := e.status("GET", p, key, nil); st != 403 {
			t.Fatalf("%s with a chores+people key: %d", p, st)
		}
	}
	// Every use is logged; revoked keys stop working.
	e.cookie = f.owner
	_, keys := e.status("GET", "/api/admin/keys", "", nil)
	if len(keys["log"].([]any)) < 4 || keys["keys"].([]any)[0].(map[string]any)["hash"] != "" {
		t.Fatalf("keys: %v", keys)
	}
	id := keys["keys"].([]any)[0].(map[string]any)["id"].(string)
	e.status("DELETE", "/api/admin/keys/"+id, "", nil)
	e.cookie = nil
	if st, _ := e.status("GET", "/api/v1/people", key, nil); st != 401 {
		t.Fatalf("revoked key: %d", st)
	}
	// The API description is public.
	if st, spec := e.status("GET", "/api/v1/openapi.json", "", nil); st != 200 || spec["openapi"] != "3.0.3" {
		t.Fatalf("openapi: %d", st)
	}
}

func TestRewards(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	if st, _ := e.status("POST", "/api/rewards", f.kid, map[string]any{"title": "Screen time", "cost": 3}); st != 403 {
		t.Fatalf("children don't set rewards: %d", st)
	}
	st, rw := e.status("POST", "/api/rewards", f.mom, map[string]any{"title": "Screen time", "cost": 3})
	if st != 200 {
		t.Fatalf("add: %d %v", st, rw)
	}
	id := rw["id"].(string)
	st, ask := e.status("POST", "/api/rewards/"+id+"/ask", f.kid, nil)
	if st != 200 || ask["status"] != "asked" {
		t.Fatalf("ask: %d %v", st, ask)
	}
	ak := ask["id"].(string)
	if st, _ := e.status("POST", "/api/rewards/asks/"+ak+"/approve", f.kid, nil); st != 403 {
		t.Fatalf("a child can't approve: %d", st)
	}
	if st, _ := e.status("POST", "/api/rewards/asks/"+ak+"/approve", f.mom, nil); st != 409 {
		t.Fatalf("not enough stars yet: %d", st)
	}
	e.status("POST", "/api/members/"+f.kidID+"/stars", f.mom, map[string]any{"add": 5})
	if st, a := e.status("POST", "/api/rewards/asks/"+ak+"/approve", f.mom, nil); st != 200 || a["status"] != "approved" {
		t.Fatalf("approve: %d %v", st, a)
	}
	_, list := e.status("GET", "/api/chores", f.mom, nil)
	if list["stars"].(map[string]any)[f.kidID].(float64) != 2 {
		t.Fatalf("5 - 3 = 2 stars: %v", list["stars"])
	}
}

func TestWhenClock(t *testing.T) {
	for in, want := range map[string]int{"07:15": 435, "16:00-18:00": 960, "after school": 930, "Evening": 1110} {
		h, m, ok := whenClock(in)
		if !ok || h*60+m != want {
			t.Errorf("%q: %d:%d %v", in, h, m, ok)
		}
	}
	if _, _, ok := whenClock("whenever"); ok {
		t.Error("no time")
	}
}

// A fake model: first it asks for add_chore, then it answers.
func TestSteward(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	calls := 0
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		var req map[string]any
		json.NewDecoder(r.Body).Decode(&req)
		msgs := req["messages"].([]any)
		last := msgs[len(msgs)-1].(map[string]any)
		if last["role"] == "tool" {
			if !strings.Contains(last["content"].(string), "Unload the dishwasher") {
				t.Errorf("tool result: %v", last["content"])
			}
			fmt.Fprint(w, `{"choices":[{"message":{"role":"assistant","content":"Done. I asked Kid to unload the dishwasher."}}]}`)
			return
		}
		fmt.Fprint(w, `{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"1","type":"function","function":{"name":"add_chore","arguments":"{\"title\":\"Unload the dishwasher\",\"who\":[\"kid\"]}"}}]}}]}`)
	}))
	defer llm.Close()
	if st, _ := e.status("POST", "/api/steward", f.mom, map[string]any{"text": "hi"}); st != 403 {
		t.Fatalf("not set up yet: %d", st)
	}
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chat": true, "chores": true, "agents": true}, "setupDone": true})
	if st, c := e.status("PUT", "/api/admin/steward", "", map[string]any{"enabled": true, "url": llm.URL, "model": "test", "key": "secret"}); st != 200 || c["hasKey"] != true {
		t.Fatalf("config: %d %v", st, c)
	}
	if st, _ := e.status("POST", "/api/steward", f.kid, map[string]any{"text": "hi"}); st != 403 {
		t.Fatalf("grown-ups only by default: %d", st)
	}
	st, res := e.status("POST", "/api/steward", f.mom, map[string]any{"text": "Tell Kid to unload the dishwasher"})
	if st != 200 || !strings.Contains(res["reply"].(string), "Done") || calls != 2 {
		t.Fatalf("say: %d %v (%d calls)", st, res, calls)
	}
	_, list := e.status("GET", "/api/chores", f.mom, nil)
	ch := list["chores"].([]any)[0].(map[string]any)
	if ch["title"] != "Unload the dishwasher" || ch["who"].([]any)[0] != f.kidID {
		t.Fatalf("chore: %v", ch)
	}
	if _, h := e.status("GET", "/api/steward", f.mom, nil); len(h["messages"].([]any)) != 2 {
		t.Fatalf("history: %v", h)
	}
}

func TestRidesAndCheckPlans(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	now := time.Now().UnixMilli()
	e.status("POST", "/api/location", f.kid, map[string]any{"lat": 51.5, "lon": -0.1, "acc": 10, "battery": 80, "time": now})
	e.status("POST", "/api/location", f.mom, map[string]any{"lat": 51.6, "lon": -0.1, "acc": 10, "battery": 80, "time": now})
	if st, out := e.status("POST", "/api/requests", f.mom, map[string]any{"to": f.kidID, "kind": "coming"}); st >= 300 {
		t.Fatalf("on my way: %d %v", st, out)
	}
	ride := func() any {
		_, m := e.status("GET", "/api/members", f.kid, nil)
		for _, x := range m["members"].([]any) {
			if x.(map[string]any)["id"] == f.momID {
				return x.(map[string]any)["rideTo"]
			}
		}
		return nil
	}
	if ride() != f.kidID {
		t.Fatal("mom should be on her way to the kid")
	}
	e.status("POST", "/api/location", f.mom, map[string]any{"lat": 51.5005, "lon": -0.1, "acc": 10, "battery": 80, "time": now + 1000})
	if ride() != nil {
		t.Fatal("arrived: the ride ends")
	}
	if st, _ := e.status("PATCH", "/api/me", f.mom, map[string]any{"phone": "+44 7700 900123"}); st != 200 {
		t.Fatalf("phone: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/me", f.mom, map[string]any{"phone": "call me maybe"}); st != 400 {
		t.Fatalf("bad phone: %d", st)
	}
	// A check-in window: a child can't set one; a parent can.
	plan := map[string]any{"memberId": f.kidID, "placeId": "nope", "days": []int{1, 2}, "from": "08:00", "until": "08:45"}
	if st, _ := e.status("POST", "/api/checkplans", f.kid, plan); st != 403 {
		t.Fatalf("children don't set these: %d", st)
	}
	if st, _ := e.status("POST", "/api/checkplans", f.mom, plan); st != 404 {
		t.Fatalf("unknown fence: %d", st)
	}
}
