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
	// Presets: the younger child has Thereabouts and Chitchat only; the older child everything.
	_, m := e.do("GET", "/api/members", f.kid, nil)
	kid := m["settings"].(map[string]any)["kid"].(map[string]any)
	mods := kid["modules"].(map[string]any)
	if kid["preset"] != "younger" || mods["thereabouts"] != "full" || mods["chat"] != "full" || mods["meals"] != "off" ||
		kid["features"].(map[string]any)["sos"] != true || kid["features"].(map[string]any)["chat_delete"] != false {
		t.Fatalf("younger preset: %v", kid)
	}
	if m["settings"].(map[string]any)["big"] != true || m["settings"].(map[string]any)["pictures"] != true {
		t.Fatal("younger children get big buttons and pictures by default")
	}
	_, m = e.do("GET", "/api/members", f.teen, nil)
	if m["settings"].(map[string]any)["kid"].(map[string]any)["modules"].(map[string]any)["meals"] != "full" {
		t.Fatal("older preset should have everything")
	}
	// Only parents change them; children can't, nor arrange their own home screen.
	path := "/api/members/" + f.kidID + "/kid"
	if st, _ := e.status("PUT", path, f.teen, map[string]any{"preset": "older"}); st != 403 {
		t.Fatalf("a child changed settings: %d", st)
	}
	st, got := e.status("PUT", path, f.mom, map[string]any{"modules": map[string]string{"chat": "off"}, "home": []string{"sos:half", "map:big", "sos"}})
	if st != 200 || got["settings"].(map[string]any)["modules"].(map[string]any)["chat"] != "off" || len(got["home"].([]any)) != 2 {
		t.Fatalf("set: %d %v", st, got)
	}
	if st, _ := e.status("GET", "/api/chat", f.kid, nil); st != 403 {
		t.Fatalf("chat should be off for the kid: %d", st)
	}
	if st, _ := e.status("PUT", path, f.mom, map[string]any{"home": []string{"casino"}}); st != 400 {
		t.Fatalf("unknown tile: %d", st)
	}
	if st, _ := e.status("PUT", path, f.mom, map[string]any{"home": []string{"map:half", "convo.abc123:half", "routines"}}); st != 200 {
		t.Fatalf("a half-width map, a chat shortcut and a placeholder: %d", st)
	}
	if st, _ := e.status("PUT", path, f.mom, map[string]any{"home": []string{"convo.../etc"}}); st != 400 {
		t.Fatalf("a bad chat shortcut: %d", st)
	}
	if st, _ := e.status("PUT", path, f.mom, map[string]any{"modules": map[string]string{"meals": "maybe"}}); st != 400 {
		t.Fatalf("bad level: %d", st)
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
	if _, got := e.do("PUT", path, f.dad, map[string]any{"preset": "older"}); got["settings"].(map[string]any)["modules"].(map[string]any)["chat"] != "full" {
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

func TestAccessLevelsAndGrandparents(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	// Chitchat "view only": the child reads but can't post.
	e.do("PUT", "/api/members/"+f.teenID+"/kid", f.mom, map[string]any{"modules": map[string]string{"chat": "view"}})
	if st, _ := e.status("GET", "/api/chat/family/messages", f.teen, nil); st != 200 {
		t.Fatalf("view only can't read: %d", st)
	}
	if st, _ := e.status("POST", "/api/chat/family/messages", f.teen, map[string]string{"text": "hi"}); st != 403 {
		t.Fatalf("view only posted: %d", st)
	}
	// A child can't delete their own message unless a parent allows it.
	_, msg := e.do("POST", "/api/chat/family/messages", f.kid, map[string]string{"text": "oops"})
	mid := itoa(int64(msg["id"].(float64)))
	if st, _ := e.status("DELETE", "/api/chat/family/messages/"+mid, f.kid, nil); st != 403 {
		t.Fatalf("kid deleted without permission: %d", st)
	}
	e.do("PUT", "/api/members/"+f.kidID+"/kid", f.mom, map[string]any{"features": map[string]bool{"chat_delete": true}})
	if st, _ := e.status("DELETE", "/api/chat/family/messages/"+mid, f.kid, nil); st != 204 {
		t.Fatalf("kid delete with permission: %d", st)
	}

	// Grandparents: invited as such, no oversight of the children's chats, options set by parents or themselves.
	e.cookie = f.owner
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Nana", "access": "grandparent"})
	e.cookie = nil
	_, out := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string), "name": "Nana", "username": "nana", "password": "purple teapot on sunday"})
	nana, nanaID := out["token"].(string), out["member"].(map[string]any)["id"].(string)
	if out["member"].(map[string]any)["access"] != "grandparent" {
		t.Fatalf("grandparent invite: %v", out["member"])
	}
	_, dm := e.do("POST", "/api/chat/direct", f.kid, map[string]string{"to": f.teenID})
	if _, list := e.do("GET", "/api/chat", nana, nil); convIDs(list)[dm["id"].(string)] != nil {
		t.Fatal("a grandparent can see the children's chat")
	}
	if st, g := e.status("PUT", "/api/members/"+nanaID+"/kid", f.mom, map[string]any{"big": true}); st != 200 || g["big"] != true || g["pictures"] != false {
		t.Fatalf("grandparent options: %d %v", st, g)
	}
	if st, _ := e.status("PATCH", "/api/me", nana, map[string]any{"pictures": true, "home": []string{"chat:wide", "map:big"}}); st != 200 {
		t.Fatalf("grandparent own options: %d", st)
	}
	if st, _ := e.status("PUT", "/api/members/"+f.kidID+"/kid", nana, map[string]any{"preset": "older"}); st != 403 {
		t.Fatalf("a grandparent changed a child's settings: %d", st)
	}

	// Nudges: the grown-up ones only between adults; parents can switch nudges off for a child.
	if st, _ := e.status("POST", "/api/requests", f.mom, map[string]string{"to": nanaID, "kind": "peach"}); st != 204 {
		t.Fatalf("adult nudge: %d", st)
	}
	if st, _ := e.status("POST", "/api/requests", f.mom, map[string]string{"to": f.kidID, "kind": "eggplant"}); st != 400 {
		t.Fatalf("adult nudge to a child: %d", st)
	}
	if st, _ := e.status("POST", "/api/requests", f.kid, map[string]string{"to": f.momID, "kind": "cowboy"}); st != 204 {
		t.Fatalf("kid nudge: %d", st)
	}
	e.do("PUT", "/api/members/"+f.kidID+"/kid", f.mom, map[string]any{"features": map[string]bool{"nudges": false}})
	if st, _ := e.status("POST", "/api/requests", f.kid, map[string]string{"to": f.momID, "kind": "alien"}); st != 403 {
		t.Fatalf("nudges off: %d", st)
	}

	// Opening settings on a child's device from afar.
	_, before := e.do("GET", "/api/members?since=0", f.kid, nil)
	cursor := int64(before["cursor"].(float64))
	if st, _ := e.status("POST", "/api/members/"+f.kidID+"/unlock", f.mom, map[string]int{"minutes": 10}); st != 204 {
		t.Fatalf("unlock: %d", st)
	}
	if st, _ := e.status("POST", "/api/members/"+f.kidID+"/unlock", f.teen, map[string]int{"minutes": 10}); st != 403 {
		t.Fatalf("a child unlocked: %d", st)
	}
	_, after := e.do("GET", "/api/members?since="+itoa(cursor), f.kid, nil)
	got := false
	for _, ev := range after["events"].([]any) {
		if m := ev.(map[string]any); m["type"] == "unlock" && m["duration"].(float64) == 600000 {
			got = true
		}
	}
	if !got {
		t.Fatalf("no unlock event: %v", after["events"])
	}
}
