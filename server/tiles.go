package main

// What a few home-screen tiles need from the server: the weather at home (from
// Open-Meteo, free and without an account) and the family's sticky note.
//
// Weather sends Open-Meteo only the home fence's position, rounded to two decimal
// places (about a kilometre), and keeps the answer for 30 minutes so a family full
// of phones asks once.

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// ---- weather

type weatherNow struct {
	Temp    float64     `json:"temp"`
	Code    int         `json:"code"` // WMO weather code
	Text    string      `json:"text"` // "Light rain"
	High    float64     `json:"high"`
	Low     float64     `json:"low"`
	Rain    int         `json:"rain"` // chance of rain today, %
	Unit    string      `json:"unit"` // °C or °F
	Hours   []weatherHr `json:"hours"`
	Fetched int64       `json:"fetched"`
}

type weatherHr struct {
	Time int64   `json:"time"`
	Temp float64 `json:"temp"`
	Code int     `json:"code"`
}

var (
	weatherCache   = map[string]weatherNow{}
	weatherCacheMu sync.Mutex
	weatherClient  = &http.Client{Timeout: 15 * time.Second}
	// Open-Meteo's address (a variable so tests can point it elsewhere).
	weatherURL = "https://api.open-meteo.com/v1/forecast"
)

// weatherWords turns a WMO code into a few words.
func weatherWords(code int) string {
	switch {
	case code == 0:
		return "Clear"
	case code <= 2:
		return "Partly cloudy"
	case code == 3:
		return "Cloudy"
	case code == 45 || code == 48:
		return "Fog"
	case code >= 51 && code <= 57:
		return "Drizzle"
	case code >= 61 && code <= 67, code >= 80 && code <= 82:
		return "Rain"
	case code >= 71 && code <= 77, code == 85 || code == 86:
		return "Snow"
	case code >= 95:
		return "Thunderstorms"
	}
	return "Mixed"
}

// GET /api/weather?unit=c|f: the weather at home now and for the next hours.
func (s *Server) handleWeather(w http.ResponseWriter, r *http.Request, me *Member) {
	lat, lon, ok := s.homeSpot()
	if !ok {
		writeError(w, http.StatusNotFound, "add a fence called Home first, so FamLogic knows where home is")
		return
	}
	unit := "celsius"
	if strings.EqualFold(r.URL.Query().Get("unit"), "f") {
		unit = "fahrenheit"
	}
	// About a kilometre: enough for the weather, without the house's exact spot.
	lat, lon = math.Round(lat*100)/100, math.Round(lon*100)/100
	key := fmt.Sprintf("%.2f,%.2f,%s", lat, lon, unit)
	weatherCacheMu.Lock()
	cached, have := weatherCache[key]
	weatherCacheMu.Unlock()
	if have && time.Since(time.UnixMilli(cached.Fetched)) < 30*time.Minute {
		writeJSON(w, http.StatusOK, cached)
		return
	}
	now, err := fetchWeather(r.Context(), lat, lon, unit)
	if err != nil {
		if have { // an older answer beats none
			writeJSON(w, http.StatusOK, cached)
			return
		}
		writeError(w, http.StatusBadGateway, "couldn't get the weather: "+err.Error())
		return
	}
	weatherCacheMu.Lock()
	weatherCache[key] = now
	weatherCacheMu.Unlock()
	writeJSON(w, http.StatusOK, now)
}

func fetchWeather(ctx context.Context, lat, lon float64, unit string) (weatherNow, error) {
	u := fmt.Sprintf("%s?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code"+
		"&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=2&timezone=auto&timeformat=unixtime&temperature_unit=%s",
		weatherURL, lat, lon, unit)
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	req.Header.Set("User-Agent", "FamLogic")
	res, err := weatherClient.Do(req)
	if err != nil {
		return weatherNow{}, err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return weatherNow{}, fmt.Errorf("Open-Meteo answered %d", res.StatusCode)
	}
	var out struct {
		Current struct {
			Temp float64 `json:"temperature_2m"`
			Code int     `json:"weather_code"`
		} `json:"current"`
		Hourly struct {
			Time []int64   `json:"time"`
			Temp []float64 `json:"temperature_2m"`
			Code []int     `json:"weather_code"`
		} `json:"hourly"`
		Daily struct {
			Max  []float64 `json:"temperature_2m_max"`
			Min  []float64 `json:"temperature_2m_min"`
			Rain []float64 `json:"precipitation_probability_max"`
		} `json:"daily"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return weatherNow{}, fmt.Errorf("Open-Meteo's answer didn't make sense")
	}
	n := weatherNow{Temp: out.Current.Temp, Code: out.Current.Code, Text: weatherWords(out.Current.Code), Unit: "°C", Fetched: time.Now().UnixMilli()}
	if unit == "fahrenheit" {
		n.Unit = "°F"
	}
	if len(out.Daily.Max) > 0 {
		n.High, n.Low = out.Daily.Max[0], out.Daily.Min[0]
	}
	if len(out.Daily.Rain) > 0 {
		n.Rain = int(out.Daily.Rain[0])
	}
	// The next 12 hours, every 2.
	nowS := time.Now().Unix()
	for i, t := range out.Hourly.Time {
		if t < nowS || i >= len(out.Hourly.Temp) || i >= len(out.Hourly.Code) {
			continue
		}
		if len(n.Hours) < 6 && (len(n.Hours) == 0 || t-n.Hours[len(n.Hours)-1].Time/1000 >= 7200) {
			n.Hours = append(n.Hours, weatherHr{Time: t * 1000, Temp: out.Hourly.Temp[i], Code: out.Hourly.Code[i]})
		}
	}
	return n, nil
}

// ---- the sticky note on the fridge

// FridgeNote is the family's sticky note: one short message anyone can change.
type FridgeNote struct {
	Text string `json:"text"`
	By   string `json:"by,omitempty"` // who wrote it (name)
	Time int64  `json:"time,omitempty"`
}

func (s *Store) Fridge() FridgeNote {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.d.Fridge == nil {
		return FridgeNote{}
	}
	return *s.d.Fridge
}

func (s *Store) SetFridge(n FridgeNote) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.d.Fridge = &n
	return s.saveLocked()
}

// GET /api/fridge: the sticky note. PUT {text}: change it (anyone but a TV; "" clears it).
func (s *Server) handleFridge(w http.ResponseWriter, r *http.Request, me *Member) {
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, s.store.Fridge())
		return
	}
	if me.Display || me.role() == RoleDependent {
		writeError(w, http.StatusForbidden, "the TV can show the note, not change it")
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.TrimSpace(body.Text)
	if utf8.RuneCountInString(text) > 300 {
		writeError(w, http.StatusBadRequest, "a short note: 300 characters at most")
		return
	}
	n := FridgeNote{}
	if text != "" {
		n = FridgeNote{Text: text, By: me.Name, Time: time.Now().UnixMilli()}
	}
	if err := s.store.SetFridge(n); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, n)
}
