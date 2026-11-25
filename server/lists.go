package main

// Docket: shared lists (shopping, packing) and to-dos, as Markdown notes in
// the vault (Lists/ and To-dos/), so the app, the console, Obsidian and AI agents
// all see the same thing. A note is front matter (type: list or todos, famlogic_id,
// who) and task lines, optionally under "## Section" headings:
//
//	- [ ] Milk
//	- [ ] Pack PE kit @Sam #school 📅 2026-12-03
//	- [x] Eggs ✅ 2026-11-24
//
// Items are found by an id made from their text (and how many times the same text
// came before), so a note edited elsewhere still lines up.

import (
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"
)

var errNoList = errors.New("no such list")

type ListItem struct {
	ID      string `json:"id"`
	Text    string `json:"text"` // without the @who, 📅 and ✅ marks
	Done    bool   `json:"done"`
	Who     string `json:"who,omitempty"` // member id, from @Name
	Due     string `json:"due,omitempty"`
	DoneOn  string `json:"doneOn,omitempty"`
	Section string `json:"section,omitempty"`
}

type List struct {
	ID    string   `json:"id"`
	Title string   `json:"title"`
	Kind  string   `json:"kind"`           // list or todos
	Who   []string `json:"who"`            // member ids; empty = everyone's
	When  string   `json:"when,omitempty"` // a time of day it matters
	// Routines: when it comes round (HH:MM) and on which days (mon…sun; none: every day).
	Starts string     `json:"starts,omitempty"`
	Days   []string   `json:"days,omitempty"`
	Items  []ListItem `json:"items"`
	File   string     `json:"file"`
}

type lists struct {
	root    string
	folders map[string]string // note type → folder
	mu      sync.Mutex
}

func newLists(v *Vault) *lists {
	if v == nil {
		return nil
	}
	return &lists{root: v.dir, folders: map[string]string{"list": "Lists", "todos": "To-dos"}}
}

// newRoutines: Rhythms' routines are checklists too (Routines/, type routine).
func newRoutines(v *Vault) *lists {
	if v == nil {
		return nil
	}
	return &lists{root: v.dir, folders: map[string]string{"routine": "Routines"}}
}

var (
	taskLineRe = regexp.MustCompile(`^(\s*)- \[( |x|X)\] (.*)$`)
	dueMarkRe  = regexp.MustCompile(`\s*📅\s*(\d{4}-\d{2}-\d{2})`)
	doneMarkRe = regexp.MustCompile(`\s*✅\s*(\d{4}-\d{2}-\d{2})`)
	whoMarkRe  = regexp.MustCompile(`\s@([\p{L}\p{N}_\-]+)`)
)

func (l *lists) dirs() []string {
	seen := map[string]bool{}
	out := []string{}
	for _, t := range []string{"list", "todos", "routine"} {
		if f, ok := l.folders[t]; ok && !seen[f] {
			seen[f] = true
			out = append(out, filepath.Join(l.root, f))
		}
	}
	return out
}

// all reads every list and to-do note.
func (l *lists) all(members []Member) []List {
	out := []List{}
	for _, dir := range l.dirs() {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
				continue
			}
			n, err := readNote(filepath.Join(dir, e.Name()))
			if err != nil {
				continue
			}
			t, _ := n.field("type")
			if _, ok := l.folders[t]; !ok {
				continue
			}
			if a, _ := n.field("archived"); a == "true" {
				continue
			}
			id, _ := n.field("famlogic_id")
			if id == "" {
				id = "l" + randomHex(5)
				noteWriteFront(n, [][2]string{{"famlogic_id", id}})
			}
			out = append(out, l.parse(n, id, t, members))
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Kind != out[j].Kind {
			return out[i].Kind == "list"
		}
		return strings.ToLower(out[i].Title) < strings.ToLower(out[j].Title)
	})
	return out
}

func (l *lists) parse(n *note, id, kind string, members []Member) List {
	ls := List{ID: id, Kind: kind, Items: []ListItem{}, Who: []string{}, File: filepath.Base(n.path)}
	ls.Title = strings.TrimSuffix(filepath.Base(n.path), ".md")
	_, who := n.field("who")
	for _, w := range who {
		if m := memberByName(members, linkName(w)); m != nil {
			ls.Who = append(ls.Who, m.ID)
		}
	}
	ls.When, _ = n.field("when")
	ls.Starts, _ = n.field("starts")
	_, ls.Days = n.field("days")
	section := ""
	seen := map[string]int{}
	for _, line := range strings.Split(n.body, "\n") {
		if h, ok := strings.CutPrefix(line, "# "); ok && ls.Title == "" {
			ls.Title = strings.TrimSpace(h)
			continue
		}
		if h, ok := strings.CutPrefix(line, "## "); ok {
			section = strings.TrimSpace(h)
			continue
		}
		m := taskLineRe.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		it := parseItem(m[3], members)
		it.Done = m[2] != " "
		it.Section = section
		it.ID = itemID(m[3], seen)
		ls.Items = append(ls.Items, it)
	}
	if t := noteTitle(n); t != "" {
		ls.Title = t
	}
	return ls
}

// noteTitle is the note's "# Title" heading, if it has one.
func noteTitle(n *note) string {
	for _, line := range strings.Split(n.body, "\n") {
		if h, ok := strings.CutPrefix(line, "# "); ok {
			return strings.TrimSpace(h)
		}
	}
	return ""
}

func parseItem(raw string, members []Member) ListItem {
	it := ListItem{}
	text := raw
	if m := doneMarkRe.FindStringSubmatch(text); m != nil {
		it.DoneOn = m[1]
		text = doneMarkRe.ReplaceAllString(text, "")
	}
	if m := dueMarkRe.FindStringSubmatch(text); m != nil {
		it.Due = m[1]
		text = dueMarkRe.ReplaceAllString(text, "")
	}
	if m := whoMarkRe.FindStringSubmatch(" " + text); m != nil {
		if p := memberByName(members, m[1]); p != nil {
			it.Who = p.ID
			text = strings.TrimSpace(whoMarkRe.ReplaceAllString(" "+text, ""))
		}
	}
	it.Text = strings.TrimSpace(text)
	return it
}

// itemID: the item's text (without the done mark), plus how many identical items came first.
func itemID(raw string, seen map[string]int) string {
	key := strings.TrimSpace(doneMarkRe.ReplaceAllString(raw, ""))
	h := sha1.Sum([]byte(key))
	id := hex.EncodeToString(h[:4])
	seen[id]++
	if seen[id] > 1 {
		id = fmt.Sprintf("%s-%d", id, seen[id])
	}
	return id
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (l *lists) find(id string) (*note, string, error) {
	for _, dir := range l.dirs() {
		entries, _ := os.ReadDir(dir)
		for _, e := range entries {
			if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
				continue
			}
			n, err := readNote(filepath.Join(dir, e.Name()))
			if err != nil {
				continue
			}
			if got, _ := n.field("famlogic_id"); got == id {
				t, _ := n.field("type")
				return n, t, nil
			}
		}
	}
	return nil, "", errNoList
}

func (l *lists) get(id string, members []Member) (List, error) {
	n, kind, err := l.find(id)
	if err != nil {
		return List{}, err
	}
	return l.parse(n, id, kind, members), nil
}

// create makes a new list or to-do note.
func (l *lists) create(title, kind string, who []string, members []Member) (List, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	dir := filepath.Join(l.root, l.folders[kind])
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return List{}, err
	}
	name := noteName(title)
	path := filepath.Join(dir, name+".md")
	for i := 2; fileExists(path); i++ {
		path = filepath.Join(dir, fmt.Sprintf("%s (%d).md", name, i))
	}
	id := "l" + randomHex(5)
	front := "---\ntype: " + kind + "\nfamlogic_id: " + id + "\n"
	if len(who) > 0 {
		front += "who: " + whoLinks(who, members) + "\n"
	}
	text := front + "---\n# " + title + "\n\n"
	if err := writeAtomic(path, []byte(text)); err != nil {
		return List{}, err
	}
	return l.get(id, members)
}

// edit changes a list's items with fn, which gets the body's lines.
func (l *lists) edit(id string, members []Member, fn func(lines []string) ([]string, error)) (List, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	n, kind, err := l.find(id)
	if err != nil {
		return List{}, err
	}
	lines := strings.Split(strings.TrimRight(n.body, "\n"), "\n")
	lines, err = fn(lines)
	if err != nil {
		return List{}, err
	}
	n.body = strings.Join(lines, "\n") + "\n"
	if err := noteWriteBody(n); err != nil {
		return List{}, err
	}
	return l.parse(n, id, kind, members), nil
}

// lineOf finds the line of an item id.
func lineOf(lines []string, itemIDWanted string) int {
	seen := map[string]int{}
	for i, line := range lines {
		if m := taskLineRe.FindStringSubmatch(line); m != nil && itemID(m[3], seen) == itemIDWanted {
			return i
		}
	}
	return -1
}

var errNoItem = errors.New("no such item")

// itemLine writes an item as a task line.
func itemLine(text, whoID, due string, members []Member) string {
	s := "- [ ] " + strings.TrimSpace(text)
	if whoID != "" {
		for _, m := range members {
			if m.ID == whoID {
				s += " @" + strings.ReplaceAll(noteName(m.Name), " ", "_")
			}
		}
	}
	if due != "" {
		s += " 📅 " + due
	}
	return s
}

func (l *lists) addItem(id, text, whoID, due, section string, members []Member) (List, error) {
	return l.edit(id, members, func(lines []string) ([]string, error) {
		line := itemLine(text, whoID, due, members)
		// At the end of its section (or the end of the note).
		at := len(lines)
		if section != "" {
			in := false
			for i, ln := range lines {
				if h, ok := strings.CutPrefix(ln, "## "); ok {
					if in {
						at = i
						break
					}
					in = strings.EqualFold(strings.TrimSpace(h), section)
					if in {
						at = len(lines)
					}
				}
			}
			if !in && at == len(lines) {
				lines = append(lines, "", "## "+section)
				at = len(lines)
			}
		}
		// Not after trailing blank lines.
		for at > 0 && at <= len(lines) && strings.TrimSpace(lines[at-1]) == "" {
			at--
		}
		out := append([]string{}, lines[:at]...)
		out = append(out, line)
		return append(out, lines[at:]...), nil
	})
}

func (l *lists) setDone(id, item string, done bool, members []Member) (List, error) {
	return l.edit(id, members, func(lines []string) ([]string, error) {
		i := lineOf(lines, item)
		if i < 0 {
			return nil, errNoItem
		}
		m := taskLineRe.FindStringSubmatch(lines[i])
		text := strings.TrimSpace(doneMarkRe.ReplaceAllString(m[3], ""))
		if done {
			lines[i] = m[1] + "- [x] " + text + " ✅ " + today()
		} else {
			lines[i] = m[1] + "- [ ] " + text
		}
		return lines, nil
	})
}

func (l *lists) removeItem(id, item string, members []Member) (List, error) {
	return l.edit(id, members, func(lines []string) ([]string, error) {
		i := lineOf(lines, item)
		if i < 0 {
			return nil, errNoItem
		}
		return append(lines[:i], lines[i+1:]...), nil
	})
}

// clearDone removes ticked items (a shopping list after the shop).
func (l *lists) clearDone(id string, members []Member) (List, error) {
	return l.edit(id, members, func(lines []string) ([]string, error) {
		out := lines[:0]
		for _, ln := range lines {
			if m := taskLineRe.FindStringSubmatch(ln); m != nil && m[2] != " " {
				continue
			}
			out = append(out, ln)
		}
		return out, nil
	})
}

func (l *lists) archive(id string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	n, _, err := l.find(id)
	if err != nil {
		return err
	}
	return noteWriteFront(n, [][2]string{{"archived", "true"}})
}

// ---- small helpers shared by the vault modules

func noteWriteFront(n *note, fields [][2]string) error {
	text := setFront("---\n"+strings.Join(n.front, "\n")+"\n---\n"+n.body, fields)
	if len(n.front) == 0 {
		text = setFront(n.body, fields)
	}
	if err := writeAtomic(n.path, []byte(text)); err != nil {
		return err
	}
	lines, body, _ := splitFront(text)
	n.front, n.body = lines, body
	return nil
}

func noteWriteBody(n *note) error {
	return writeAtomic(n.path, []byte("---\n"+strings.Join(n.front, "\n")+"\n---\n"+n.body))
}

// ---- HTTP

func (s *Server) listsLevel(me *Member) string {
	if !s.store.Modules().Enabled["todos"] {
		return LevelOff
	}
	return me.level("todos")
}

func (s *Server) listsSetup(w http.ResponseWriter, me *Member) bool {
	if s.lists == nil {
		writeError(w, http.StatusServiceUnavailable, "lists need the notes vault, which isn't available on this server")
		return false
	}
	if s.listsLevel(me) == LevelOff {
		writeError(w, http.StatusForbidden, "Docket isn't switched on for you")
		return false
	}
	return true
}

// canSeeList: shared lists are everyone's; someone's to-dos are theirs and the grown-ups'.
func canSeeList(me *Member, l List) bool {
	return len(l.Who) == 0 || isAdult(me) || me.ID == "admin" || strings.HasPrefix(me.ID, "agent:") || containsStr(l.Who, me.ID)
}

func containsStr(xs []string, x string) bool {
	for _, y := range xs {
		if y == x {
			return true
		}
	}
	return false
}

func (s *Server) visibleList(w http.ResponseWriter, r *http.Request, me *Member) (List, bool) {
	l, err := s.lists.get(r.PathValue("id"), s.store.Members())
	if err != nil || !canSeeList(me, l) {
		writeError(w, http.StatusNotFound, "no such list")
		return List{}, false
	}
	return l, true
}

// GET /api/lists
func (s *Server) handleLists(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	out := []List{}
	for _, l := range s.lists.all(s.store.Members()) {
		if canSeeList(me, l) {
			out = append(out, l)
		}
	}
	level := s.listsLevel(me)
	writeJSON(w, http.StatusOK, map[string]any{"lists": out, "level": level,
		"canEdit": isAdult(me) || levelAtLeast(level, LevelFull), "canAdd": isAdult(me) || levelAtLeast(level, LevelAdd)})
}

// POST /api/lists {title, kind, who}
func (s *Server) handleCreateList(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	var body struct {
		Title string   `json:"title"`
		Kind  string   `json:"kind"`
		Who   []string `json:"who"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	title := strings.TrimSpace(body.Title)
	if title == "" || utf8.RuneCountInString(title) > 60 {
		writeError(w, http.StatusBadRequest, "a list needs a name (60 characters at most)")
		return
	}
	if body.Kind != "todos" {
		body.Kind = "list"
	}
	level := s.listsLevel(me)
	if !isAdult(me) && !levelAtLeast(level, LevelFull) {
		// A child may make their own to-do list, with "add".
		if !levelAtLeast(level, LevelAdd) || body.Kind != "todos" {
			writeError(w, http.StatusForbidden, "ask a grown-up to make that list")
			return
		}
		body.Who = []string{me.ID}
	}
	l, err := s.lists.create(title, body.Kind, body.Who, s.store.Members())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the list")
		return
	}
	writeJSON(w, http.StatusOK, l)
}

// DELETE /api/lists/{id}: archived (the note stays in the vault).
func (s *Server) handleArchiveList(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	l, ok := s.visibleList(w, r, me)
	if !ok {
		return
	}
	if !isAdult(me) && !(len(l.Who) == 1 && l.Who[0] == me.ID) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	if err := s.lists.archive(l.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/lists/{id}/items {text, who?, due?, section?}
func (s *Server) handleAddItem(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	l, ok := s.visibleList(w, r, me)
	if !ok {
		return
	}
	if !isAdult(me) && !levelAtLeast(s.listsLevel(me), LevelAdd) {
		writeError(w, http.StatusForbidden, "you can tick things off, but not add them")
		return
	}
	var body struct {
		Text    string `json:"text"`
		Who     string `json:"who"`
		Due     string `json:"due"`
		Section string `json:"section"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.Join(strings.Fields(body.Text), " ")
	if text == "" || utf8.RuneCountInString(text) > 200 {
		writeError(w, http.StatusBadRequest, "an item is 1 to 200 characters")
		return
	}
	if body.Due != "" && !dateRe.MatchString(body.Due) {
		writeError(w, http.StatusBadRequest, "due is a date: YYYY-MM-DD")
		return
	}
	got, err := s.lists.addItem(l.ID, text, body.Who, body.Due, strings.TrimSpace(body.Section), s.store.Members())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, got)
}

// PATCH /api/lists/{id}/items/{item} {done}; DELETE removes it.
func (s *Server) handleTickItem(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	l, ok := s.visibleList(w, r, me)
	if !ok {
		return
	}
	var body struct {
		Done bool `json:"done"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	got, err := s.lists.setDone(l.ID, r.PathValue("item"), body.Done, s.store.Members())
	if errors.Is(err, errNoItem) {
		writeError(w, http.StatusNotFound, "that item isn't there any more")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, got)
}

func (s *Server) handleRemoveItem(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	l, ok := s.visibleList(w, r, me)
	if !ok {
		return
	}
	if !isAdult(me) && !levelAtLeast(s.listsLevel(me), LevelAdd) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	got, err := s.lists.removeItem(l.ID, r.PathValue("item"), s.store.Members())
	if errors.Is(err, errNoItem) {
		writeError(w, http.StatusNotFound, "that item isn't there any more")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, got)
}

// POST /api/lists/{id}/clear: remove the ticked items.
func (s *Server) handleClearList(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.listsSetup(w, me) {
		return
	}
	l, ok := s.visibleList(w, r, me)
	if !ok {
		return
	}
	if !isAdult(me) && !levelAtLeast(s.listsLevel(me), LevelAdd) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	got, err := s.lists.clearDone(l.ID, s.store.Members())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, got)
}
