package main

// Outside calendars for Datebook: a parent pastes a calendar's link (an .ics or
// webcal:// address, like a school's term dates, a sports club's fixtures or
// public holidays); everyone sees its events, read-only, beside the family's own.
// The server fetches each link every few hours and keeps the last good copy in
// DATA_DIR/feeds/, so a link that's briefly down changes nothing.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/emersion/go-ical"
)

// CalFeed is one outside calendar.
type CalFeed struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	URL     string `json:"url"`
	Color   string `json:"color"`
	AddedBy string `json:"addedBy"`
	Fetched int64  `json:"fetched,omitempty"` // last good fetch (unix ms)
	Error   string `json:"error,omitempty"`   // the last fetch's problem, if any
	Events  int    `json:"events"`
}

const feedEvery = 3 * time.Hour

func (s *Store) Feeds() []CalFeed {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]CalFeed{}, s.d.Feeds...)
}

// updateFeeds changes the list of outside calendars under the store's lock.
func (s *Store) updateFeeds(f func(list []CalFeed) []CalFeed) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.d.Feeds = f(append([]CalFeed{}, s.d.Feeds...))
	return s.saveLocked()
}

func (s *Server) feedPath(id string) string {
	return filepath.Join(filepath.Dir(s.store.path), "feeds", id+".ics")
}

var feedClient = &http.Client{Timeout: 30 * time.Second}

// feedURL tidies a pasted link: webcal:// is https://.
func feedURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if strings.HasPrefix(strings.ToLower(raw), "webcal://") {
		raw = "https://" + raw[len("webcal://"):]
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return "", errors.New("paste the calendar's link (it starts with https:// or webcal://)")
	}
	return u.String(), nil
}

// fetchFeed downloads one calendar, checks it reads, and keeps it.
func (s *Server) fetchFeed(ctx context.Context, f CalFeed) (int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.URL, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", "FamLogic calendar")
	res, err := feedClient.Do(req)
	if err != nil {
		return 0, errors.New("couldn't reach it")
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("it answered %d", res.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, 8<<20))
	if err != nil {
		return 0, errors.New("the download didn't finish")
	}
	cal, err := ical.NewDecoder(bytes.NewReader(b)).Decode()
	if err != nil {
		return 0, errors.New("that link isn't a calendar (.ics)")
	}
	n := 0
	for _, c := range cal.Children {
		if c.Name == ical.CompEvent {
			n++
		}
	}
	p := s.feedPath(f.ID)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return 0, err
	}
	if err := writeAtomic(p, b); err != nil {
		return 0, err
	}
	s.feedCache.forget(f.ID)
	return n, nil
}

// refreshFeed fetches one calendar and records how it went.
func (s *Server) refreshFeed(ctx context.Context, id string) {
	var f *CalFeed
	for _, x := range s.store.Feeds() {
		if x.ID == id {
			x := x
			f = &x
		}
	}
	if f == nil {
		return
	}
	n, err := s.fetchFeed(ctx, *f)
	_ = s.store.updateFeeds(func(list []CalFeed) []CalFeed {
		for i := range list {
			if list[i].ID == id {
				if err != nil {
					list[i].Error = err.Error()
				} else {
					list[i].Error, list[i].Fetched, list[i].Events = "", time.Now().UnixMilli(), n
				}
			}
		}
		return list
	})
	if err != nil {
		log.Printf("calendar %q: %v", f.Name, err)
	}
}

// KeepFeeds refreshes every outside calendar every few hours.
func (s *Server) KeepFeeds(ctx context.Context) {
	t := time.NewTicker(15 * time.Minute)
	defer t.Stop()
	for {
		for _, f := range s.store.Feeds() {
			if time.Since(time.UnixMilli(f.Fetched)) >= feedEvery || (f.Error != "" && time.Since(time.UnixMilli(f.Fetched)) >= time.Hour) {
				s.refreshFeed(ctx, f.ID)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// feedEvents caches each kept calendar's events, read once per download.
type feedEvents struct {
	mu sync.Mutex
	m  map[string][]CalEvent
}

func (c *feedEvents) forget(id string) {
	c.mu.Lock()
	delete(c.m, id)
	c.mu.Unlock()
}

func (s *Server) feedEventsOf(id string) []CalEvent {
	s.feedCache.mu.Lock()
	defer s.feedCache.mu.Unlock()
	if evs, ok := s.feedCache.m[id]; ok {
		return evs
	}
	evs := []CalEvent{}
	if b, err := os.ReadFile(s.feedPath(id)); err == nil {
		if cal, err := ical.NewDecoder(bytes.NewReader(b)).Decode(); err == nil {
			for _, c := range cal.Children {
				// A changed single showing of a repeat (RECURRENCE-ID) is skipped: the repeat covers it.
				if c.Name != ical.CompEvent || c.Props.Get(ical.PropRecurrenceID) != nil {
					continue
				}
				if ev, err := readVEvent(c); err == nil {
					ev.ID = "feed:" + id
					evs = append(evs, ev)
				}
			}
		}
	}
	if s.feedCache.m == nil {
		s.feedCache.m = map[string][]CalEvent{}
	}
	s.feedCache.m[id] = evs
	return evs
}

// feedOccurrences are the outside calendars' showings between from and to.
func (s *Server) feedOccurrences(from, to time.Time) []Occurrence {
	out := []Occurrence{}
	for _, f := range s.store.Feeds() {
		for _, ev := range s.feedEventsOf(f.ID) {
			for _, o := range expandEvent(ev, from, to) {
				o.Calendar, o.Color = f.Name, f.Color
				out = append(out, o)
			}
		}
	}
	return out
}

// ---- HTTP

func mayManageFeeds(me *Member) bool { return me.ID == "admin" || me.role() == RoleParent }

// GET /api/calendar/feeds
func (s *Server) handleFeeds(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"feeds": s.store.Feeds(), "canManage": mayManageFeeds(me)})
}

// POST /api/calendar/feeds {name, url, color}: a parent adds an outside calendar.
func (s *Server) handleAddFeed(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	if !mayManageFeeds(me) {
		writeError(w, http.StatusForbidden, "parents add outside calendars")
		return
	}
	var body struct{ Name, URL, Color string }
	if !readJSON(w, r, &body) {
		return
	}
	link, err := feedURL(body.URL)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	name := strings.TrimSpace(body.Name)
	if name == "" || utf8.RuneCountInString(name) > 40 {
		writeError(w, http.StatusBadRequest, "give it a short name, like School or Holidays")
		return
	}
	color := strings.TrimSpace(body.Color)
	if !colorRe.MatchString(color) {
		color = "#3a7bd5"
	}
	f := CalFeed{ID: "f" + randomHex(5), Name: name, URL: link, Color: color, AddedBy: me.ID}
	// Fetch it now, so a wrong link is caught while the parent is still here.
	n, err := s.fetchFeed(r.Context(), f)
	if err != nil {
		writeError(w, http.StatusBadRequest, "couldn't read that calendar: "+err.Error())
		return
	}
	f.Fetched, f.Events = time.Now().UnixMilli(), n
	if err := s.store.updateFeeds(func(list []CalFeed) []CalFeed { return append(list, f) }); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, f)
}

// DELETE /api/calendar/feeds/{id}
func (s *Server) handleDeleteFeed(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	if !mayManageFeeds(me) {
		writeError(w, http.StatusForbidden, "parents remove outside calendars")
		return
	}
	id := r.PathValue("id")
	_ = s.store.updateFeeds(func(list []CalFeed) []CalFeed {
		out := list[:0]
		for _, f := range list {
			if f.ID != id {
				out = append(out, f)
			}
		}
		return out
	})
	os.Remove(s.feedPath(id))
	s.feedCache.forget(id)
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/calendar/feeds/{id}/refresh: fetch it again now.
func (s *Server) handleRefreshFeed(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	if !mayManageFeeds(me) {
		writeError(w, http.StatusForbidden, "parents manage outside calendars")
		return
	}
	s.refreshFeed(r.Context(), r.PathValue("id"))
	feeds := s.store.Feeds()
	sort.Slice(feeds, func(i, j int) bool { return feeds[i].Name < feeds[j].Name })
	writeJSON(w, http.StatusOK, map[string]any{"feeds": feeds, "canManage": true})
}
