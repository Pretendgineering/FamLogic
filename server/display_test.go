package main

import (
	"testing"
	"time"
)

func TestDisplay(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chores": true, "display": true}, "setupDone": true})
	if st, _ := e.status("POST", "/api/display/quiet", f.kid, map[string]any{"minutes": 30}); st != 403 {
		t.Fatalf("children don't start quiet time: %d", st)
	}
	if st, _ := e.status("POST", "/api/display/quiet", f.mom, map[string]any{"minutes": 60}); st != 200 {
		t.Fatalf("quiet: %d", st)
	}
	if st, _ := e.status("POST", "/api/display/messages", f.mom, map[string]any{"text": "Inside voices please"}); st != 200 {
		t.Fatalf("message: %d", st)
	}
	_, b := e.status("GET", "/api/display/board", f.kid, nil)
	q := b["quiet"].(map[string]any)
	if q["on"] != true || q["by"] != "Mom" || len(b["messages"].([]any)) != 1 || b["chores"] == nil {
		t.Fatalf("board: %v", b)
	}
	e.status("DELETE", "/api/display/quiet", f.dad, nil)
	_, b = e.status("GET", "/api/display/board", f.kid, nil)
	if b["quiet"].(map[string]any)["on"] != false {
		t.Fatal("quiet should be over")
	}
}

func TestSunTimes(t *testing.T) {
	// London, midsummer: sunrise about 04:43 UTC, sunset about 20:21 UTC.
	rise, set, ok := sunTimes(time.Date(2026, 6, 21, 12, 0, 0, 0, time.UTC), 51.5, -0.12)
	if !ok || rise.UTC().Hour() != 3 && rise.UTC().Hour() != 4 || set.UTC().Hour() != 20 {
		t.Fatalf("sun: %v %v %v", rise.UTC(), set.UTC(), ok)
	}
}
