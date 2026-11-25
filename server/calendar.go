package main

// Datebook: the family calendar. Each event is a Markdown note in the vault's
// Calendar/ folder ("2026-12-05 Dentist (Sam).md"):
//
//	---
//	type: event
//	famlogic_id: e3f9a1c2b7
//	uid: e3f9a1c2b7@famlogic
//	start: 2026-12-05T15:30
//	end: 2026-12-05T16:15
//	who: ["[[Sam]]"]
//	where: Smile Dental
//	rrule: FREQ=WEEKLY;BYDAY=TU
//	---
//	# Dentist (Sam)
//
//	Bring the referral letter.
//
// Phones sync with it both ways over CalDAV (calendar.go's sibling caldav.go):
// DAVx5 on Android, the Calendar app on iPhones and Macs, Thunderbird. Extra
// details a calendar app adds (alarms, attendees) are kept alongside the note in
// DATA_DIR/caldav/<uid>.ics and merged back when the event goes out again.

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/emersion/go-ical"
	"github.com/teambition/rrule-go"
)

var errNoEvent = errors.New("no such event")

type CalEvent struct {
	ID     string   `json:"id"`
	UID    string   `json:"uid"`
	Title  string   `json:"title"`
	Start  string   `json:"start"` // 2026-12-05T15:30, or 2026-12-05 all day
	End    string   `json:"end,omitempty"`
	AllDay bool     `json:"allDay"`
	Who    []string `json:"who"`
	Where  string   `json:"where,omitempty"`
	Notes  string   `json:"notes,omitempty"`
	RRule  string   `json:"rrule,omitempty"`
	File   string   `json:"file"`
	mod    time.Time
}

// Occurrence is one showing of an event in a time range (repeats expanded).
type Occurrence struct {
	ID        string   `json:"id"`
	Title     string   `json:"title"`
	Start     int64    `json:"start"` // unix ms
	End       int64    `json:"end"`
	AllDay    bool     `json:"allDay"`
	Who       []string `json:"who"`
	Where     string   `json:"where,omitempty"`
	Notes     string   `json:"notes,omitempty"`
	Recurring bool     `json:"recurring,omitempty"`
}

type calendar struct {
	dir    string // vault Calendar/
	extras string // DATA_DIR/caldav: raw iCalendar kept for what notes don't hold
	mu     sync.Mutex
}

func newCalendar(v *Vault, dataDir string) *calendar {
	if v == nil {
		return nil
	}
	return &calendar{dir: filepath.Join(v.dir, "Calendar"), extras: filepath.Join(dataDir, "caldav")}
}

const (
	dayLayout  = "2006-01-02"
	timeLayout = "2006-01-02T15:04"
)

// parseWhen reads a note's start or end: a date (all day) or a local date and time.
func parseWhen(s string) (time.Time, bool, error) {
	s = strings.TrimSpace(s)
	if t, err := time.ParseInLocation(timeLayout, s, time.Local); err == nil {
		return t, false, nil
	}
	if t, err := time.ParseInLocation("2006-01-02 15:04", s, time.Local); err == nil {
		return t, false, nil
	}
	if t, err := time.ParseInLocation(dayLayout, s, time.Local); err == nil {
		return t, true, nil
	}
	return time.Time{}, false, fmt.Errorf("a date (YYYY-MM-DD) or date and time (YYYY-MM-DDTHH:MM)")
}

func (c *calendar) all(members []Member) []CalEvent {
	out := []CalEvent{}
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return out
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(c.dir, e.Name()))
		if err != nil {
			continue
		}
		if t, _ := n.field("type"); t != "event" {
			continue
		}
		if a, _ := n.field("archived"); a == "true" {
			continue
		}
		ev := c.parse(n, members)
		if ev.ID == "" {
			ev.ID = "e" + randomHex(5)
			fields := [][2]string{{"famlogic_id", ev.ID}}
			if ev.UID == "" {
				ev.UID = ev.ID + "@famlogic"
				fields = append(fields, [2]string{"uid", ev.UID})
			}
			noteWriteFront(n, fields)
		}
		if ev.Start == "" {
			continue
		}
		if fi, err := os.Stat(n.path); err == nil {
			ev.mod = fi.ModTime()
		}
		out = append(out, ev)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Start < out[j].Start })
	return out
}

func (c *calendar) parse(n *note, members []Member) CalEvent {
	ev := CalEvent{Who: []string{}, File: filepath.Base(n.path)}
	ev.ID, _ = n.field("famlogic_id")
	ev.UID, _ = n.field("uid")
	if ev.UID == "" && ev.ID != "" {
		ev.UID = ev.ID + "@famlogic"
	}
	ev.Start, _ = n.field("start")
	ev.End, _ = n.field("end")
	ev.Where, _ = n.field("where")
	ev.RRule, _ = n.field("rrule")
	if ev.RRule == "" {
		if every, _ := n.field("every"); every != "" {
			ev.RRule = everyToRRule(every)
		}
	}
	if until, _ := n.field("until"); until != "" && ev.RRule != "" && !strings.Contains(ev.RRule, "UNTIL=") {
		ev.RRule += ";UNTIL=" + strings.ReplaceAll(until, "-", "") + "T235959Z"
	}
	_, ev.AllDay, _ = parseWhen(ev.Start)
	_, who := n.field("who")
	for _, w := range who {
		if m := memberByName(members, linkName(w)); m != nil {
			ev.Who = append(ev.Who, m.ID)
		}
	}
	// The title is the "# " heading (else the file name without its date).
	ev.Title = noteTitle(n)
	if ev.Title == "" {
		ev.Title = strings.TrimSuffix(ev.File, ".md")
		if len(ev.Title) > 11 && ev.Title[4] == '-' && ev.Title[10] == ' ' {
			ev.Title = ev.Title[11:]
		}
	}
	// Notes: the body after the heading.
	var notes []string
	pastTitle := false
	for _, line := range strings.Split(n.body, "\n") {
		if strings.HasPrefix(line, "# ") && !pastTitle {
			pastTitle = true
			continue
		}
		notes = append(notes, line)
	}
	ev.Notes = strings.TrimSpace(strings.Join(notes, "\n"))
	return ev
}

// everyToRRule turns the guide's friendly repeats into an RRULE.
func everyToRRule(every string) string {
	switch strings.ToLower(strings.TrimSpace(every)) {
	case "day":
		return "FREQ=DAILY"
	case "weekdays":
		return "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
	case "week":
		return "FREQ=WEEKLY"
	case "2 weeks":
		return "FREQ=WEEKLY;INTERVAL=2"
	case "month":
		return "FREQ=MONTHLY"
	case "year":
		return "FREQ=YEARLY"
	}
	return ""
}

func (c *calendar) find(id string) (*note, error) {
	entries, err := os.ReadDir(c.dir)
	if err != nil {
		return nil, errNoEvent
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(c.dir, e.Name()))
		if err != nil {
			continue
		}
		got, _ := n.field("famlogic_id")
		uid, _ := n.field("uid")
		if got == id || uid == id {
			return n, nil
		}
	}
	return nil, errNoEvent
}

// EventInput is what can be set; nil fields stay as they are.
type EventInput struct {
	Title *string   `json:"title"`
	Start *string   `json:"start"`
	End   *string   `json:"end"`
	Who   *[]string `json:"who"`
	Where *string   `json:"where"`
	Notes *string   `json:"notes"`
	RRule *string   `json:"rrule"`
	Every *string   `json:"every"` // day, weekdays, week, 2 weeks, month, year: turned into rrule
}

func (in EventInput) check(creating bool) string {
	if creating && (in.Title == nil || strings.TrimSpace(*in.Title) == "" || in.Start == nil) {
		return "an event needs a name and a start"
	}
	if in.Title != nil && utf8.RuneCountInString(*in.Title) > 100 {
		return "keep the name to 100 characters"
	}
	if in.Start != nil {
		if _, _, err := parseWhen(*in.Start); err != nil {
			return "start: " + err.Error()
		}
	}
	if in.End != nil && *in.End != "" {
		if _, _, err := parseWhen(*in.End); err != nil {
			return "end: " + err.Error()
		}
	}
	if in.RRule != nil && *in.RRule != "" {
		if _, err := rrule.StrToROption(*in.RRule); err != nil {
			return "repeat rule: " + err.Error()
		}
	}
	return ""
}

// save writes an event note (a new one when id is ""); returns the event.
func (c *calendar) save(id string, in EventInput, members []Member) (CalEvent, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if in.Every != nil {
		r := everyToRRule(*in.Every)
		in.RRule = &r
	}
	var n *note
	if id != "" {
		var err error
		if n, err = c.find(id); err != nil {
			return CalEvent{}, err
		}
	} else {
		if err := os.MkdirAll(c.dir, 0o755); err != nil {
			return CalEvent{}, err
		}
		newID := "e" + randomHex(5)
		uid := newID + "@famlogic"
		if in.RRule == nil { // keep a UID a calendar app chose (set by the caller via Notes? no: see caldav)
		}
		n = &note{path: "", front: []string{"type: event", "famlogic_id: " + newID, "uid: " + uid}, body: ""}
		id = newID
	}
	cur := c.parse(n, members)
	get := func(p *string, old string) string {
		if p != nil {
			return strings.TrimSpace(*p)
		}
		return old
	}
	title := get(in.Title, cur.Title)
	start := get(in.Start, cur.Start)
	end := get(in.End, cur.End)
	fields := [][2]string{{"start", start}}
	fields = append(fields, [2]string{"end", end})
	fields = append(fields, [2]string{"where", yamlString(get(in.Where, cur.Where))})
	if in.RRule != nil {
		fields = append(fields, [2]string{"rrule", *in.RRule}, [2]string{"every", ""})
	}
	if in.Who != nil {
		fields = append(fields, [2]string{"who", whoLinks(*in.Who, members)})
	} else if len(cur.Who) == 0 {
		fields = append(fields, [2]string{"who", "[]"})
	}
	notes := cur.Notes
	if in.Notes != nil {
		notes = strings.TrimSpace(*in.Notes)
	}
	body := "# " + title + "\n"
	if notes != "" {
		body += "\n" + notes + "\n"
	}
	// File name: "<date> <Title>.md"; renamed when the title or date changes.
	day := start
	if len(day) > 10 {
		day = day[:10]
	}
	want := filepath.Join(c.dir, noteName(day+" "+title)+".md")
	text := setFront("---\n"+strings.Join(n.front, "\n")+"\n---\n"+body, fields)
	if n.path != want {
		path := want
		for i := 2; fileExists(path) && path != n.path; i++ {
			path = filepath.Join(c.dir, fmt.Sprintf("%s (%d).md", noteName(day+" "+title), i))
		}
		if n.path != "" && n.path != path {
			removeJournaled(n.path)
		}
		n.path = path
	}
	if err := writeAtomic(n.path, []byte(text)); err != nil {
		return CalEvent{}, err
	}
	n2, err := readNote(n.path)
	if err != nil {
		return CalEvent{}, err
	}
	return c.parse(n2, members), nil
}

func (c *calendar) archive(id string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	n, err := c.find(id)
	if err != nil {
		return err
	}
	return noteWriteFront(n, [][2]string{{"archived", "true"}})
}

// occurrences: every showing of the events between from and to.
func (c *calendar) occurrences(members []Member, from, to time.Time) []Occurrence {
	out := []Occurrence{}
	for _, ev := range c.all(members) {
		start, allDay, err := parseWhen(ev.Start)
		if err != nil {
			continue
		}
		dur := time.Hour
		if allDay {
			dur = 24 * time.Hour
		}
		if ev.End != "" {
			if end, _, err := parseWhen(ev.End); err == nil && end.After(start) {
				dur = end.Sub(start)
				if allDay {
					dur += 24 * time.Hour // an all-day end date is inclusive in notes
				}
			}
		}
		add := func(s time.Time, rec bool) {
			out = append(out, Occurrence{ID: ev.ID, Title: ev.Title, Start: s.UnixMilli(), End: s.Add(dur).UnixMilli(), AllDay: allDay,
				Who: ev.Who, Where: ev.Where, Notes: ev.Notes, Recurring: rec})
		}
		if ev.RRule == "" {
			if start.Before(to) && start.Add(dur).After(from) {
				add(start, false)
			}
			continue
		}
		opt, err := rrule.StrToROption(ev.RRule)
		if err != nil {
			continue
		}
		opt.Dtstart = start
		r, err := rrule.NewRRule(*opt)
		if err != nil {
			continue
		}
		for _, s := range r.Between(from.Add(-dur), to, true) {
			add(s, true)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Start < out[j].Start })
	return out
}

// ---- iCalendar, for CalDAV

func (c *calendar) extraPath(uid string) string {
	h := sha1.Sum([]byte(uid))
	return filepath.Join(c.extras, hex.EncodeToString(h[:10])+".ics")
}

// toICS builds the event's iCalendar: what a calendar app sent last (alarms and
// all), with the note's fields on top.
func (c *calendar) toICS(ev CalEvent, members []Member) *ical.Calendar {
	var cal *ical.Calendar
	if b, err := os.ReadFile(c.extraPath(ev.UID)); err == nil {
		if got, err := ical.NewDecoder(bytes.NewReader(b)).Decode(); err == nil {
			cal = got
		}
	}
	if cal == nil {
		cal = ical.NewCalendar()
		cal.Props.SetText(ical.PropVersion, "2.0")
		cal.Props.SetText(ical.PropProductID, "-//FamLogic//Datebook//EN")
	}
	var ve *ical.Component
	for _, child := range cal.Children {
		if child.Name == ical.CompEvent && child.Props.Get(ical.PropRecurrenceID) == nil {
			ve = child
			break
		}
	}
	if ve == nil {
		ve = ical.NewEvent().Component
		cal.Children = append(cal.Children, ve)
	}
	ve.Props.SetText(ical.PropUID, ev.UID)
	ve.Props.SetDateTime(ical.PropDateTimeStamp, time.Now().UTC())
	ve.Props.SetText(ical.PropSummary, ev.Title)
	start, allDay, _ := parseWhen(ev.Start)
	ve.Props.Del(ical.PropDuration)
	if allDay {
		ve.Props.SetDate(ical.PropDateTimeStart, start)
		end := start.AddDate(0, 0, 1)
		if ev.End != "" {
			if e, _, err := parseWhen(ev.End); err == nil && !e.Before(start) {
				end = e.AddDate(0, 0, 1)
			}
		}
		ve.Props.SetDate(ical.PropDateTimeEnd, end)
	} else {
		ve.Props.SetDateTime(ical.PropDateTimeStart, start.UTC())
		end := start.Add(time.Hour)
		if ev.End != "" {
			if e, _, err := parseWhen(ev.End); err == nil && e.After(start) {
				end = e
			}
		}
		ve.Props.SetDateTime(ical.PropDateTimeEnd, end.UTC())
	}
	setOrDel := func(name, v string) {
		if v == "" {
			ve.Props.Del(name)
		} else {
			ve.Props.SetText(name, v)
		}
	}
	setOrDel(ical.PropLocation, ev.Where)
	setOrDel(ical.PropDescription, ev.Notes)
	if ev.RRule == "" {
		ve.Props.Del(ical.PropRecurrenceRule)
	} else {
		p := ical.NewProp(ical.PropRecurrenceRule)
		p.SetValueType(ical.ValueRecurrence)
		p.Value = ev.RRule
		ve.Props.Set(p)
	}
	// Who it's for, as categories (calendar apps show them; FamLogic reads them back).
	var names []string
	for _, id := range ev.Who {
		for _, m := range members {
			if m.ID == id {
				names = append(names, m.Name)
			}
		}
	}
	if len(names) > 0 {
		p := ical.NewProp(ical.PropCategories)
		p.SetTextList(names)
		ve.Props.Set(p)
	} else {
		ve.Props.Del(ical.PropCategories)
	}
	return cal
}

func encodeICS(cal *ical.Calendar) []byte {
	var buf bytes.Buffer
	_ = ical.NewEncoder(&buf).Encode(cal)
	return buf.Bytes()
}

func etagOf(b []byte) string {
	h := sha1.Sum(b)
	return hex.EncodeToString(h[:10])
}

// fromICS saves an event a calendar app sent (new or changed).
func (c *calendar) fromICS(cal *ical.Calendar, members []Member) (CalEvent, error) {
	var ve *ical.Component
	for _, child := range cal.Children {
		if child.Name == ical.CompEvent && child.Props.Get(ical.PropRecurrenceID) == nil {
			ve = child
			break
		}
	}
	if ve == nil {
		return CalEvent{}, fmt.Errorf("no event in it")
	}
	ev := &ical.Event{Component: ve}
	uid, _ := ve.Props.Text(ical.PropUID)
	if uid == "" {
		return CalEvent{}, fmt.Errorf("the event has no UID")
	}
	title, _ := ve.Props.Text(ical.PropSummary)
	if title == "" {
		title = "(no title)"
	}
	start, err := ev.DateTimeStart(time.Local)
	if err != nil {
		return CalEvent{}, fmt.Errorf("the event has no start")
	}
	allDay := ve.Props.Get(ical.PropDateTimeStart).ValueType() == ical.ValueDate
	var s, e string
	if allDay {
		s = start.Format(dayLayout)
		if end, err := ev.DateTimeEnd(time.Local); err == nil && end.After(start.AddDate(0, 0, 1)) {
			e = end.AddDate(0, 0, -1).Format(dayLayout)
		}
	} else {
		s = start.In(time.Local).Format(timeLayout)
		if end, err := ev.DateTimeEnd(time.Local); err == nil && end.After(start) {
			e = end.In(time.Local).Format(timeLayout)
		}
	}
	where, _ := ve.Props.Text(ical.PropLocation)
	desc, _ := ve.Props.Text(ical.PropDescription)
	rr := ""
	if p := ve.Props.Get(ical.PropRecurrenceRule); p != nil {
		rr = p.Value
	}
	in := EventInput{Title: &title, Start: &s, End: &e, Where: &where, Notes: &desc, RRule: &rr}
	if p := ve.Props.Get(ical.PropCategories); p != nil {
		if names, err := p.TextList(); err == nil {
			ids := []string{}
			for _, nm := range names {
				if m := memberByName(members, nm); m != nil {
					ids = append(ids, m.ID)
				}
			}
			in.Who = &ids
		}
	}
	// Keep what the app sent, for its alarms and the like.
	if err := os.MkdirAll(c.extras, 0o700); err == nil {
		_ = writeAtomic(c.extraPath(uid), encodeICS(cal))
	}
	id := ""
	if n, err := c.find(uid); err == nil {
		id, _ = n.field("famlogic_id")
	}
	if id != "" {
		return c.save(id, in, members)
	}
	got, err := c.save("", in, members)
	if err != nil {
		return got, err
	}
	// A new event keeps the calendar app's UID.
	c.mu.Lock()
	defer c.mu.Unlock()
	if n, err := c.find(got.ID); err == nil {
		noteWriteFront(n, [][2]string{{"uid", uid}})
		got.UID = uid
	}
	return got, nil
}

// ---- HTTP (the app and the console)

func (s *Server) calendarLevel(me *Member) string {
	if !s.store.Modules().Enabled["calendar"] {
		return LevelOff
	}
	return me.level("calendar")
}

func (s *Server) calendarSetup(w http.ResponseWriter, me *Member) bool {
	if s.cal == nil {
		writeError(w, http.StatusServiceUnavailable, "the calendar needs the notes vault, which isn't available on this server")
		return false
	}
	if s.calendarLevel(me) == LevelOff {
		writeError(w, http.StatusForbidden, "Datebook isn't switched on for you")
		return false
	}
	return true
}

func (s *Server) canEditCalendar(me *Member) bool {
	return isAdult(me) || me.ID == "admin" || strings.HasPrefix(me.ID, "agent:") || levelAtLeast(s.calendarLevel(me), LevelAdd)
}

// GET /api/events?from=<ms>&to=<ms> (default: today and the next 30 days)
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	now := time.Now()
	from := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.Local)
	to := from.AddDate(0, 0, 31)
	if v, err := strconv.ParseInt(r.URL.Query().Get("from"), 10, 64); err == nil && v > 0 {
		from = time.UnixMilli(v)
	}
	if v, err := strconv.ParseInt(r.URL.Query().Get("to"), 10, 64); err == nil && v > 0 {
		to = time.UnixMilli(v)
	}
	if to.Sub(from) > 400*24*time.Hour {
		to = from.Add(400 * 24 * time.Hour)
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": s.cal.occurrences(s.store.Members(), from, to), "canEdit": s.canEditCalendar(me)})
}

// GET /api/events/{id}: the event itself (not an occurrence).
func (s *Server) handleEvent(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	n, err := s.cal.find(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "no such event")
		return
	}
	writeJSON(w, http.StatusOK, s.cal.parse(n, s.store.Members()))
}

func (s *Server) handleSaveEvent(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	if !s.canEditCalendar(me) {
		writeError(w, http.StatusForbidden, "you can see the calendar, but not change it")
		return
	}
	var in EventInput
	if !readJSON(w, r, &in) {
		return
	}
	id := r.PathValue("id")
	if msg := in.check(id == ""); msg != "" {
		writeError(w, http.StatusBadRequest, msg)
		return
	}
	ev, err := s.cal.save(id, in, s.store.Members())
	if errors.Is(err, errNoEvent) {
		writeError(w, http.StatusNotFound, "no such event")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the event")
		return
	}
	writeJSON(w, http.StatusOK, ev)
}

func (s *Server) handleDeleteEvent(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.calendarSetup(w, me) {
		return
	}
	if !s.canEditCalendar(me) {
		writeError(w, http.StatusForbidden, "you can see the calendar, but not change it")
		return
	}
	if err := s.cal.archive(r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such event")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
