package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Address search for adding fences: "123 Main St" -> coordinates. Phones and
// the dashboard ask this server, which asks a geocoder (by default the free
// OpenStreetMap Nominatim service; GEOCODER_URL can point at a self-hosted
// Nominatim, or be "off"). Calls are limited to one per second and cached, as
// Nominatim's usage policy asks.

type GeoResult struct {
	Name string  `json:"name"`
	Lat  float64 `json:"lat"`
	Lon  float64 `json:"lon"`
}

type geocoder struct {
	url    string
	client *http.Client

	mu       sync.Mutex // one upstream request at a time
	lastCall time.Time
	cache    map[string]geoCacheEntry
}

type geoCacheEntry struct {
	results []GeoResult
	at      time.Time
}

const (
	geoMinGap    = 1100 * time.Millisecond
	geoCacheTTL  = 24 * time.Hour
	geoCacheSize = 500
)

var errGeocoderOff = errors.New("address search is turned off on this server")

func newGeocoder(u string) *geocoder {
	if strings.EqualFold(u, "off") {
		u = ""
	}
	return &geocoder{url: u, client: &http.Client{Timeout: 10 * time.Second}, cache: map[string]geoCacheEntry{}}
}

func (g *geocoder) enabled() bool { return g.url != "" }

// Search looks up a free-text address. lang is the asker's Accept-Language.
func (g *geocoder) Search(q, lang string) ([]GeoResult, error) {
	if !g.enabled() {
		return nil, errGeocoderOff
	}
	q = strings.Join(strings.Fields(q), " ")
	if len([]rune(q)) < 3 {
		return []GeoResult{}, nil
	}
	if len([]rune(q)) > 200 {
		q = string([]rune(q)[:200])
	}
	key := strings.ToLower(q) + "|" + lang

	g.mu.Lock()
	defer g.mu.Unlock()
	if c, ok := g.cache[key]; ok && time.Since(c.at) < geoCacheTTL {
		return c.results, nil
	}
	if wait := geoMinGap - time.Since(g.lastCall); wait > 0 {
		time.Sleep(wait)
	}
	g.lastCall = time.Now()

	u, err := url.Parse(g.url)
	if err != nil {
		return nil, err
	}
	params := u.Query()
	params.Set("q", q)
	params.Set("format", "jsonv2")
	params.Set("limit", "6")
	u.RawQuery = params.Encode()
	req, _ := http.NewRequest(http.MethodGet, u.String(), nil)
	// Nominatim asks every app to identify itself.
	req.Header.Set("User-Agent", "Thereabouts/"+version+" (self-hosted family locator; https://github.com/Spasm0dic/Thereabouts)")
	if lang != "" {
		req.Header.Set("Accept-Language", lang)
	}
	resp, err := g.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("the address service answered " + resp.Status)
	}
	var raw []struct {
		DisplayName string `json:"display_name"`
		Lat         string `json:"lat"`
		Lon         string `json:"lon"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(nil, resp.Body, 1<<20)).Decode(&raw); err != nil {
		return nil, err
	}
	out := []GeoResult{}
	for _, r := range raw {
		lat, err1 := strconv.ParseFloat(r.Lat, 64)
		lon, err2 := strconv.ParseFloat(r.Lon, 64)
		if err1 == nil && err2 == nil {
			out = append(out, GeoResult{Name: r.DisplayName, Lat: lat, Lon: lon})
		}
	}
	if len(g.cache) >= geoCacheSize {
		g.cache = map[string]geoCacheEntry{} // simple: start over
	}
	g.cache[key] = geoCacheEntry{out, time.Now()}
	return out, nil
}

func (s *Server) geocode(w http.ResponseWriter, r *http.Request) {
	results, err := s.geo.Search(r.URL.Query().Get("q"), r.Header.Get("Accept-Language"))
	switch {
	case errors.Is(err, errGeocoderOff):
		writeError(w, http.StatusNotFound, "Address search is turned off on this server")
	case err != nil:
		writeError(w, http.StatusBadGateway, "Address search isn't working right now")
	default:
		writeJSON(w, http.StatusOK, results)
	}
}

func (s *Server) handleGeocode(w http.ResponseWriter, r *http.Request, _ *Member) { s.geocode(w, r) }
func (s *Server) handleAdminGeocode(w http.ResponseWriter, r *http.Request)      { s.geocode(w, r) }
