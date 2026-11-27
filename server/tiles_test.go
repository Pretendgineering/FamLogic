package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestWeatherAndFridge(t *testing.T) {
	var asked string
	om := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = r.URL.RawQuery
		now := time.Now().Unix()
		fmt.Fprintf(w, `{"current":{"temperature_2m":12.5,"weather_code":61},
			"hourly":{"time":[%d,%d,%d],"temperature_2m":[12,13,14],"weather_code":[61,3,0]},
			"daily":{"temperature_2m_max":[15],"temperature_2m_min":[7],"precipitation_probability_max":[80]}}`, now+60, now+3660, now+7260)
	}))
	defer om.Close()
	old := weatherURL
	weatherURL = om.URL
	defer func() { weatherURL = old }()

	f := newChatFamily(t)
	e := f.e
	if st, _ := e.status("GET", "/api/weather", f.mom, nil); st != 404 {
		t.Fatalf("no home yet: %d", st)
	}
	family := e.app.store.Circles()[0].ID
	if st, p := e.status("POST", "/api/places", f.mom, map[string]any{"circleId": family, "name": "Home", "lat": 51.501234, "lon": -0.141234, "radius": 100}); st != 200 {
		t.Fatalf("home: %d %v", st, p)
	}
	st, wx := e.status("GET", "/api/weather", f.mom, nil)
	if st != 200 || wx["text"] != "Rain" || wx["high"].(float64) != 15 || wx["rain"].(float64) != 80 {
		t.Fatalf("weather: %d %v", st, wx)
	}
	// Only the rounded position leaves home.
	if !strings.Contains(asked, "latitude=51.50") || strings.Contains(asked, "51.5012") {
		t.Fatalf("sent: %s", asked)
	}

	if st, n := e.status("PUT", "/api/fridge", f.kid, map[string]any{"text": "Back at 6, pizza in the fridge"}); st != 200 || n["by"] != "Kid" {
		t.Fatalf("note: %d %v", st, n)
	}
	if _, n := e.status("GET", "/api/fridge", f.dad, nil); n["text"] != "Back at 6, pizza in the fridge" {
		t.Fatalf("read note: %v", n)
	}
	if st, _ := e.status("PUT", "/api/fridge", f.mom, map[string]any{"text": strings.Repeat("x", 301)}); st != 400 {
		t.Fatalf("too long: %d", st)
	}
}
