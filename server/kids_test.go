package main

import "testing"

func TestParentPIN(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	if st, _ := e.status("POST", "/api/pin/check", f.kid, map[string]string{"pin": "1234"}); st != 404 {
		t.Fatalf("no PIN yet: %d", st)
	}
	if st, _ := e.status("PUT", "/api/family/pin", f.mom, map[string]string{"pin": "1234"}); st != 403 {
		t.Fatalf("a non-admin parent set the PIN: %d", st)
	}
	e.cookie = f.owner
	if st, _ := e.status("PUT", "/api/admin/pin", "", map[string]string{"pin": "12a"}); st != 400 {
		t.Fatalf("bad PIN accepted: %d", st)
	}
	if st, _ := e.status("PUT", "/api/admin/pin", "", map[string]string{"pin": "2468"}); st != 204 {
		t.Fatalf("set PIN: %d", st)
	}
	if _, got := e.do("GET", "/api/admin/pin", "", nil); got["set"] != true {
		t.Fatalf("pin status: %v", got)
	}
	e.cookie = nil
	if st, _ := e.status("POST", "/api/pin/check", f.kid, map[string]string{"pin": "2468"}); st != 204 {
		t.Fatalf("right PIN: %d", st)
	}
	for i := 0; i < maxFailures; i++ {
		e.status("POST", "/api/pin/check", f.kid, map[string]string{"pin": "0000"})
	}
	if st, _ := e.status("POST", "/api/pin/check", f.kid, map[string]string{"pin": "2468"}); st != 429 {
		t.Fatalf("not locked after wrong guesses: %d", st)
	}
	if _, m := e.do("GET", "/api/members", f.teen, nil); m["settings"].(map[string]any)["pinSet"] != true {
		t.Fatal("apps should know a PIN is set")
	}
}

func TestKidSettings(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	// Presets: the younger child has no map, the older child has it.
	_, m := e.do("GET", "/api/members", f.kid, nil)
	kid := m["settings"].(map[string]any)["kid"].(map[string]any)
	if kid["preset"] != "younger" || kid["sections"].(map[string]any)["map"] != false || kid["features"].(map[string]any)["sos"] != true {
		t.Fatalf("younger preset: %v", kid)
	}
	_, m = e.do("GET", "/api/members", f.teen, nil)
	if m["settings"].(map[string]any)["kid"].(map[string]any)["sections"].(map[string]any)["map"] != true {
		t.Fatal("older preset should have the map")
	}
	// Only parents change them; children can't, nor arrange their own home screen.
	path := "/api/members/" + f.kidID + "/kid"
	if st, _ := e.status("PUT", path, f.teen, map[string]any{"preset": "older"}); st != 403 {
		t.Fatalf("a child changed settings: %d", st)
	}
	st, got := e.status("PUT", path, f.mom, map[string]any{"sections": map[string]bool{"chat": false}, "home": []string{"sos", "map", "sos"}})
	if st != 200 || got["settings"].(map[string]any)["sections"].(map[string]any)["chat"] != false || len(got["home"].([]any)) != 2 {
		t.Fatalf("set: %d %v", st, got)
	}
	if st, _ := e.status("GET", "/api/chat", f.kid, nil); st != 403 {
		t.Fatalf("chat should be off for the kid: %d", st)
	}
	if st, _ := e.status("PUT", path, f.mom, map[string]any{"home": []string{"casino"}}); st != 400 {
		t.Fatalf("unknown tile: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/me", f.kid, map[string]any{"home": []string{"map"}}); st != 403 {
		t.Fatalf("kid arranged their home: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/me", f.kid, map[string]any{"paused": true}); st != 403 {
		t.Fatalf("kid paused without permission: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/me", f.mom, map[string]any{"home": []string{"chat", "map"}}); st != 200 {
		t.Fatalf("adult home: %d", st)
	}
	// A new preset starts afresh.
	if _, got := e.do("PUT", path, f.dad, map[string]any{"preset": "older"}); got["settings"].(map[string]any)["sections"].(map[string]any)["chat"] != true {
		t.Fatalf("preset reset: %v", got)
	}
	if st, _ := e.status("PUT", "/api/members/"+f.momID+"/kid", f.dad, map[string]any{"preset": "older"}); st != 400 {
		t.Fatalf("kid settings on an adult: %d", st)
	}
}

func TestChatModeration(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	_, dm := e.do("POST", "/api/chat/direct", f.kid, map[string]string{"to": f.teenID})
	id := dm["id"].(string)
	_, msg := e.do("POST", "/api/chat/"+id+"/messages", f.teen, map[string]string{"text": "mean thing"})
	mid := itoa(int64(msg["id"].(float64)))

	// Pause: read only. Block: gone for the children, still there for parents.
	e.do("POST", "/api/chat/"+id+"/moderate", f.mom, map[string]string{"action": "pause"})
	if st, _ := e.status("POST", "/api/chat/"+id+"/messages", f.kid, map[string]string{"text": "hi"}); st != 403 {
		t.Fatalf("posted while paused: %d", st)
	}
	e.do("POST", "/api/chat/"+id+"/moderate", f.mom, map[string]string{"action": "block"})
	if _, list := e.do("GET", "/api/chat", f.kid, nil); convIDs(list)[id] != nil {
		t.Fatal("a blocked chat is still visible to the child")
	}
	if _, list := e.do("GET", "/api/chat", f.dad, nil); convIDs(list)[id]["status"] != "blocked" {
		t.Fatal("parents should still see a blocked chat")
	}
	e.do("POST", "/api/chat/"+id+"/moderate", f.mom, map[string]string{"action": "unblock"})

	// Deleting messages: the kid can't delete the teen's; a parent can.
	if st, _ := e.status("DELETE", "/api/chat/"+id+"/messages/"+mid, f.kid, nil); st != 403 {
		t.Fatalf("kid deleted someone else's message: %d", st)
	}
	if st, _ := e.status("DELETE", "/api/chat/"+id+"/messages/"+mid, f.dad, nil); st != 204 {
		t.Fatalf("parent delete: %d", st)
	}
	// Parents can't moderate parents' own chats or the family chat.
	_, pd := e.do("POST", "/api/chat/direct", f.mom, map[string]string{"to": f.dadID})
	if st, _ := e.status("POST", "/api/chat/"+pd["id"].(string)+"/moderate", f.dad, map[string]string{"action": "delete"}); st != 403 {
		t.Fatalf("moderated a parents' chat: %d", st)
	}
	if st, _ := e.status("POST", "/api/chat/family/moderate", f.dad, map[string]string{"action": "pause"}); st != 400 {
		t.Fatalf("paused the family chat: %d", st)
	}
	// Delete the whole chat.
	if st, _ := e.status("POST", "/api/chat/"+id+"/moderate", f.dad, map[string]string{"action": "delete"}); st != 204 {
		t.Fatalf("delete chat: %d", st)
	}
	if st, _ := e.status("GET", "/api/chat/"+id+"/messages", f.teen, nil); st != 404 {
		t.Fatalf("deleted chat still there: %d", st)
	}
}
