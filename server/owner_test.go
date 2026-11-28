package main

import "testing"

func TestOwnerJoinsFamily(t *testing.T) {
	e := newEnv(t)
	body := map[string]any{"name": "Alex", "username": "alex", "password": "blue canoe at midnight"}
	if st, _ := e.status("POST", "/api/admin/owner", "", body); st != 401 && st != 403 {
		t.Fatalf("not logged in: %d", st)
	}
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	if st, out := e.status("POST", "/api/admin/owner", "", body); st != 200 {
		t.Fatalf("add owner: %d %v", st, out)
	}
	// Now signed in as themselves, with the main admin's powers.
	st, sess := e.status("GET", "/api/admin/session", "", nil)
	if st != 200 || sess["owner"] != true || sess["member"] == nil || sess["ownerInFamily"] != true {
		t.Fatalf("session: %d %v", st, sess)
	}
	// Only once, and only with the admin password.
	if st, _ := e.status("POST", "/api/admin/owner", "", map[string]any{"name": "Al", "username": "al2", "password": "blue canoe at midnight"}); st != 403 {
		t.Fatalf("as a member: %d, want 403", st)
	}
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	if st, _ := e.status("POST", "/api/admin/owner", "", map[string]any{"name": "Al", "username": "al2", "password": "blue canoe at midnight"}); st != 409 {
		t.Fatalf("second owner: %d, want 409", st)
	}
	// They can sign in with their own account.
	if st, _ := e.status("POST", "/api/admin/login", "", map[string]string{"username": "alex", "password": "blue canoe at midnight"}); st != 204 {
		t.Fatalf("own login: %d", st)
	}
}
