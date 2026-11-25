package main

// Undo for Steward: while Steward answers, every file it changes (notes, chores,
// events, lists, meals, recipes...) is remembered as it was before. The person can
// then undo that answer: each file goes back to how it was, unless someone has
// changed it again since (then it's left alone and named).
//
// Only the latest answer can be undone. Steward turns run one at a time, and the
// journal only listens while one of Steward's tools is running (milliseconds, not
// the seconds spent waiting for the model), so other people's changes aren't caught.

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// fileJournal records the old contents of files written while it's on.
type fileJournal struct {
	mu   sync.Mutex
	on   bool // a turn is being journaled
	live bool // ...and one of its tools is running now
	skip string             // a folder never journaled (Steward's own history)
	old  map[string]*string // path → contents before (nil: the file didn't exist)
}

var journal fileJournal

// stewardTurns makes Steward answers run one at a time, so a journal belongs to one answer.
var stewardTurns sync.Mutex

// journalBefore notes a file's contents before it's written or removed (once per turn).
func journalBefore(path string) {
	journal.mu.Lock()
	defer journal.mu.Unlock()
	if !journal.on || !journal.live || (journal.skip != "" && strings.HasPrefix(path, journal.skip)) {
		return
	}
	if _, seen := journal.old[path]; seen {
		return
	}
	if b, err := os.ReadFile(path); err == nil {
		s := string(b)
		journal.old[path] = &s
	} else {
		journal.old[path] = nil
	}
}

func journalStart(skip string) {
	journal.mu.Lock()
	journal.on, journal.skip, journal.old = true, skip, map[string]*string{}
	journal.mu.Unlock()
}

func journalStop() map[string]*string {
	journal.mu.Lock()
	defer journal.mu.Unlock()
	out := journal.old
	journal.on, journal.live, journal.old = false, false, nil
	return out
}

// journalLive turns listening on while a Steward tool runs, and off again after.
func journalLive(on bool) {
	journal.mu.Lock()
	journal.live = on && journal.on
	journal.mu.Unlock()
}

// removeJournaled removes a file, remembering it first.
func removeJournaled(path string) {
	journalBefore(path)
	os.Remove(path)
}

func fileHash(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return "" // gone
	}
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// stewardUndo is what the latest answer changed, saved next to the conversation.
type stewardUndo struct {
	Time  int64            `json:"time"`
	Files []stewardUndoFile `json:"files"`
}

type stewardUndoFile struct {
	Path   string  `json:"path"`
	Before *string `json:"before"` // nil: didn't exist
	After  string  `json:"after"`  // hash after the answer ("" if removed)
}

func (s *Server) stewardUndoPath(id string) string {
	return filepath.Join(filepath.Dir(s.store.path), "steward", id+".undo.json")
}

func (s *Server) loadStewardUndo(id string) *stewardUndo {
	b, err := os.ReadFile(s.stewardUndoPath(id))
	if err != nil {
		return nil
	}
	var u stewardUndo
	if json.Unmarshal(b, &u) != nil || len(u.Files) == 0 {
		return nil
	}
	return &u
}

// saveStewardUndo keeps what an answer changed (or forgets the last one if it changed nothing).
func (s *Server) saveStewardUndo(id string, changed map[string]*string) *stewardUndo {
	p := s.stewardUndoPath(id)
	if len(changed) == 0 {
		os.Remove(p)
		return nil
	}
	u := stewardUndo{Time: time.Now().UnixMilli()}
	for path, before := range changed {
		after := fileHash(path)
		if before != nil {
			h := sha256.Sum256([]byte(*before))
			if hex.EncodeToString(h[:]) == after {
				continue // written but not changed
			}
		} else if after == "" {
			continue
		}
		u.Files = append(u.Files, stewardUndoFile{Path: path, Before: before, After: after})
	}
	if len(u.Files) == 0 {
		os.Remove(p)
		return nil
	}
	sort.Slice(u.Files, func(i, j int) bool { return u.Files[i].Path < u.Files[j].Path })
	b, _ := json.Marshal(u)
	_ = os.MkdirAll(filepath.Dir(p), 0o700)
	_ = writeAtomic(p, b)
	return &u
}

// changedNames says what an answer changed, for people: "Shopping", "2026-11-26 Dentist".
func changedNames(u *stewardUndo) []string {
	if u == nil {
		return []string{}
	}
	out := []string{}
	seen := map[string]bool{}
	for _, f := range u.Files {
		n := strings.TrimSuffix(filepath.Base(f.Path), ".md")
		if strings.HasSuffix(n, ".ics") || seen[n] {
			continue // calendar apps' copies: the event itself is listed
		}
		seen[n] = true
		out = append(out, n)
	}
	return out
}

// POST /api/steward/undo: put back what the latest answer changed.
func (s *Server) handleStewardUndo(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.mayTalkToSteward(me) {
		writeError(w, http.StatusForbidden, "Steward isn't set up for you")
		return
	}
	stewardTurns.Lock()
	defer stewardTurns.Unlock()
	u := s.loadStewardUndo(me.ID)
	if u == nil {
		writeError(w, http.StatusConflict, "there's nothing to undo")
		return
	}
	var done, kept []string
	for _, f := range u.Files {
		name := strings.TrimSuffix(filepath.Base(f.Path), ".md")
		if fileHash(f.Path) != f.After {
			kept = append(kept, name) // changed again since: leave it
			continue
		}
		var err error
		if f.Before == nil {
			err = os.Remove(f.Path)
		} else {
			err = writeAtomic(f.Path, []byte(*f.Before))
		}
		if err != nil && !os.IsNotExist(err) {
			kept = append(kept, name)
			continue
		}
		if !strings.HasSuffix(name, ".ics") {
			done = append(done, name)
		}
	}
	os.Remove(s.stewardUndoPath(me.ID))
	text := "Undone."
	if len(done) > 0 {
		text = "Undone: " + strings.Join(done, ", ") + "."
	}
	if len(kept) > 0 {
		text += " Left as they are (changed since): " + strings.Join(kept, ", ") + "."
	}
	hist := append(s.stewardHistory(me.ID), StewardMsg{Role: "assistant", Text: text, Time: time.Now().UnixMilli()})
	s.saveStewardHistory(me.ID, hist)
	writeJSON(w, http.StatusOK, map[string]any{"messages": s.stewardHistory(me.ID), "reply": text, "undone": done, "kept": kept})
}
