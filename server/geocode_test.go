package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestGeocodeProxy(t *testing.T) {
	var calls atomic.Int32
	var gotUA, gotQ string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		gotUA, gotQ = r.Header.Get("User-Agent"), r.URL.Query().Get("q")
		fmt.Fprint(w, `[{"display_name":"10 Main St, Springfield","lat":"40.1","lon":"-75.2"},{"display_name":"bad","lat":"x","lon":"1"}]`)
	}))
	defer upstream.Close()

	g := newGeocoder(upstream.URL)
	res, err := g.Search("  10   Main St ", "en")
	if err != nil || len(res) != 1 || res[0].Lat != 40.1 || res[0].Name != "10 Main St, Springfield" {
		t.Fatalf("got %v, %v", res, err)
	}
	if gotQ != "10 Main St" || !strings.HasPrefix(gotUA, "FamLogic/") {
		t.Fatalf("upstream saw q=%q ua=%q", gotQ, gotUA)
	}
	// Cached: no second upstream call.
	g.Search("10 main st", "en")
	if calls.Load() != 1 {
		t.Fatalf("expected a cached answer, upstream called %d times", calls.Load())
	}
	// Rate limited: two different searches are at least ~1 s apart.
	start := time.Now()
	g.Search("first street", "en")
	g.Search("second street", "en")
	if time.Since(start) < time.Second {
		t.Fatalf("upstream called too quickly: %v", time.Since(start))
	}
	// Too short to search.
	if res, _ := g.Search("ab", ""); len(res) != 0 {
		t.Fatal("searched for a 2-letter query")
	}
	// Turned off.
	if _, err := newGeocoder("off").Search("anything here", ""); err != errGeocoderOff {
		t.Fatalf("off geocoder: %v", err)
	}
}

func TestGeocodeOverHTTP(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `[{"display_name":"Somewhere","lat":"1.5","lon":"2.5"}]`)
	}))
	defer upstream.Close()

	// The standard test server has address search off.
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	if st, _ := e.status("GET", "/api/admin/geocode?q=somewhere", "", nil); st != 404 {
		t.Fatalf("search while off: %d", st)
	}
	_, info := e.do("GET", "/api/info", "", nil)
	if strings.Contains(fmt.Sprint(info["features"]), "geocode") {
		t.Fatal("geocode advertised while off")
	}

	// Turned on, pointing at the fake geocoder.
	store, _ := OpenStore(t.TempDir() + "/famlogic.json")
	store.EnsureDefaultCircle("Family")
	srv := httptest.NewServer(NewServer(Config{AdminPassword: "correct horse", GeocoderURL: upstream.URL, InviteTTL: time.Hour}, store, nil).Routes())
	defer srv.Close()
	e2 := &testEnv{t: t, srv: srv}
	e2.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	r, _ := e2.do("GET", "/api/admin/geocode?q=somewhere", "", nil)
	if r.StatusCode != 200 {
		t.Fatalf("admin search: %d", r.StatusCode)
	}
	_, info = e2.do("GET", "/api/info", "", nil)
	if !strings.Contains(fmt.Sprint(info["features"]), "geocode") {
		t.Fatal("geocode not advertised while on")
	}
	_, inv := e2.do("POST", "/api/admin/invites", "", map[string]string{})
	_, j := e2.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
	req, _ := http.NewRequest("GET", srv.URL+"/api/geocode?q=somewhere", nil)
	req.Header.Set("Authorization", "Bearer "+j["token"].(string))
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("phone search: %v %v", err, resp)
	}
}

func TestNearNames(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/reverse" {
			http.NotFound(w, r)
			return
		}
		fmt.Fprint(w, `{"name":"","address":{"road":"Main Street","city":"Springfield"}}`)
	}))
	defer upstream.Close()
	g := newGeocoder(upstream.URL + "/search")
	if n := g.NearName(40.1, -75.2); n != "" {
		t.Fatalf("first ask should queue, got %q", n)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if n := g.NearName(40.1001, -75.2001); n != "" { // same ~100 m spot
			if n != "Main Street, Springfield" {
				t.Fatalf("got %q", n)
			}
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("name never arrived")
}
