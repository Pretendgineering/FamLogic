package main

// The family feed: a lasting record of what happened, day by day ("Sam got to
// School", "Mia unloaded the dishwasher", "Dad finished a trip"). Alerts (events)
// only live for two days; the timeline keeps the ones worth looking back on for
// 60 days, one file per day (DATA_DIR/timeline/2026-11-24.jsonl, one JSON line per
// item), so it's easy to read and back up. Who sees what follows the same rules as
// alerts: arrivals only to the groups someone shares their location with, and so on.

import (
	"bufio"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	EventChore     = "chore"      // to one person: a chore for them (Kind "new"), or a parent: one done (Kind "done")
	EventChoreDone = "chore_done" // the timeline: someone did a chore
	timelineDays   = 60
)

// Kinds of event that go on the timeline (when they're not for one person only).
var timelineTypes = map[string]bool{
	EventArrived: true, EventLeft: true, EventCheckIn: true, EventSOS: true, EventSOSEnd: true,
	EventTrip: true, EventDriveStart: true, EventChoreDone: true,
}

func (s *Store) timelineDir() string { return filepath.Join(filepath.Dir(s.path), "timeline") }

// timelineLocked adds an item to today's file (the caller holds the lock).
func (s *Store) timelineLocked(e Event) {
	if e.To != "" || !timelineTypes[e.Type] {
		return
	}
	if e.Time == 0 {
		e.Time = time.Now().UnixMilli()
	}
	dir := s.timelineDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	day := time.UnixMilli(e.Time).UTC().Format("2006-01-02")
	path := filepath.Join(dir, day+".jsonl")
	if _, err := os.Stat(path); os.IsNotExist(err) {
		s.pruneTimeline() // a new day: drop the oldest
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	b, _ := json.Marshal(e)
	f.Write(append(b, '\n'))
}

// AddTimeline records something that isn't an alert (a chore done).
func (s *Store) AddTimeline(e Event) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.timelineLocked(e)
}

func (s *Store) pruneTimeline() {
	entries, _ := os.ReadDir(s.timelineDir())
	cutoff := time.Now().AddDate(0, 0, -timelineDays).UTC().Format("2006-01-02")
	for _, e := range entries {
		if name := strings.TrimSuffix(e.Name(), ".jsonl"); name < cutoff {
			os.Remove(filepath.Join(s.timelineDir(), e.Name()))
		}
	}
}

// Timeline returns what the viewer may see between two times (unix ms), newest first.
func (s *Store) Timeline(viewerID string, from, to int64) []Event {
	s.mu.RLock()
	defer s.mu.RUnlock()
	viewer, ok := s.d.Members[viewerID]
	out := []Event{}
	if !ok {
		return out
	}
	for d := time.UnixMilli(from).UTC().Truncate(24 * time.Hour); !d.After(time.UnixMilli(to).UTC()); d = d.AddDate(0, 0, 1) {
		f, err := os.Open(filepath.Join(s.timelineDir(), d.Format("2006-01-02")+".jsonl"))
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64*1024), 1<<20)
		for sc.Scan() {
			var e Event
			if json.Unmarshal(sc.Bytes(), &e) != nil || e.Time < from || e.Time > to {
				continue
			}
			// Your own things, and others' as alerts allow (and only while they're still in the family).
			if _, still := s.d.Members[e.MemberID]; !still {
				continue
			}
			if e.MemberID == viewer.ID || s.eventVisibleLocked(e, viewer) || (e.Type == EventChoreDone && len(sharedCircles(s.d.Members[e.MemberID], viewer)) > 0) {
				out = append(out, e)
			}
		}
		f.Close()
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Time > out[j].Time })
	if len(out) > 500 {
		out = out[:500]
	}
	return out
}

// GET /api/feed?from=<ms>&to=<ms>: the family feed for a time range (default: the last day).
func (s *Server) handleFeed(w http.ResponseWriter, r *http.Request, me *Member) {
	now := time.Now().UnixMilli()
	to, err := strconv.ParseInt(r.URL.Query().Get("to"), 10, 64)
	if err != nil || to <= 0 {
		to = now
	}
	from, err := strconv.ParseInt(r.URL.Query().Get("from"), 10, 64)
	if err != nil || from <= 0 {
		from = to - 24*3600*1000
	}
	if to-from > 8*24*3600*1000 {
		from = to - 8*24*3600*1000 // a week at a time is plenty
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": s.store.Timeline(me.ID, from, to)})
}
