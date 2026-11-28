package main

import "testing"

func TestBarter(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chores": true}, "setupDone": true})
	_, ch := e.status("POST", "/api/chores", f.mom, map[string]any{"title": "Dishes", "who": []string{f.teenID}, "stars": 2})
	id := ch["id"].(string)
	e.status("POST", "/api/members/"+f.teenID+"/stars", f.mom, map[string]any{"set": 10})
	stars := func(tok, who string) float64 {
		_, out := e.status("GET", "/api/chores", tok, nil)
		v, _ := out["stars"].(map[string]any)[who].(float64)
		return v
	}
	// Grown-ups don't trade, and you can't spend points you haven't got.
	if st, _ := e.status("POST", "/api/barters", f.mom, map[string]any{"choreId": id, "to": f.kidID, "points": 3}); st != 403 {
		t.Fatalf("parent offers: %d", st)
	}
	if st, _ := e.status("POST", "/api/barters", f.teen, map[string]any{"choreId": id, "to": f.kidID, "points": 50}); st != 400 {
		t.Fatalf("too many points: %d", st)
	}
	st, b := e.status("POST", "/api/barters", f.teen, map[string]any{"choreId": id, "to": f.kidID, "points": 3})
	if st != 200 {
		t.Fatalf("offer: %d %v", st, b)
	}
	bid := b["id"].(string)
	if st, _ := e.status("POST", "/api/barters/"+bid+"/accept", f.teen, nil); st != 403 {
		t.Fatalf("offerer accepts: %d", st)
	}
	if st, _ := e.status("POST", "/api/barters/"+bid+"/accept", f.kid, nil); st != 200 {
		t.Fatalf("accept: %d", st)
	}
	// The chore is the younger child's now; when they do it, 3 points move (plus its own 2).
	if st, _ := e.status("POST", "/api/chores/"+id+"/done", f.kid, map[string]any{}); st != 200 {
		t.Fatalf("done: %d", st)
	}
	if got := stars(f.mom, f.teenID); got != 7 {
		t.Fatalf("teen points: %v", got)
	}
	if got := stars(f.mom, f.kidID); got != 5 {
		t.Fatalf("kid points: %v", got)
	}
	// A parent undoes it: the 3 go back.
	if st, _ := e.status("POST", "/api/barters/"+bid+"/undo", f.mom, nil); st != 200 {
		t.Fatalf("undo: %d", st)
	}
	if stars(f.mom, f.teenID) != 10 || stars(f.mom, f.kidID) != 2 {
		t.Fatalf("after undo: %v %v", stars(f.mom, f.teenID), stars(f.mom, f.kidID))
	}
}
