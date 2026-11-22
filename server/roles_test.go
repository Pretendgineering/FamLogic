package main

import "testing"

func TestRolesOverHTTP(t *testing.T) {
	e := newEnv(t)
	owner := e.cookie
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	owner = e.cookie
	const pw = "blue canoe at midnight"
	invite := func(name, access string) string {
		e.cookie = owner
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name, "access": access})
		return inv["code"].(string)
	}

	// A parent with an account.
	_, mom := e.do("POST", "/api/join", "", map[string]string{"code": invite("Mom", ""), "username": "mom", "password": pw})
	momID := mom["member"].(map[string]any)["id"].(string)

	// A younger child: the code is passwordless, and a username/password sent anyway is ignored.
	code := invite("Kid", "younger_child")
	if st, info := e.status("GET", "/api/invites/"+code, "", nil); st != 200 || info["passwordless"] != true {
		t.Fatalf("child invite info: %d %v", st, info)
	}
	st, kid := e.status("POST", "/api/join", "", map[string]string{"code": code, "username": "kiddo", "password": pw})
	if st != 200 || kid["username"] != "" || kid["member"].(map[string]any)["role"] != "child" {
		t.Fatalf("child join: %d %v", st, kid)
	}
	kidID := kid["member"].(map[string]any)["id"].(string)
	kidToken := kid["token"].(string)
	if st, _ := e.status("POST", "/api/me/password", kidToken, map[string]string{"newPassword": pw, "username": "kiddo"}); st != 403 {
		t.Fatalf("younger child set a password: %d", st)
	}
	// A new device for the child: the parent's re-issued code signs it in, no password.
	e.cookie = owner
	_, reset := e.do("POST", "/api/admin/members/"+kidID+"/reset", "", nil)
	rcode := reset["code"].(string)
	if _, info := e.do("GET", "/api/invites/"+rcode, "", nil); info["passwordless"] != true {
		t.Fatalf("child reset info: %v", info)
	}
	if st, out := e.status("POST", "/api/join", "", map[string]string{"code": rcode}); st != 200 || out["token"] == "" {
		t.Fatalf("child new device: %d %v", st, out)
	}

	// Mom logs in to the console with her own account: parent tools, not admin pages.
	e.cookie = nil
	if st, _ := e.status("POST", "/api/admin/login", "", map[string]string{"username": "mom", "password": "wrong one entirely"}); st != 401 {
		t.Fatalf("wrong password: %d", st)
	}
	if st, _ := e.status("POST", "/api/admin/login", "", map[string]string{"username": "mom", "password": pw}); st != 204 {
		t.Fatalf("parent login: %d", st)
	}
	momCookie := e.cookie
	if st, sess := e.status("GET", "/api/admin/session", "", nil); st != 200 || sess["admin"] != false || sess["owner"] != false {
		t.Fatalf("parent session: %d %v", st, sess)
	}
	if st, _ := e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{}}); st != 403 {
		t.Fatalf("parent changed modules: %d", st)
	}
	if st, _ := e.status("GET", "/api/admin/invites", "", nil); st != 403 {
		t.Fatalf("parent reached an admin page: %d", st)
	}
	// ...and uses the app's API as herself.
	if st, out := e.status("GET", "/api/members?since=0", "", nil); st != 200 || out["me"] != momID {
		t.Fatalf("parent via console: %d %v", st, out["me"])
	}

	// Only the main admin makes Mom an admin; then she gets the admin pages.
	if st, _ := e.status("PUT", "/api/admin/members/"+momID+"/role", "", map[string]string{"role": "admin"}); st != 403 {
		t.Fatalf("non-admin made herself admin: %d", st)
	}
	e.cookie = owner
	if st, _ := e.status("PUT", "/api/admin/members/"+momID+"/role", "", map[string]string{"role": "admin"}); st != 204 {
		t.Fatalf("owner grants admin: %d", st)
	}
	e.cookie = momCookie
	if st, _ := e.status("GET", "/api/admin/invites", "", nil); st != 200 {
		t.Fatalf("admin page for an admin: %d", st)
	}
	// An admin who isn't the main admin can't hand out admin either.
	if st, _ := e.status("PUT", "/api/admin/members/"+kidID+"/role", "", map[string]string{"role": "admin"}); st != 403 {
		t.Fatalf("admin granted admin: %d", st)
	}
	// ...but can let an older child use a password.
	if st, _ := e.status("PUT", "/api/admin/members/"+kidID+"/role", "", map[string]string{"role": "older_child"}); st != 204 {
		t.Fatalf("older child: %d", st)
	}

	// Children can't log in to the console.
	e.cookie = nil
	if st, _ := e.status("POST", "/api/admin/login", "", map[string]string{"username": "kiddo", "password": pw}); st != 401 {
		t.Fatalf("child console login: %d", st)
	}

	// Non-users.
	e.cookie = owner
	if st, baby := e.status("POST", "/api/admin/dependents", "", map[string]string{"name": "Baby Lu"}); st != 200 || baby["role"] != "dependent" {
		t.Fatalf("dependent: %d %v", st, baby)
	}
}
