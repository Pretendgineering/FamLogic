package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type testEnv struct {
	t      *testing.T
	srv    *httptest.Server
	cookie *http.Cookie
	path   string // the data file
}

func (e *testEnv) dataFile() string { return e.path }

func newEnv(t *testing.T) *testEnv {
	t.Helper()
	store, err := OpenStore(filepath.Join(t.TempDir(), "data", "famlogic.json"))
	if err != nil {
		t.Fatal(err)
	}
	store.EnsureDefaultCircle("Test Family") // as the server does at start-up
	static, _ := fs.Sub(webFS, "web")
	cfg := Config{AdminPassword: "correct horse", FamilyName: "Test Family", TileURL: "https://tiles/{z}/{x}/{y}.png", InviteTTL: time.Hour,
		VaultDir: filepath.Join(filepath.Dir(store.path), "vault")}
	srv := httptest.NewServer(NewServer(cfg, store, static).Routes())
	t.Cleanup(srv.Close)
	return &testEnv{t: t, srv: srv, path: store.path}
}

func (e *testEnv) do(method, path, auth string, body any) (*http.Response, map[string]any) {
	e.t.Helper()
	var buf bytes.Buffer
	if body != nil {
		json.NewEncoder(&buf).Encode(body)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, &buf)
	if auth != "" {
		req.Header.Set("Authorization", "Bearer "+auth)
	}
	if e.cookie != nil {
		req.AddCookie(e.cookie)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	for _, c := range resp.Cookies() {
		if c.Name == adminCookie {
			e.cookie = c
		}
	}
	return resp, out
}

func TestFullFlow(t *testing.T) {
	e := newEnv(t)

	// Admin endpoints need login.
	if r, _ := e.do("POST", "/api/admin/invites", "", map[string]string{}); r.StatusCode != 401 {
		t.Fatalf("expected 401 before login, got %d", r.StatusCode)
	}
	if r, _ := e.do("POST", "/api/admin/login", "", map[string]string{"password": "nope"}); r.StatusCode != 401 {
		t.Fatalf("wrong password accepted: %d", r.StatusCode)
	}
	if r, _ := e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"}); r.StatusCode != 204 {
		t.Fatalf("login failed: %d", r.StatusCode)
	}

	// Create an invite and join with it (code typed in lower case, no dash).
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Alice"})
	code := inv["code"].(string)
	if !strings.HasSuffix(inv["link"].(string), "/join/"+code) {
		t.Fatalf("bad link %v", inv["link"])
	}
	typed := strings.ToLower(strings.ReplaceAll(code, "-", ""))
	r, joined := e.do("POST", "/api/join", "", map[string]string{"code": typed})
	if r.StatusCode != 200 {
		t.Fatalf("join failed: %d %v", r.StatusCode, joined)
	}
	alice := joined["token"].(string)
	if joined["member"].(map[string]any)["name"] != "Alice" {
		t.Fatalf("invite name not used: %v", joined)
	}

	// Invites are single use.
	if r, _ := e.do("POST", "/api/join", "", map[string]string{"code": code}); r.StatusCode != 404 {
		t.Fatalf("invite reused: %d", r.StatusCode)
	}

	// Second member.
	_, inv2 := e.do("POST", "/api/admin/invites", "", map[string]string{})
	_, joined2 := e.do("POST", "/api/join", "", map[string]string{"code": inv2["code"].(string), "name": "  Bob  "})
	bob := joined2["token"].(string)

	// Report a location and see it from the other phone.
	if r, _ := e.do("POST", "/api/location", alice, map[string]any{"lat": 51.5, "lon": -0.12, "acc": 12, "battery": 80, "time": time.Now().UnixMilli()}); r.StatusCode != 204 {
		t.Fatalf("location failed: %d", r.StatusCode)
	}
	if r, _ := e.do("POST", "/api/location", alice, map[string]any{"lat": 91, "lon": 0}); r.StatusCode != 400 {
		t.Fatalf("bad lat accepted: %d", r.StatusCode)
	}
	_, list := e.do("GET", "/api/members", bob, nil)
	members := list["members"].([]any)
	if len(members) != 2 {
		t.Fatalf("want 2 members, got %v", members)
	}
	a := members[0].(map[string]any)
	if a["name"] != "Alice" || a["loc"].(map[string]any)["lat"] != 51.5 {
		t.Fatalf("unexpected member %v", a)
	}
	if _, leaked := a["tokenHash"]; leaked {
		t.Fatal("token hash leaked to clients")
	}
	if list["server"].(map[string]any)["tileUrl"] != "https://tiles/{z}/{x}/{y}.png" {
		t.Fatalf("server info missing: %v", list["server"])
	}

	// Pausing hides the location, and fixes sent while paused are dropped.
	e.do("PATCH", "/api/me", alice, map[string]any{"paused": true})
	e.do("POST", "/api/location", alice, map[string]any{"lat": 10, "lon": 10})
	_, list = e.do("GET", "/api/members", bob, nil)
	a = list["members"].([]any)[0].(map[string]any)
	if a["paused"] != true || a["loc"] != nil {
		t.Fatalf("paused member still shows location: %v", a)
	}
	e.do("PATCH", "/api/me", alice, map[string]any{"paused": false})
	e.do("POST", "/api/location", alice, map[string]any{"lat": 10, "lon": 10})
	_, list = e.do("GET", "/api/members", bob, nil)
	if list["members"].([]any)[0].(map[string]any)["loc"] == nil {
		t.Fatal("location not shown after resuming")
	}

	// Admin removes Bob; his token stops working.
	bobID := joined2["member"].(map[string]any)["id"].(string)
	if r, _ := e.do("DELETE", "/api/admin/members/"+bobID, "", nil); r.StatusCode != 204 {
		t.Fatalf("delete failed: %d", r.StatusCode)
	}
	if r, _ := e.do("GET", "/api/members", bob, nil); r.StatusCode != 401 {
		t.Fatalf("removed member still authorised: %d", r.StatusCode)
	}

	// Join page renders with an app link.
	resp, err := http.Get(e.srv.URL + "/join/ABCD-EFGH")
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("join page: %v %v", err, resp)
	}
}

func TestStorePersists(t *testing.T) {
	path := filepath.Join(t.TempDir(), "famlogic.json")
	s, _ := OpenStore(path)
	inv, _ := s.CreateInvite("Carol", "", time.Hour)
	m, token, err := s.Join(inv.Code, "", "", "")
	if err != nil {
		t.Fatal(err)
	}
	s.UpdateLocation(m.ID, Location{Lat: 1, Lon: 2, Time: 100})
	s.UpdateLocation(m.ID, Location{Lat: 9, Lon: 9, Time: 50}) // older, ignored

	s2, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := s2.MemberByToken(token)
	if !ok || got.Name != "Carol" || got.Loc.Lat != 1 {
		t.Fatalf("not persisted correctly: %+v", got)
	}
	if s2.Secret() != s.Secret() {
		t.Fatal("secret changed across restarts; admins would be logged out")
	}
}

func TestAlertsOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	join := func(name string) string {
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name})
		_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
		return j["token"].(string)
	}
	alex, bob := join("Alex"), join("Bob")

	// Bob's phone has no cursor yet: it gets one, and no backlog.
	_, list := e.do("GET", "/api/members?since=-1", bob, nil)
	cursor := int64(list["cursor"].(float64))
	if evs := list["events"].([]any); len(evs) != 0 {
		t.Fatalf("fresh phone got backlog: %v", evs)
	}
	if list["settings"].(map[string]any)["shareLowBattery"] != true {
		t.Fatalf("shareLowBattery should default to on: %v", list["settings"])
	}

	// Old-style request without ?since still works and gets 204.
	if r, _ := e.do("POST", "/api/location", alex, map[string]any{"lat": 1, "lon": 1, "battery": 50}); r.StatusCode != 204 {
		t.Fatalf("location without since: %d", r.StatusCode)
	}
	e.do("POST", "/api/location", alex, map[string]any{"lat": 1, "lon": 1, "battery": 14, "time": time.Now().UnixMilli()})

	// Bob picks the alert up in the reply to his own location report.
	r, resp := e.do("POST", fmt.Sprintf("/api/location?since=%d", cursor), bob, map[string]any{"lat": 2, "lon": 2, "battery": 90})
	if r.StatusCode != 200 {
		t.Fatalf("location with since: %d", r.StatusCode)
	}
	evs := resp["events"].([]any)
	if len(evs) != 1 {
		t.Fatalf("want 1 alert, got %v", evs)
	}
	ev := evs[0].(map[string]any)
	if ev["type"] != "battery_low" || ev["name"] != "Alex" || ev["battery"] != float64(14) || ev["level"] != float64(15) {
		t.Fatalf("unexpected alert %v", ev)
	}

	// Alex switches the alerts off from the app.
	e.do("POST", "/api/me", alex, map[string]any{"shareLowBattery": false})
	_, list = e.do("GET", "/api/members?since=-1", alex, nil)
	if list["settings"].(map[string]any)["shareLowBattery"] != false {
		t.Fatalf("switch not saved: %v", list["settings"])
	}
}

func TestServerAnnouncesFeatures(t *testing.T) {
	e := newEnv(t)
	_, info := e.do("GET", "/api/info", "", nil)
	got := fmt.Sprint(info["features"])
	for _, f := range []string{"sos", "checkin", "circles", "places", "trails"} {
		if !strings.Contains(got, f) {
			t.Fatalf("features %s missing %q", got, f)
		}
	}
}
