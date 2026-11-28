package main

import (
	"errors"
	"net/http"
	"slices"
	"strings"
	"unicode/utf8"
)

// ---- stars

// AddStars changes someone's stars (never below 0) and returns the new total.
func (s *Store) AddStars(id string, delta int) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return 0, ErrNotFound
	}
	m.Stars = max(0, m.Stars+delta)
	return m.Stars, s.saveLocked()
}

// SetStars sets someone's stars (a parent resetting or spending them).
func (s *Store) SetStars(id string, n int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	m.Stars = max(0, n)
	return s.saveLocked()
}

// ChoreEvents tells people about a chore (alerts to one person each).
func (s *Store) ChoreEvents(to []string, e Event) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, id := range to {
		if _, ok := s.d.Members[id]; ok {
			ev := e
			ev.To = id
			s.addEventLocked(ev)
		}
	}
	_ = s.saveLocked()
}

// ---- who may do what

// choresLevel is this person's access to Upkeep ("off" if the family doesn't have it).
func (s *Server) choresLevel(me *Member) string {
	if !s.store.Modules().Enabled["chores"] {
		return LevelOff
	}
	return me.level("chores")
}

// canEditChores: adults, and children a parent has given "Full".
func (s *Server) canEditChores(me *Member) bool {
	return isAdult(me) || levelAtLeast(s.choresLevel(me), LevelFull)
}

func (s *Server) choreSetup(w http.ResponseWriter, me *Member) bool {
	if s.chores == nil {
		writeError(w, http.StatusServiceUnavailable, "chores need the notes vault, which isn't available on this server")
		return false
	}
	if s.choresLevel(me) == LevelOff {
		writeError(w, http.StatusForbidden, "Upkeep isn't switched on for you")
		return false
	}
	return true
}

func (s *Server) chorePeople() []Member { return s.store.Members() }

func validChoreInput(in ChoreInput, members []Member, creating bool) string {
	if creating && (in.Title == nil || strings.TrimSpace(*in.Title) == "") {
		return "a chore needs a name"
	}
	if in.Title != nil && utf8.RuneCountInString(strings.TrimSpace(*in.Title)) > 80 {
		return "keep the name to 80 characters"
	}
	if in.Every != nil && !validEvery(*in.Every) {
		return `"every" is day, weekdays, week, month, "2 weeks", "3 days" or days like "mon,thu" (or empty for once)`
	}
	if in.Due != nil && *in.Due != "" && !dateRe.MatchString(*in.Due) {
		return "due is a date: YYYY-MM-DD"
	}
	if in.Stars != nil && (*in.Stars < 0 || *in.Stars > 100) {
		return "stars: 0 to 100"
	}
	if in.Notes != nil && utf8.RuneCountInString(*in.Notes) > 2000 {
		return "notes: 2000 characters at most"
	}
	if in.Who != nil {
		for _, id := range *in.Who {
			if !slices.ContainsFunc(members, func(m Member) bool { return m.ID == id }) {
				return "who: unknown person " + id
			}
		}
	}
	return ""
}

// ---- HTTP

// choreAuth: phones, agents and parents' console logins as usual; the main admin's
// console login (which isn't a family member) acts as a grown-up called "Admin".
func (s *Server) choreAuth(h deviceHandler) http.HandlerFunc {
	dev := s.device(h)
	return func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
			if sess := s.session(r); sess.Valid() && sess.Member == nil && sess.Admin() {
				h(w, r, &Member{ID: "admin", Name: "Admin", Role: RoleParent})
				return
			}
		}
		dev(w, r)
	}
}

// GET /api/chores: every chore, and everyone's stars.
func (s *Server) handleChores(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	people := s.chorePeople()
	list, err := s.chores.list(people, r.URL.Query().Get("archived") == "1")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't read the chores")
		return
	}
	stars := map[string]int{}
	for _, m := range people {
		if m.Stars > 0 {
			stars[m.ID] = m.Stars
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"chores": list, "stars": stars, "today": today(),
		"canEdit": s.canEditChores(me), "level": s.choresLevel(me)})
}

// POST /api/chores: a new chore. Children allowed to "add" may only make their own.
func (s *Server) handleCreateChore(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	var in ChoreInput
	if !readJSON(w, r, &in) {
		return
	}
	people := s.chorePeople()
	if msg := validChoreInput(in, people, true); msg != "" {
		writeError(w, http.StatusBadRequest, msg)
		return
	}
	if !s.canEditChores(me) {
		own := in.Who != nil && len(*in.Who) == 1 && (*in.Who)[0] == me.ID
		if !levelAtLeast(s.choresLevel(me), LevelAdd) || !own {
			writeError(w, http.StatusForbidden, "you can only add chores for yourself")
			return
		}
		in.Stars = nil // stars are for parents to give
	}
	ch, err := s.chores.create(in, people)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the chore")
		return
	}
	// Tell the people it's for (not whoever made it).
	var to []string
	for _, id := range ch.Who {
		if id != me.ID {
			to = append(to, id)
		}
	}
	s.store.ChoreEvents(to, Event{Type: EventChore, Kind: "new", MemberID: me.ID, Name: me.Name, Title: ch.Title, Note: ch.ID})
	writeJSON(w, http.StatusOK, ch)
}

// PATCH /api/chores/{id}
func (s *Server) handleUpdateChore(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	if !s.canEditChores(me) {
		writeError(w, http.StatusForbidden, "only a parent can change chores")
		return
	}
	var in ChoreInput
	if !readJSON(w, r, &in) {
		return
	}
	people := s.chorePeople()
	if msg := validChoreInput(in, people, false); msg != "" {
		writeError(w, http.StatusBadRequest, msg)
		return
	}
	ch, err := s.chores.update(r.PathValue("id"), in, people)
	if errors.Is(err, errNoChore) {
		writeError(w, http.StatusNotFound, "no such chore")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the chore")
		return
	}
	writeJSON(w, http.StatusOK, ch)
}

// DELETE /api/chores/{id}: retired (kept in the vault, hidden in the apps).
func (s *Server) handleArchiveChore(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	if !s.canEditChores(me) {
		writeError(w, http.StatusForbidden, "only a parent can remove chores")
		return
	}
	if err := s.chores.archive(r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such chore")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/chores/{id}/done {date?, by?}: ticked off. By someone it's for (or
// anyone, if it's for nobody in particular); adults and agents can tick for others.
func (s *Server) handleChoreDone(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	var body struct {
		Date string `json:"date"`
		By   string `json:"by"`
	}
	if r.ContentLength != 0 && !readJSON(w, r, &body) {
		return
	}
	if body.Date != "" && !dateRe.MatchString(body.Date) {
		writeError(w, http.StatusBadRequest, "date is YYYY-MM-DD")
		return
	}
	people := s.chorePeople()
	by := me
	if body.By != "" && body.By != me.ID {
		if !isAdult(me) {
			writeError(w, http.StatusForbidden, "you can only tick off your own chores")
			return
		}
		i := slices.IndexFunc(people, func(m Member) bool { return m.ID == body.By })
		if i < 0 {
			writeError(w, http.StatusBadRequest, "by: unknown person")
			return
		}
		by = &people[i]
	}
	list, _ := s.chores.list(people, false)
	i := slices.IndexFunc(list, func(c Chore) bool { return c.ID == r.PathValue("id") })
	if i < 0 {
		writeError(w, http.StatusNotFound, "no such chore")
		return
	}
	if !isAdult(me) && len(list[i].Who) > 0 && !slices.Contains(list[i].Who, me.ID) {
		writeError(w, http.StatusForbidden, "that chore is someone else's")
		return
	}
	ch, changed, err := s.chores.tick(r.PathValue("id"), body.Date, by.Name, people)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the chore")
		return
	}
	if changed {
		if ch.Stars > 0 {
			s.store.AddStars(by.ID, ch.Stars)
		}
		s.settleBarters(ch.ID, by.ID, ch.Every != "")
		s.store.AddTimeline(Event{Type: EventChoreDone, MemberID: by.ID, Name: by.Name, Title: ch.Title, Level: ch.Stars, Note: ch.ID})
		// Parents hear about it (not the one who did it).
		var to []string
		for _, m := range people {
			if m.role() == RoleParent && m.ID != by.ID && m.ID != me.ID {
				to = append(to, m.ID)
			}
		}
		s.store.ChoreEvents(to, Event{Type: EventChore, Kind: "done", MemberID: by.ID, Name: by.Name, Title: ch.Title, Level: ch.Stars, Note: ch.ID})
	}
	writeJSON(w, http.StatusOK, ch)
}

// POST /api/chores/{id}/undo {date?}: a parent takes a tick back (and its stars).
func (s *Server) handleChoreUndo(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	var body struct {
		Date string `json:"date"`
	}
	if r.ContentLength != 0 && !readJSON(w, r, &body) {
		return
	}
	people := s.chorePeople()
	// Grown-ups can take back anyone's tick; a child, only their own (a misclick,
	// or not quite done after all).
	if !isAdult(me) {
		list, _ := s.chores.list(people, false)
		mine := false
		for _, c := range list {
			if c.ID != r.PathValue("id") || len(c.Done) == 0 {
				continue
			}
			date := body.Date
			if date == "" {
				date = c.Done[0].Date
			}
			for _, d := range c.Done {
				if d.Date == date && d.By == me.ID {
					mine = true
				}
			}
		}
		if !mine {
			writeError(w, http.StatusForbidden, "only the person who ticked it, or a grown-up, can take it back")
			return
		}
	}
	ch, by, err := s.chores.untick(r.PathValue("id"), body.Date, people)
	if errors.Is(err, errNoChore) {
		writeError(w, http.StatusNotFound, "no such chore")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the chore")
		return
	}
	if by != "" && ch.Stars > 0 {
		s.store.AddStars(by, -ch.Stars)
	}
	writeJSON(w, http.StatusOK, ch)
}

// POST /api/members/{id}/stars {add?, set?}: a parent gives, spends or resets stars.
func (s *Server) handleStars(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "only a parent can change stars")
		return
	}
	var body struct {
		Add *int `json:"add"`
		Set *int `json:"set"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	id := r.PathValue("id")
	var err error
	switch {
	case body.Set != nil:
		err = s.store.SetStars(id, *body.Set)
	case body.Add != nil:
		_, err = s.store.AddStars(id, *body.Add)
	default:
		writeError(w, http.StatusBadRequest, "send add or set")
		return
	}
	if err != nil {
		writeError(w, http.StatusNotFound, "no such person")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
