package main

// Family meetings: a parent writes a short agenda (or asks Steward to), then the family
// clicks through it on the TV, a tablet or the console, like slides. On each slide the
// family can hand out jobs (to someone, or asking for a volunteer) and keep notes; when
// it's over, the jobs go onto the Docket. A meeting is a note in the vault's Meetings/
// folder, readable and editable anywhere:
//
//	---
//	type: meeting
//	date: 2026-11-29
//	---
//	# Sunday family meeting
//
//	## Weekend plans
//	The park on Saturday, or the pool?
//
//	- [ ] Pack the picnic @Sam
//	- [ ] Bring the football
//	> We'll go to the park if it's dry.
//
// Each "## " heading is a slide, the text under it is what's on the slide, "- [ ]"
// lines are jobs (with "@Name" when someone has it; without, it needs a volunteer) and
// "> " lines are the notes. A job sent to the Docket is marked "📋".

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type MeetingJob struct {
	Text string `json:"text"`
	Who  string `json:"who,omitempty"`  // member id; "" = needs a volunteer
	Sent bool   `json:"sent,omitempty"` // on the Docket already
}

type MeetingSlide struct {
	Title string       `json:"title"`
	Text  string       `json:"text,omitempty"`
	Jobs  []MeetingJob `json:"jobs"`
	Notes []string     `json:"notes"`
}

type Meeting struct {
	ID     string         `json:"id"`
	Title  string         `json:"title"`
	Date   string         `json:"date,omitempty"` // YYYY-MM-DD
	Slides []MeetingSlide `json:"slides"`
	File   string         `json:"file"`
}

type meetings struct {
	dir string
	mu  sync.Mutex
}

func newMeetings(v *Vault) *meetings {
	if v == nil {
		return nil
	}
	return &meetings{dir: filepath.Join(v.dir, "Meetings")}
}

var meetingSentRe = regexp.MustCompile(`\s*📋\s*$`)

func (mt *meetings) parse(n *note, members []Member) Meeting {
	id, _ := n.field("famlogic_id")
	date, _ := n.field("date")
	m := Meeting{ID: id, Date: date, Slides: []MeetingSlide{}, File: filepath.Join("Meetings", filepath.Base(n.path))}
	var cur *MeetingSlide
	var text []string
	flush := func() {
		if cur != nil {
			cur.Text = strings.TrimSpace(strings.Join(text, "\n"))
			m.Slides = append(m.Slides, *cur)
		}
		text = nil
	}
	for _, line := range strings.Split(n.body, "\n") {
		t := strings.TrimRight(line, " \t")
		switch {
		case strings.HasPrefix(t, "# ") && m.Title == "":
			m.Title = strings.TrimSpace(t[2:])
		case strings.HasPrefix(t, "## "):
			flush()
			cur = &MeetingSlide{Title: strings.TrimSpace(t[3:]), Jobs: []MeetingJob{}, Notes: []string{}}
		case cur == nil:
			continue
		case taskLineRe.MatchString(t):
			g := taskLineRe.FindStringSubmatch(t)
			job := MeetingJob{Text: g[3]}
			if meetingSentRe.MatchString(job.Text) {
				job.Sent, job.Text = true, meetingSentRe.ReplaceAllString(job.Text, "")
			}
			if w := whoMarkRe.FindStringSubmatch(job.Text); w != nil {
				if mem := memberByName(members, strings.ReplaceAll(w[1], "_", " ")); mem != nil {
					job.Who = mem.ID
				}
				job.Text = strings.TrimSpace(whoMarkRe.ReplaceAllString(job.Text, ""))
			}
			cur.Jobs = append(cur.Jobs, job)
		case strings.HasPrefix(t, ">"):
			if s := strings.TrimSpace(strings.TrimPrefix(t, ">")); s != "" {
				cur.Notes = append(cur.Notes, s)
			}
		default:
			text = append(text, t)
		}
	}
	flush()
	if m.Title == "" {
		m.Title = strings.TrimSuffix(filepath.Base(n.path), ".md")
	}
	return m
}

// render writes a meeting back as its note's body.
func renderMeeting(m Meeting, members []Member) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# %s\n", m.Title)
	for _, s := range m.Slides {
		fmt.Fprintf(&b, "\n## %s\n", s.Title)
		if s.Text != "" {
			b.WriteString(s.Text + "\n")
		}
		if len(s.Jobs) > 0 {
			b.WriteString("\n")
		}
		for _, j := range s.Jobs {
			line := "- [ ] " + j.Text
			for _, mem := range members {
				if mem.ID == j.Who {
					line += " @" + strings.ReplaceAll(noteName(mem.Name), " ", "_")
				}
			}
			if j.Sent {
				line += " 📋"
			}
			b.WriteString(line + "\n")
		}
		for _, n := range s.Notes {
			b.WriteString("> " + n + "\n")
		}
	}
	return b.String()
}

func (mt *meetings) all(members []Member) []Meeting {
	out := []Meeting{}
	entries, _ := os.ReadDir(mt.dir)
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(mt.dir, e.Name()))
		if err != nil {
			continue
		}
		if t, _ := n.field("type"); t != "meeting" {
			continue
		}
		if id, _ := n.field("famlogic_id"); id == "" {
			_ = noteWriteFront(n, [][2]string{{"famlogic_id", "m" + randomHex(5)}})
			if n, err = readNote(n.path); err != nil {
				continue
			}
		}
		out = append(out, mt.parse(n, members))
	}
	// Newest (or next) first.
	sort.Slice(out, func(i, j int) bool { return out[i].Date > out[j].Date })
	return out
}

func (mt *meetings) find(id string) (*note, error) {
	entries, _ := os.ReadDir(mt.dir)
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(mt.dir, e.Name()))
		if err != nil {
			continue
		}
		if v, _ := n.field("famlogic_id"); v == id {
			return n, nil
		}
	}
	return nil, ErrNotFound
}

// change loads a meeting, lets fn change it, and writes it back.
func (mt *meetings) change(id string, members []Member, fn func(m *Meeting) error) (Meeting, error) {
	mt.mu.Lock()
	defer mt.mu.Unlock()
	n, err := mt.find(id)
	if err != nil {
		return Meeting{}, err
	}
	m := mt.parse(n, members)
	if err := fn(&m); err != nil {
		return Meeting{}, err
	}
	n.body = renderMeeting(m, members)
	if err := noteWriteBody(n); err != nil {
		return Meeting{}, err
	}
	if m.Date != "" {
		_ = noteWriteFront(n, [][2]string{{"date", m.Date}})
	}
	return m, nil
}

func (mt *meetings) create(m Meeting, members []Member) (Meeting, error) {
	mt.mu.Lock()
	defer mt.mu.Unlock()
	if err := os.MkdirAll(mt.dir, 0o755); err != nil {
		return Meeting{}, err
	}
	if m.Date == "" {
		m.Date = time.Now().Format("2006-01-02")
	}
	m.ID = "m" + randomHex(5)
	base := noteName(m.Date + " " + m.Title)
	path := filepath.Join(mt.dir, base+".md")
	for i := 2; ; i++ {
		if _, err := os.Stat(path); os.IsNotExist(err) {
			break
		}
		path = filepath.Join(mt.dir, fmt.Sprintf("%s (%d).md", base, i))
	}
	text := fmt.Sprintf("---\ntype: meeting\ndate: %s\nfamlogic_id: %s\n---\n%s", m.Date, m.ID, renderMeeting(m, members))
	if err := writeAtomic(path, []byte(text)); err != nil {
		return Meeting{}, err
	}
	m.File = filepath.Join("Meetings", filepath.Base(path))
	return m, nil
}

// ---- HTTP

func (s *Server) meetingsOn(w http.ResponseWriter, me *Member) bool {
	return s.moduleOn(w, me, "meetings", s.meetings != nil)
}

// Parents (and the admin) write meetings; the TV, a family display, can run one
// (hand out jobs, take notes) since the family is gathered round it.
func mayEditMeetings(me *Member) bool { return me.ID == "admin" || me.role() == RoleParent }
func mayRunMeetings(me *Member) bool  { return mayEditMeetings(me) || me.Display }

func (s *Server) meetingReply(w http.ResponseWriter, me *Member, m Meeting, err error) {
	switch {
	case err == ErrNotFound:
		writeError(w, http.StatusNotFound, "no such meeting")
	case err != nil:
		writeError(w, http.StatusBadRequest, err.Error())
	default:
		writeJSON(w, http.StatusOK, map[string]any{"meeting": m, "canEdit": mayEditMeetings(me), "canRun": mayRunMeetings(me)})
	}
}

// GET /api/meetings: every meeting, newest first.
func (s *Server) handleMeetings(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"meetings": s.meetings.all(s.store.Members()), "canEdit": mayEditMeetings(me), "canRun": mayRunMeetings(me)})
}

// GET /api/meetings/{id}
func (s *Server) handleMeeting(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	n, err := s.meetings.find(r.PathValue("id"))
	if err != nil {
		s.meetingReply(w, me, Meeting{}, err)
		return
	}
	s.meetingReply(w, me, s.meetings.parse(n, s.store.Members()), nil)
}

// meetingInput is a whole meeting as sent by the app, console or Steward. Jobs can
// name their person by id or by name.
type meetingInput struct {
	Title  string `json:"title"`
	Date   string `json:"date"`
	Slides []struct {
		Title string `json:"title"`
		Text  string `json:"text"`
		Jobs  []struct {
			Text string `json:"text"`
			Who  string `json:"who"`
		} `json:"jobs"`
		Notes []string `json:"notes"`
	} `json:"slides"`
}

func (s *Server) meetingFromInput(in meetingInput) (Meeting, error) {
	m := Meeting{Title: strings.TrimSpace(in.Title), Date: strings.TrimSpace(in.Date), Slides: []MeetingSlide{}}
	if m.Title == "" || utf8.RuneCountInString(m.Title) > 80 {
		return m, fmt.Errorf("a meeting needs a title (80 characters at most)")
	}
	if m.Date != "" {
		if _, err := time.Parse("2006-01-02", m.Date); err != nil {
			return m, fmt.Errorf("the date should look like 2026-11-29")
		}
	}
	if len(in.Slides) > 40 {
		return m, fmt.Errorf("40 slides at most")
	}
	members := s.store.Members()
	for _, sl := range in.Slides {
		t := strings.TrimSpace(strings.ReplaceAll(sl.Title, "\n", " "))
		if t == "" {
			continue
		}
		slide := MeetingSlide{Title: t, Text: strings.TrimSpace(sl.Text), Jobs: []MeetingJob{}, Notes: []string{}}
		for _, j := range sl.Jobs {
			if txt := strings.TrimSpace(strings.ReplaceAll(j.Text, "\n", " ")); txt != "" {
				slide.Jobs = append(slide.Jobs, MeetingJob{Text: txt, Who: resolveWho(members, j.Who)})
			}
		}
		for _, n := range sl.Notes {
			if n = strings.TrimSpace(strings.ReplaceAll(n, "\n", " ")); n != "" {
				slide.Notes = append(slide.Notes, n)
			}
		}
		m.Slides = append(m.Slides, slide)
	}
	return m, nil
}

// resolveWho turns a member id or name into an id ("" if neither).
func resolveWho(members []Member, who string) string {
	who = strings.TrimSpace(strings.TrimPrefix(who, "@"))
	if who == "" {
		return ""
	}
	for _, m := range members {
		if m.ID == who {
			return m.ID
		}
	}
	if m := memberByName(members, who); m != nil {
		return m.ID
	}
	return ""
}

// POST /api/meetings: a new meeting (parents).
func (s *Server) handleCreateMeeting(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayEditMeetings(me) {
		writeError(w, http.StatusForbidden, "parents make the meetings")
		return
	}
	var in meetingInput
	if !readJSON(w, r, &in) {
		return
	}
	m, err := s.meetingFromInput(in)
	if err == nil {
		m, err = s.meetings.create(m, s.store.Members())
	}
	s.meetingReply(w, me, m, err)
}

// PUT /api/meetings/{id}: the whole meeting, changed (parents). Jobs already sent keep that mark.
func (s *Server) handleSaveMeeting(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayEditMeetings(me) {
		writeError(w, http.StatusForbidden, "parents change the meetings")
		return
	}
	var in meetingInput
	if !readJSON(w, r, &in) {
		return
	}
	next, err := s.meetingFromInput(in)
	if err != nil {
		s.meetingReply(w, me, Meeting{}, err)
		return
	}
	m, err := s.meetings.change(r.PathValue("id"), s.store.Members(), func(m *Meeting) error {
		sent := map[string]bool{}
		for _, sl := range m.Slides {
			for _, j := range sl.Jobs {
				if j.Sent {
					sent[sl.Title+"\x00"+j.Text] = true
				}
			}
		}
		for i := range next.Slides {
			for k := range next.Slides[i].Jobs {
				next.Slides[i].Jobs[k].Sent = sent[next.Slides[i].Title+"\x00"+next.Slides[i].Jobs[k].Text]
			}
		}
		m.Title, m.Slides = next.Title, next.Slides
		if next.Date != "" {
			m.Date = next.Date
		}
		return nil
	})
	s.meetingReply(w, me, m, err)
}

// POST /api/meetings/{id}/delete (parents): the note moves to the vault's trash.
func (s *Server) handleDeleteMeeting(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayEditMeetings(me) {
		writeError(w, http.StatusForbidden, "parents remove meetings")
		return
	}
	n, err := s.meetings.find(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "no such meeting")
		return
	}
	_ = noteWriteFront(n, [][2]string{{"type", "meeting-archived"}})
	w.WriteHeader(http.StatusNoContent)
}

// slideAt checks a slide number from the path.
func slideAt(m *Meeting, r *http.Request) (*MeetingSlide, error) {
	i, err := strconv.Atoi(r.PathValue("slide"))
	if err != nil || i < 0 || i >= len(m.Slides) {
		return nil, fmt.Errorf("no such slide")
	}
	return &m.Slides[i], nil
}

// POST /api/meetings/{id}/slides/{slide}/jobs {text, who}: a job on a slide, during the meeting.
func (s *Server) handleMeetingJob(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayRunMeetings(me) {
		writeError(w, http.StatusForbidden, "parents hand out the jobs")
		return
	}
	var body struct{ Text, Who string }
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.TrimSpace(strings.ReplaceAll(body.Text, "\n", " "))
	if text == "" || utf8.RuneCountInString(text) > 120 {
		writeError(w, http.StatusBadRequest, "a job needs a few words (120 characters at most)")
		return
	}
	members := s.store.Members()
	m, err := s.meetings.change(r.PathValue("id"), members, func(m *Meeting) error {
		sl, err := slideAt(m, r)
		if err != nil {
			return err
		}
		sl.Jobs = append(sl.Jobs, MeetingJob{Text: text, Who: resolveWho(members, body.Who)})
		return nil
	})
	s.meetingReply(w, me, m, err)
}

// POST /api/meetings/{id}/slides/{slide}/jobs/{job} {who}: give a job to someone
// (a volunteer), or back to "needs a volunteer" with who "". {remove: true} takes it off.
func (s *Server) handleMeetingJobWho(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayRunMeetings(me) {
		writeError(w, http.StatusForbidden, "parents hand out the jobs")
		return
	}
	var body struct {
		Who    string `json:"who"`
		Remove bool   `json:"remove"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	members := s.store.Members()
	m, err := s.meetings.change(r.PathValue("id"), members, func(m *Meeting) error {
		sl, err := slideAt(m, r)
		if err != nil {
			return err
		}
		j, err := strconv.Atoi(r.PathValue("job"))
		if err != nil || j < 0 || j >= len(sl.Jobs) {
			return fmt.Errorf("no such job")
		}
		if body.Remove {
			sl.Jobs = append(sl.Jobs[:j], sl.Jobs[j+1:]...)
			return nil
		}
		sl.Jobs[j].Who = resolveWho(members, body.Who)
		return nil
	})
	s.meetingReply(w, me, m, err)
}

// POST /api/meetings/{id}/slides/{slide}/notes {text}: a note on a slide (the minutes).
func (s *Server) handleMeetingNote(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayRunMeetings(me) {
		writeError(w, http.StatusForbidden, "parents keep the notes")
		return
	}
	var body struct{ Text string }
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.TrimSpace(strings.ReplaceAll(body.Text, "\n", " "))
	if text == "" || utf8.RuneCountInString(text) > 300 {
		writeError(w, http.StatusBadRequest, "a note of 300 characters at most")
		return
	}
	m, err := s.meetings.change(r.PathValue("id"), s.store.Members(), func(m *Meeting) error {
		sl, err := slideAt(m, r)
		if err != nil {
			return err
		}
		sl.Notes = append(sl.Notes, text)
		return nil
	})
	s.meetingReply(w, me, m, err)
}

// POST /api/meetings/{id}/send: the meeting's jobs that have someone go onto the Docket
// (a to-do list called "From family meetings"), once each. Jobs still needing a
// volunteer stay on the meeting.
func (s *Server) handleMeetingSend(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.meetingsOn(w, me) {
		return
	}
	if !mayRunMeetings(me) {
		writeError(w, http.StatusForbidden, "parents send the jobs")
		return
	}
	if s.lists == nil {
		writeError(w, http.StatusServiceUnavailable, "the Docket isn't available")
		return
	}
	members := s.store.Members()
	sent := 0
	m, err := s.meetings.change(r.PathValue("id"), members, func(m *Meeting) error {
		var listID string
		for _, l := range s.lists.all(members) {
			if l.Kind == "todos" && l.Title == "From family meetings" {
				listID = l.ID
			}
		}
		for i := range m.Slides {
			for k := range m.Slides[i].Jobs {
				j := &m.Slides[i].Jobs[k]
				if j.Sent || j.Who == "" {
					continue
				}
				if listID == "" {
					l, err := s.lists.create("From family meetings", "todos", nil, members)
					if err != nil {
						return err
					}
					listID = l.ID
				}
				if _, err := s.lists.addItem(listID, j.Text, j.Who, "", "", members); err != nil {
					return err
				}
				j.Sent = true
				sent++
			}
		}
		return nil
	})
	if err != nil {
		s.meetingReply(w, me, m, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"meeting": m, "sent": sent, "canEdit": mayEditMeetings(me), "canRun": mayRunMeetings(me)})
}
