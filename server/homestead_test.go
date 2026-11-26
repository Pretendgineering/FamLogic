package main

import (
	"slices"
	"strings"
	"testing"
)

func TestHomestead(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chores": true, "calendar": true, "homestead": true}, "setupDone": true})

	if st, _ := e.status("PUT", "/api/home", f.kid, map[string]any{"features": []string{"smoke"}}); st != 403 {
		t.Fatalf("children don't set up the home: %d", st)
	}
	st, res := e.status("PUT", "/api/home", f.mom, map[string]any{"tenure": "own", "features": []string{"smoke", "hvac", "nonsense"}, "bins": "thu", "recycling": "thu", "recyclingEvery": "2 weeks",
		"lastDone": map[string]string{"furnace-filter": "2026-10-01"}})
	if st != 200 {
		t.Fatalf("set: %d %v", st, res)
	}
	prof := res["profile"].(map[string]any)
	if len(prof["features"].([]any)) != 2 {
		t.Fatalf("unknown features dropped: %v", prof)
	}
	jobs := func() map[string]map[string]any {
		_, ch := e.status("GET", "/api/chores", f.mom, nil)
		out := map[string]map[string]any{}
		for _, c := range ch["chores"].([]any) {
			cm := c.(map[string]any)
			if src, _ := cm["source"].(string); strings.HasPrefix(src, "home:") {
				out[src] = cm
			}
		}
		return out
	}
	j := jobs()
	// smoke: 2 jobs, hvac: 2, bins, recycling
	if len(j) != 6 {
		t.Fatalf("home chores: %v", j)
	}
	if j["home:furnace-filter"]["due"] != "2027-01-01" || j["home:furnace-filter"]["for"] != "Home" || len(j["home:furnace-filter"]["who"].([]any)) != 0 {
		t.Fatalf("furnace filter: %v", j["home:furnace-filter"])
	}
	if j["home:bins"]["every"] != "thu" || j["home:recycling"]["every"] != "2 weeks" {
		t.Fatalf("bins: %v %v", j["home:bins"], j["home:recycling"])
	}
	events := func() []string {
		_, ev := e.status("GET", "/api/events", f.mom, nil) // today and the next 30 days
		var out []string
		for _, x := range ev["events"].([]any) {
			out = append(out, x.(map[string]any)["title"].(string))
		}
		return out
	}
	if ev := events(); !slices.Contains(ev, "Bins day") || !slices.Contains(ev, "Recycling day") {
		t.Fatalf("bin days on the calendar: %v", ev)
	}
	// Untick hvac, move bins to Monday, stop recycling: chores and events follow.
	e.status("PUT", "/api/home", f.mom, map[string]any{"features": []string{"smoke"}, "bins": "mon"})
	j = jobs()
	if _, ok := j["home:furnace-filter"]; ok || len(j) != 3 || j["home:bins"]["every"] != "mon" {
		t.Fatalf("after changes: %v", j)
	}
	if ev := events(); slices.Contains(ev, "Recycling day") {
		t.Fatalf("recycling should be gone: %v", ev)
	}
	// Saving again changes nothing (no duplicates).
	e.status("PUT", "/api/home", f.mom, map[string]any{"features": []string{"smoke"}, "bins": "mon"})
	if len(jobs()) != 3 {
		t.Fatal("duplicates")
	}
}
