package main

import (
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	// Real hashing and delays would make the tests slow; the logic is the same.
	pbkdf2Iterations = 1000
	wrongPasswordDelay = 0
	os.Exit(m.Run())
}

func TestPasswordRules(t *testing.T) {
	cases := []struct {
		pw   string
		ok   bool
		want string // part of the error message
	}{
		{"short", false, "at least 12"},
		{"correct horse battery", true, ""},
		{"Tr0ub4dor&3-elephant", true, ""},
		{"password1234", false, "too common"},
		{"P@ssw0rd2024!", false, "too common"},
		{"basketball12345", false, "too common"},
		{"iloveyou2024!!", false, "too common"},
		{"QWERTYUIOP!!", false, "too common"},
		{"123456789012", false, "only numbers"},
		{"aaaaaaaaaaaaaaaa", false, "repetitive"},
		{"abababababab", false, "repetitive"},
		{"taylor-likes-hiking", false, "username"},
		{"my famlogic pass", false, "username"},
		{strings.Repeat("abcdefghij", 13), false, "at most"},
	}
	for _, c := range cases {
		err := CheckPassword(c.pw, "taylor", "Taylor")
		if c.ok != (err == nil) || (err != nil && !strings.Contains(err.Error(), c.want)) {
			t.Errorf("CheckPassword(%q) = %v, want ok=%v containing %q", c.pw, err, c.ok, c.want)
		}
	}
}

func TestHashing(t *testing.T) {
	h, err := HashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyPassword("correct horse battery", h) || VerifyPassword("correct horse batterY", h) || VerifyPassword("x", "") {
		t.Fatal("password verification is wrong")
	}
	if h2, _ := HashPassword("correct horse battery"); h2 == h {
		t.Fatal("hashes must be salted")
	}
}

func TestUsernames(t *testing.T) {
	if u, err := NormalizeUsername("  Taylor.S "); err != nil || u != "taylor.s" {
		t.Fatalf("got %q, %v", u, err)
	}
	for _, bad := range []string{"ab", "-dash", "has space", "émile", strings.Repeat("a", 33)} {
		if _, err := NormalizeUsername(bad); err == nil {
			t.Errorf("username %q accepted", bad)
		}
	}
}

// status is do() returning just the status code and body.
func (e *testEnv) status(method, path, token string, body any) (int, map[string]any) {
	e.t.Helper()
	r, out := e.do(method, path, token, body)
	return r.StatusCode, out
}

func TestAccountsFlow(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	invite := func(name string) string {
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name})
		return inv["code"].(string)
	}
	const pw = "blue canoe at midnight"

	// The app can ask what a code is for.
	code := invite("Taylor")
	if st, info := e.status("GET", "/api/invites/"+code, "", nil); st != 200 || info["kind"] != "join" || info["name"] != "Taylor" {
		t.Fatalf("invite info: %d %v", st, info)
	}

	// Weak password and bad username are refused, and the invite isn't used up.
	if st, out := e.status("POST", "/api/join", "", map[string]string{"code": code, "username": "taylor", "password": "password1234"}); st != 400 || !strings.Contains(out["error"].(string), "common") {
		t.Fatalf("weak password: %d %v", st, out)
	}
	if st, _ := e.status("POST", "/api/join", "", map[string]string{"code": code, "username": "x", "password": pw}); st != 400 {
		t.Fatalf("bad username accepted: %d", st)
	}
	st, joined := e.status("POST", "/api/join", "", map[string]string{"code": code, "username": "Taylor", "password": pw})
	if st != 200 || joined["username"] != "taylor" {
		t.Fatalf("join: %d %v", st, joined)
	}
	phone1 := joined["token"].(string)
	id := joined["member"].(map[string]any)["id"].(string)

	// Usernames are unique (case-insensitively).
	if st, _ := e.status("POST", "/api/join", "", map[string]string{"code": invite("Other"), "username": "TAYLOR", "password": "another long phrase"}); st != 409 {
		t.Fatalf("duplicate username: %d", st)
	}

	// Wrong password / unknown username look the same.
	_, a := e.status("POST", "/api/signin", "", map[string]string{"username": "taylor", "password": "not my password"})
	_, b := e.status("POST", "/api/signin", "", map[string]string{"username": "nobody", "password": "not my password"})
	if a["error"] != b["error"] || a["error"] != "Wrong username or password" {
		t.Fatalf("sign-in errors differ: %v vs %v", a, b)
	}

	// New phone: sign in. Same member, and the old phone is signed out with a reason.
	st, signed := e.status("POST", "/api/signin", "", map[string]string{"username": " Taylor ", "password": pw})
	if st != 200 || signed["member"].(map[string]any)["id"] != id {
		t.Fatalf("sign-in: %d %v", st, signed)
	}
	phone2 := signed["token"].(string)
	if st, out := e.status("GET", "/api/members", phone1, nil); st != 401 || out["reason"] != "signed_in_elsewhere" {
		t.Fatalf("old phone: %d %v", st, out)
	}
	if st, _ := e.status("GET", "/api/members", phone2, nil); st != 200 {
		t.Fatalf("new phone not signed in: %d", st)
	}

	// Changing the password needs the current one.
	if st, _ := e.status("POST", "/api/me/password", phone2, map[string]string{"currentPassword": "nope nope nope", "newPassword": "green kettle on a hill"}); st != 403 {
		t.Fatalf("change without current password: %d", st)
	}
	if st, _ := e.status("POST", "/api/me/password", phone2, map[string]string{"currentPassword": pw, "newPassword": "green kettle on a hill"}); st != 200 {
		t.Fatalf("change password: %d", st)
	}
	if st, _ := e.status("GET", "/api/members", phone2, nil); st != 200 {
		t.Fatal("changing the password must not sign this phone out")
	}

	// Forgotten password: the admin makes a reset link; it keeps the same member.
	_, reset := e.do("POST", "/api/admin/members/"+id+"/reset", "", nil)
	rcode := reset["code"].(string)
	if st, info := e.status("GET", "/api/invites/"+rcode, "", nil); st != 200 || info["kind"] != "reset" || info["username"] != "taylor" {
		t.Fatalf("reset info: %d %v", st, info)
	}
	st, redeemed := e.status("POST", "/api/join", "", map[string]string{"code": rcode, "password": "purple tractor singing"})
	if st != 200 || redeemed["member"].(map[string]any)["id"] != id || redeemed["username"] != "taylor" {
		t.Fatalf("reset: %d %v", st, redeemed)
	}
	if st, _ := e.status("GET", "/api/members", phone2, nil); st != 401 {
		t.Fatal("reset should sign the previous phone out")
	}
	if st, _ := e.status("POST", "/api/join", "", map[string]string{"code": rcode, "password": "purple tractor singing"}); st != 404 {
		t.Fatal("reset link worked twice")
	}
	st, latest := e.status("POST", "/api/signin", "", map[string]string{"username": "taylor", "password": "purple tractor singing"})
	if st != 200 {
		t.Fatal("new password from reset doesn't work")
	}

	// The dashboard sees sign-in details; other phones don't.
	_, list := e.do("GET", "/api/admin/members", "", nil)
	for _, m := range list["members"].([]any) {
		mm := m.(map[string]any)
		if mm["id"] == id && (mm["username"] != "taylor" || mm["hasPassword"] != true || mm["lastSignIn"] == nil) {
			t.Fatalf("admin view: %v", mm)
		}
	}
	_, pub := e.do("GET", "/api/members", latest["token"].(string), nil)
	for _, m := range pub["members"].([]any) {
		if _, leaked := m.(map[string]any)["username"]; leaked {
			t.Fatal("usernames leaked to other phones")
		}
	}
}

func TestLegacyMemberSetsPassword(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Sam"})
	// An app from before accounts existed joins with just a code.
	_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
	tok := j["token"].(string)
	_, list := e.do("GET", "/api/members", tok, nil)
	if list["settings"].(map[string]any)["hasPassword"] != false {
		t.Fatal("legacy member should have no password")
	}
	// After updating, they choose a username and password; no current password needed.
	if st, out := e.status("POST", "/api/me/password", tok, map[string]string{"username": "sam", "newPassword": "orange bicycle parade"}); st != 200 || out["username"] != "sam" {
		t.Fatalf("set password: %d %v", st, out)
	}
	if st, _ := e.status("POST", "/api/signin", "", map[string]string{"username": "sam", "password": "orange bicycle parade"}); st != 200 {
		t.Fatal("can't sign in with the new password")
	}
}

func TestSignInLockout(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{})
	e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string), "username": "kim", "password": "silver lantern forest"})
	for i := 0; i < maxFailures; i++ {
		e.do("POST", "/api/signin", "", map[string]string{"username": "kim", "password": "guess guess guess"})
	}
	// Locked now, even with the right password.
	st, out := e.status("POST", "/api/signin", "", map[string]string{"username": "kim", "password": "silver lantern forest"})
	if st != http.StatusTooManyRequests || !strings.Contains(out["error"].(string), "minutes") {
		t.Fatalf("expected lockout, got %d %v", st, out)
	}
	// The lock lifts after the lockout time.
	e2 := newLoginLimiter()
	e2.fails["kim"] = &failState{lockedUntil: time.Now().Add(-time.Second)}
	if e2.Locked("kim") != 0 {
		t.Fatal("lock didn't expire")
	}
}

func TestResetPageAndDeletedMember(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Jo"})
	_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string), "username": "jo.r", "password": "teal umbrella morning"})
	if j["member"] == nil {
		t.Fatalf("join failed: %v", j)
	}
	id := j["member"].(map[string]any)["id"].(string)
	_, reset := e.do("POST", "/api/admin/members/"+id+"/reset", "", nil)

	resp, _ := http.Get(e.srv.URL + "/join/" + reset["code"].(string))
	buf := make([]byte, 4096)
	n, _ := resp.Body.Read(buf)
	if !strings.Contains(string(buf[:n]), "Sign back in, Jo") {
		t.Fatalf("reset page: %s", buf[:n])
	}
	// Removing the member cancels their reset link.
	e.do("DELETE", "/api/admin/members/"+id, "", nil)
	if st, _ := e.status("GET", "/api/invites/"+reset["code"].(string), "", nil); st != 404 {
		t.Fatalf("reset link survived member removal: %d", st)
	}
}
