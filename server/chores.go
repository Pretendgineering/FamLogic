package main

// Upkeep, the chores module. Each chore is a Markdown note in the vault's Chores/
// folder (see wiki/Vault-Guide.md → chore), so people can read and edit chores in
// Obsidian, agents can edit them as text or through the API, and the apps show
// the same thing. The notes are the source of truth: the server reads them on
// each request (a family has dozens, not thousands) and writes them back
// atomically, keeping whatever else people put in them.
//
//	---
//	type: chore
//	famlogic_id: c3f9a1
//	who: ["[[Sam]]"]
//	every: week
//	due: 2026-12-01
//	stars: 2
//	---
//	# Bins out
//
//	Green and black bins to the kerb on Sunday evening.
//
//	- [x] 2026-11-24 done by [[Sam]]
//	- [ ] 2026-12-01

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	_ "time/tzdata" // the family's time zone (TZ) works in the minimal image too
)

// Chore is one chore as the apps see it.
type Chore struct {
	ID       string      `json:"id"`
	Title    string      `json:"title"`
	Who      []string    `json:"who"`             // member ids ([] = anyone)
	Every    string      `json:"every,omitempty"` // "" = once; day, weekdays, week, 2 weeks, month, mon,thu
	Due      string      `json:"due,omitempty"`   // YYYY-MM-DD: the next time it's due
	When     string      `json:"when,omitempty"`  // morning, evening, 16:00...
	Stars    int         `json:"stars,omitempty"`
	Notes    string      `json:"notes,omitempty"`
	Done     []ChoreDone `json:"done"`                 // the latest first
	Finished bool        `json:"finished"`             // a one-off chore that's been done
	File     string      `json:"file"`                 // its note, relative to the vault
	Unknown  []string    `json:"unknownWho,omitempty"` // names in who that aren't anyone in the family
}

// ChoreDone is one time it was done.
type ChoreDone struct {
	Date string `json:"date"`
	By   string `json:"by,omitempty"` // member id
}

var (
	errNoChore   = errors.New("no such chore")
	errNoVault   = errors.New("the notes vault isn't available")
	choreLineRe  = regexp.MustCompile(`^\s*- \[( |x|X)\] (\d{4}-\d{2}-\d{2})(?: done by \[\[([^\]]+)\]\])?`)
	dateRe       = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	weekdayNames = []string{"sun", "mon", "tue", "wed", "thu", "fri", "sat"}
)

// chores reads and writes the Chores/ folder. One writer at a time.
type chores struct {
	dir string
	mu  sync.Mutex
}

func newChores(v *Vault) *chores {
	if v == nil {
		return nil
	}
	return &chores{dir: filepath.Join(v.dir, "Chores")}
}

// today is the family's date (TZ, or the server's clock).
func today() string { return time.Now().Format("2006-01-02") }

// ---- reading notes

// note is a parsed chore note.
type note struct {
	path  string
	front []string // front-matter lines
	body  string
}

func readNote(path string) (*note, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	lines, body, ok := splitFront(string(b))
	if !ok {
		return &note{path: path, body: string(b)}, nil
	}
	return &note{path: path, front: lines, body: body}, nil
}

// field reads a top-level front-matter field: a scalar, an inline [list] or a block list.
func (n *note) field(key string) (string, []string) {
	for i, l := range n.front {
		k, v, ok := strings.Cut(l, ":")
		if !ok || k != key || strings.HasPrefix(l, " ") {
			continue
		}
		v = strings.TrimSpace(v)
		if strings.HasPrefix(v, "[") && strings.HasSuffix(v, "]") {
			return v, splitList(v[1 : len(v)-1])
		}
		if v == "" { // a block list on the following lines
			var items []string
			for _, nl := range n.front[i+1:] {
				t := strings.TrimSpace(nl)
				if !strings.HasPrefix(t, "- ") {
					break
				}
				items = append(items, unquote(strings.TrimSpace(t[2:])))
			}
			return "", items
		}
		return unquote(v), nil
	}
	return "", nil
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = unquote(strings.TrimSpace(p)); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func unquote(s string) string {
	s = strings.TrimSpace(s)
	if len(s) >= 2 && (s[0] == '"' && s[len(s)-1] == '"' || s[0] == '\'' && s[len(s)-1] == '\'') {
		s = s[1 : len(s)-1]
	}
	return s
}

// linkName turns "[[Sam]]" (or "[[People/Sam|Sammy]]", or plain "Sam") into "Sam".
func linkName(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(strings.TrimSuffix(s, "]]"), "[[")
	if i := strings.IndexByte(s, '|'); i >= 0 {
		s = s[:i]
	}
	s = strings.TrimPrefix(s, "People/")
	return strings.TrimPrefix(strings.TrimSpace(s), "@")
}

// list reads every chore (archived ones only if asked). people maps names to ids.
func (c *chores) list(members []Member, archived bool) ([]Chore, error) {
	entries, err := os.ReadDir(c.dir)
	if errors.Is(err, os.ErrNotExist) {
		return []Chore{}, nil
	}
	if err != nil {
		return nil, err
	}
	out := []Chore{}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(c.dir, e.Name()))
		if err != nil {
			continue
		}
		if t, _ := n.field("type"); t != "chore" {
			continue
		}
		if a, _ := n.field("archived"); a == "true" && !archived {
			continue
		}
		ch := c.parse(n, members)
		if ch.ID == "" {
			// A chore written by hand (Obsidian, an agent): give it an id now.
			ch.ID = newChoreID()
			c.writeFront(n, [][2]string{{"famlogic_id", ch.ID}})
		}
		out = append(out, ch)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Finished != out[j].Finished {
			return !out[i].Finished
		}
		if out[i].Due != out[j].Due {
			return out[i].Due < out[j].Due
		}
		return strings.ToLower(out[i].Title) < strings.ToLower(out[j].Title)
	})
	return out, nil
}

func (c *chores) parse(n *note, members []Member) Chore {
	id, _ := n.field("famlogic_id")
	every, _ := n.field("every")
	due, _ := n.field("due")
	when, _ := n.field("when")
	starsS, _ := n.field("stars")
	if starsS == "" {
		starsS, _ = n.field("points") // the guide's older name
	}
	stars, _ := strconv.Atoi(starsS)
	whoS, whoL := n.field("who")
	if whoL == nil && whoS != "" {
		whoL = []string{whoS}
	}
	ch := Chore{ID: id, Every: every, Due: due, When: when, Stars: max(stars, 0), Who: []string{}, Done: []ChoreDone{},
		File: filepath.ToSlash(filepath.Join("Chores", filepath.Base(n.path)))}
	for _, w := range whoL {
		name := linkName(w)
		if m := memberByName(members, name); m != nil {
			ch.Who = append(ch.Who, m.ID)
		} else if name != "" {
			ch.Unknown = append(ch.Unknown, name)
		}
	}
	// The body: "# Title", the notes, then one task line per time it's due.
	var notes []string
	for _, l := range strings.Split(n.body, "\n") {
		if ch.Title == "" && strings.HasPrefix(l, "# ") {
			ch.Title = strings.TrimSpace(l[2:])
			continue
		}
		if m := choreLineRe.FindStringSubmatch(l); m != nil {
			if m[1] != " " {
				d := ChoreDone{Date: m[2]}
				if by := memberByName(members, m[3]); by != nil {
					d.By = by.ID
				}
				ch.Done = append([]ChoreDone{d}, ch.Done...)
			}
			continue
		}
		if ch.Title != "" && len(ch.Done) == 0 {
			notes = append(notes, l)
		}
	}
	if ch.Title == "" {
		ch.Title = strings.TrimSuffix(filepath.Base(n.path), ".md")
	}
	ch.Notes = strings.TrimSpace(strings.Join(notes, "\n"))
	sort.SliceStable(ch.Done, func(i, j int) bool { return ch.Done[i].Date > ch.Done[j].Date })
	ch.Finished = ch.Every == "" && len(ch.Done) > 0
	return ch
}

func memberByName(members []Member, name string) *Member {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil
	}
	for i := range members {
		if strings.EqualFold(members[i].Name, name) || strings.EqualFold(noteName(members[i].Name), name) {
			return &members[i]
		}
	}
	return nil
}

func newChoreID() string {
	b := make([]byte, 5)
	rand.Read(b)
	return "c" + hex.EncodeToString(b)
}

// find returns the note for a chore id.
func (c *chores) find(id string) (*note, error) {
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return nil, errNoChore
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(c.dir, e.Name()))
		if err != nil {
			continue
		}
		if got, _ := n.field("famlogic_id"); got == id {
			return n, nil
		}
	}
	return nil, errNoChore
}

// ---- writing notes

func (c *chores) writeFront(n *note, fields [][2]string) error {
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

func (c *chores) writeBody(n *note) error {
	text := "---\n" + strings.Join(n.front, "\n") + "\n---\n" + n.body
	return writeAtomic(n.path, []byte(text))
}

// ChoreInput is what can be set on a chore. Nil fields stay as they are.
type ChoreInput struct {
	Title *string   `json:"title"`
	Who   *[]string `json:"who"` // member ids
	Every *string   `json:"every"`
	Due   *string   `json:"due"`
	When  *string   `json:"when"`
	Stars *int      `json:"stars"`
	Notes *string   `json:"notes"`
}

// validEvery checks a repeat rule.
func validEvery(s string) bool {
	switch s {
	case "", "day", "weekdays", "week", "month":
		return true
	}
	if n, unit, ok := strings.Cut(s, " "); ok {
		k, err := strconv.Atoi(n)
		return err == nil && k >= 1 && k <= 52 && (unit == "days" || unit == "weeks" || unit == "months")
	}
	for _, d := range strings.Split(s, ",") {
		if !slices.Contains(weekdayNames, strings.TrimSpace(strings.ToLower(d))) {
			return false
		}
	}
	return true
}

// nextDue is the next date after `from` that a chore repeating `every` is due.
func nextDue(every, from string) string {
	t, err := time.Parse("2006-01-02", from)
	if err != nil {
		t = time.Now()
	}
	switch every {
	case "day":
		t = t.AddDate(0, 0, 1)
	case "weekdays":
		t = t.AddDate(0, 0, 1)
		for t.Weekday() == time.Saturday || t.Weekday() == time.Sunday {
			t = t.AddDate(0, 0, 1)
		}
	case "week":
		t = t.AddDate(0, 0, 7)
	case "month":
		t = t.AddDate(0, 1, 0)
	default:
		if n, unit, ok := strings.Cut(every, " "); ok {
			k, _ := strconv.Atoi(n)
			switch unit {
			case "days":
				t = t.AddDate(0, 0, k)
			case "weeks":
				t = t.AddDate(0, 0, 7*k)
			case "months":
				t = t.AddDate(0, k, 0)
			}
			break
		}
		// Days of the week: the next listed one.
		want := map[time.Weekday]bool{}
		for _, d := range strings.Split(every, ",") {
			if i := slices.Index(weekdayNames, strings.TrimSpace(strings.ToLower(d))); i >= 0 {
				want[time.Weekday(i)] = true
			}
		}
		if len(want) == 0 {
			return ""
		}
		for {
			t = t.AddDate(0, 0, 1)
			if want[t.Weekday()] {
				break
			}
		}
	}
	return t.Format("2006-01-02")
}

func whoLinks(ids []string, members []Member) string {
	var parts []string
	for _, id := range ids {
		for _, m := range members {
			if m.ID == id {
				parts = append(parts, yamlString("[["+noteName(m.Name)+"]]"))
			}
		}
	}
	return "[" + strings.Join(parts, ", ") + "]"
}

// create writes a new chore note.
func (c *chores) create(in ChoreInput, members []Member) (Chore, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := os.MkdirAll(c.dir, 0o755); err != nil {
		return Chore{}, err
	}
	title := strings.TrimSpace(deref(in.Title))
	name := noteName(title)
	path := filepath.Join(c.dir, name+".md")
	for i := 2; fileExists(path); i++ {
		path = filepath.Join(c.dir, fmt.Sprintf("%s (%d).md", name, i))
	}
	id := newChoreID()
	due := deref(in.Due)
	if due == "" {
		due = today()
	}
	fields := [][2]string{{"type", "chore"}, {"famlogic_id", id}, {"who", whoLinks(derefList(in.Who), members)}}
	if e := deref(in.Every); e != "" {
		fields = append(fields, [2]string{"every", e})
	}
	fields = append(fields, [2]string{"due", due})
	if w := deref(in.When); w != "" {
		fields = append(fields, [2]string{"when", yamlString(w)})
	}
	if in.Stars != nil && *in.Stars > 0 {
		fields = append(fields, [2]string{"stars", strconv.Itoa(*in.Stars)})
	}
	body := "# " + title + "\n\n"
	if n := strings.TrimSpace(deref(in.Notes)); n != "" {
		body += n + "\n\n"
	}
	body += "- [ ] " + due + "\n"
	if err := writeAtomic(path, []byte(setFront(body, fields))); err != nil {
		return Chore{}, err
	}
	n, err := readNote(path)
	if err != nil {
		return Chore{}, err
	}
	return c.parse(n, members), nil
}

// update changes a chore's fields (keeping everything else in the note).
func (c *chores) update(id string, in ChoreInput, members []Member) (Chore, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	n, err := c.find(id)
	if err != nil {
		return Chore{}, err
	}
	var fields [][2]string
	if in.Who != nil {
		fields = append(fields, [2]string{"who", whoLinks(*in.Who, members)})
	}
	if in.Every != nil {
		fields = append(fields, [2]string{"every", *in.Every})
	}
	if in.Due != nil {
		fields = append(fields, [2]string{"due", *in.Due})
	}
	if in.When != nil {
		fields = append(fields, [2]string{"when", yamlString(*in.When)})
	}
	if in.Stars != nil {
		fields = append(fields, [2]string{"stars", strconv.Itoa(max(*in.Stars, 0))})
	}
	if len(fields) > 0 {
		if err := c.writeFront(n, fields); err != nil {
			return Chore{}, err
		}
	}
	if in.Title != nil || in.Notes != nil {
		cur := c.parse(n, members)
		title := cur.Title
		if in.Title != nil {
			title = strings.TrimSpace(*in.Title)
		}
		notes := cur.Notes
		if in.Notes != nil {
			notes = strings.TrimSpace(*in.Notes)
		}
		// Rewrite the heading and notes, keeping the task lines as they are.
		var tasks []string
		for _, l := range strings.Split(n.body, "\n") {
			if choreLineRe.MatchString(l) {
				tasks = append(tasks, l)
			}
		}
		body := "# " + title + "\n\n"
		if notes != "" {
			body += notes + "\n\n"
		}
		n.body = body + strings.Join(tasks, "\n") + "\n"
		if err := c.writeBody(n); err != nil {
			return Chore{}, err
		}
		// A new title: the note is renamed to match.
		if in.Title != nil && noteName(title)+".md" != filepath.Base(n.path) {
			to := filepath.Join(c.dir, noteName(title)+".md")
			if !fileExists(to) && os.Rename(n.path, to) == nil {
				n.path = to
			}
		}
	}
	n, _ = readNote(n.path)
	return c.parse(n, members), nil
}

// tick marks a chore done on a date by someone; a repeating chore moves on to its
// next date. Returns the chore and whether anything changed.
func (c *chores) tick(id, date, byName string, members []Member) (Chore, bool, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	n, err := c.find(id)
	if err != nil {
		return Chore{}, false, err
	}
	ch := c.parse(n, members)
	if ch.Finished {
		return ch, false, nil
	}
	if date == "" {
		date = ch.Due
		if date == "" || date > today() {
			date = today()
		}
	}
	for _, d := range ch.Done {
		if d.Date == date {
			return ch, false, nil // already done for that day
		}
	}
	doneLine := "- [x] " + date + " done by [[" + noteName(byName) + "]]"
	lines := strings.Split(strings.TrimRight(n.body, "\n"), "\n")
	replaced := false
	for i, l := range lines {
		if m := choreLineRe.FindStringSubmatch(l); m != nil && m[1] == " " && m[2] == date {
			lines[i] = doneLine
			replaced = true
			break
		}
	}
	if !replaced {
		lines = append(lines, doneLine)
	}
	if ch.Every != "" {
		next := nextDue(ch.Every, max(date, ch.Due))
		if next != "" {
			has := false
			for _, l := range lines {
				if m := choreLineRe.FindStringSubmatch(l); m != nil && m[2] == next {
					has = true
				}
			}
			if !has {
				lines = append(lines, "- [ ] "+next)
			}
			n.body = strings.Join(lines, "\n") + "\n"
			if err := c.writeBody(n); err != nil {
				return Chore{}, false, err
			}
			if err := c.writeFront(n, [][2]string{{"due", next}}); err != nil {
				return Chore{}, false, err
			}
			n, _ = readNote(n.path)
			return c.parse(n, members), true, nil
		}
	}
	n.body = strings.Join(lines, "\n") + "\n"
	if err := c.writeBody(n); err != nil {
		return Chore{}, false, err
	}
	n, _ = readNote(n.path)
	return c.parse(n, members), true, nil
}

// untick takes back the tick for a date (it wasn't really done). Returns who had ticked it.
func (c *chores) untick(id, date string, members []Member) (Chore, string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	n, err := c.find(id)
	if err != nil {
		return Chore{}, "", err
	}
	ch := c.parse(n, members)
	if date == "" && len(ch.Done) > 0 {
		date = ch.Done[0].Date
	}
	by := ""
	lines := strings.Split(strings.TrimRight(n.body, "\n"), "\n")
	out := lines[:0]
	found := false
	for _, l := range lines {
		m := choreLineRe.FindStringSubmatch(l)
		switch {
		case m != nil && m[1] != " " && m[2] == date:
			found = true
			if p := memberByName(members, m[3]); p != nil {
				by = p.ID
			}
			out = append(out, "- [ ] "+date)
		case m != nil && m[1] == " " && ch.Every != "" && m[2] == ch.Due && ch.Due > date:
			// The next date that was added when it was ticked: back to this one.
		default:
			out = append(out, l)
		}
	}
	if !found {
		return ch, "", nil
	}
	n.body = strings.Join(out, "\n") + "\n"
	if err := c.writeBody(n); err != nil {
		return Chore{}, "", err
	}
	if ch.Every != "" {
		if err := c.writeFront(n, [][2]string{{"due", date}}); err != nil {
			return Chore{}, "", err
		}
	}
	n, _ = readNote(n.path)
	return c.parse(n, members), by, nil
}

// archive retires a chore: kept in the vault, hidden in the apps (the guide asks
// that notes aren't deleted).
func (c *chores) archive(id string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	n, err := c.find(id)
	if err != nil {
		return err
	}
	return c.writeFront(n, [][2]string{{"archived", "true"}})
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func derefList(s *[]string) []string {
	if s == nil {
		return nil
	}
	return *s
}
