package main

// Rhythms, built and banked: a routine can be changed, switched off (kept in the bank
// for later), and started by hand for someone (from the app, the console, a home
// tile or Steward), as well as coming round at its time. Several can be on at once:
// a person's routines now are the timed one plus any started today and not finished.

import (
	"net/http"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

type RoutineStart struct {
	RoutineID string   `json:"routineId"`
	Who       []string `json:"who"`
	At        int64    `json:"at"`
	By        string   `json:"by,omitempty"`
	Stopped   bool     `json:"stopped,omitempty"`
}

func sameDay(a, b time.Time) bool {
	y1, m1, d1 := a.Date()
	y2, m2, d2 := b.Date()
	return y1 == y2 && m1 == m2 && d1 == d2
}

// activeRoutines: for each person, every routine on for them now.
func (s *Server) activeRoutines(all []List, members []Member, now map[string]string) map[string][]string {
	out := map[string][]string{}
	for id, r := range now {
		out[id] = []string{r}
	}
	unfinished := func(id string) bool {
		i := slices.IndexFunc(all, func(l List) bool { return l.ID == id })
		return i >= 0 && slices.ContainsFunc(all[i].Items, func(it ListItem) bool { return !it.Done })
	}
	s.store.mu.RLock()
	starts := append([]RoutineStart(nil), s.store.d.RoutineStarts...)
	s.store.mu.RUnlock()
	today := time.Now()
	for _, st := range starts {
		if st.Stopped || !sameDay(time.UnixMilli(st.At), today) || !unfinished(st.RoutineID) {
			continue
		}
		for _, who := range st.Who {
			if !slices.Contains(out[who], st.RoutineID) {
				out[who] = append(out[who], st.RoutineID)
			}
		}
	}
	return out
}

func (s *Server) canEditRoutines(me *Member) bool {
	return isAdult(me) || levelAtLeast(me.level("routines"), LevelFull)
}

// PUT /api/routines/{id} {title?, who?, starts?, days?, items?, paused?}
func (s *Server) handleUpdateRoutine(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "routines", s.routines != nil) {
		return
	}
	if !s.canEditRoutines(me) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	var body struct {
		Title  *string   `json:"title"`
		Who    *[]string `json:"who"`
		Starts *string   `json:"starts"`
		Days   *[]string `json:"days"`
		Items  *[]string `json:"items"`
		Paused *bool     `json:"paused"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	members := s.store.Members()
	n, _, err := s.routines.find(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusNotFound, "no such routine")
		return
	}
	fields := [][2]string{}
	if body.Starts != nil {
		if *body.Starts != "" && !clockOnly.MatchString(*body.Starts) {
			writeError(w, http.StatusBadRequest, "starts is a time, HH:MM")
			return
		}
		fields = append(fields, [2]string{"starts", yamlString(*body.Starts)})
	}
	if body.Days != nil {
		fields = append(fields, [2]string{"days", "[" + strings.Join(lower(*body.Days), ", ") + "]"})
	}
	if body.Who != nil {
		fields = append(fields, [2]string{"who", whoLinks(*body.Who, members)})
	}
	if body.Paused != nil {
		v := "false"
		if *body.Paused {
			v = "true"
		}
		fields = append(fields, [2]string{"paused", v})
	}
	if len(fields) > 0 {
		if err := noteWriteFront(n, fields); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't save")
			return
		}
	}
	if body.Title != nil || body.Items != nil {
		lines := strings.Split(n.body, "\n")
		var keep []string
		for _, l := range lines {
			if body.Items != nil && taskLineRe.MatchString(l) {
				continue
			}
			if body.Title != nil && strings.HasPrefix(l, "# ") {
				t := strings.TrimSpace(*body.Title)
				if t == "" || utf8.RuneCountInString(t) > 60 {
					writeError(w, http.StatusBadRequest, "a routine needs a name")
					return
				}
				l = "# " + t
			}
			keep = append(keep, l)
		}
		body2 := strings.TrimRight(strings.Join(keep, "\n"), "\n") + "\n"
		if body.Items != nil {
			body2 += "\n"
			for _, it := range *body.Items {
				if t := strings.TrimSpace(strings.ReplaceAll(it, "\n", " ")); t != "" {
					body2 += "- [ ] " + t + "\n"
				}
			}
		}
		n.body = body2
		if err := noteWriteBody(n); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't save")
			return
		}
	}
	l, _ := s.routines.get(r.PathValue("id"), members)
	writeJSON(w, http.StatusOK, l)
}

// POST /api/routines/{id}/start {who}: start a routine now for these people (their
// ticks start fresh), on top of whatever's on. A display's id puts it on that TV.
// POST /api/routines/{id}/stop {who?}: take it off again.
func (s *Server) handleStartRoutine(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "routines", s.routines != nil) {
		return
	}
	if a := r.PathValue("action"); a != "start" && a != "stop" {
		http.NotFound(w, r)
		return
	}
	members := s.store.Members()
	l, err := s.routines.get(r.PathValue("id"), members)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such routine")
		return
	}
	var body struct {
		Who []string `json:"who"`
	}
	if r.ContentLength != 0 && !readJSON(w, r, &body) {
		return
	}
	who := []string{}
	for _, id := range body.Who {
		if slices.ContainsFunc(members, func(m Member) bool { return m.ID == id }) {
			who = append(who, id)
		}
	}
	if len(who) == 0 {
		who = []string{me.ID}
	}
	// Children start their own; grown-ups start anyone's.
	if !s.canEditRoutines(me) && (len(who) != 1 || who[0] != me.ID) {
		writeError(w, http.StatusForbidden, "you can start a routine for yourself")
		return
	}
	stop := r.PathValue("action") == "stop"
	s.store.mu.Lock()
	today := time.Now()
	starts := s.store.d.RoutineStarts[:0]
	for _, st := range s.store.d.RoutineStarts {
		if sameDay(time.UnixMilli(st.At), today) { // only today's are kept
			starts = append(starts, st)
		}
	}
	if stop {
		for i := range starts {
			if starts[i].RoutineID == l.ID {
				starts[i].Who = slices.DeleteFunc(starts[i].Who, func(id string) bool { return slices.Contains(who, id) })
				if len(starts[i].Who) == 0 {
					starts[i].Stopped = true
				}
			}
		}
	} else {
		starts = append(starts, RoutineStart{RoutineID: l.ID, Who: who, At: time.Now().UnixMilli(), By: me.ID})
	}
	s.store.d.RoutineStarts = starts
	err = s.store.saveLocked()
	s.store.mu.Unlock()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	if !stop {
		// A fresh start: the ticks clear.
		if n, _, err := s.routines.find(l.ID); err == nil {
			lines := strings.Split(n.body, "\n")
			for i, ln := range lines {
				if m := taskLineRe.FindStringSubmatch(ln); m != nil && m[2] != " " {
					lines[i] = m[1] + "- [ ] " + strings.TrimSpace(doneMarkRe.ReplaceAllString(m[3], ""))
				}
			}
			n.body = strings.Join(lines, "\n")
			_ = noteWriteBody(n)
		}
		var to []string
		for _, id := range who {
			if id != me.ID {
				to = append(to, id)
			}
		}
		s.store.ChoreEvents(to, Event{Type: EventRoutineStart, MemberID: me.ID, Name: me.Name, Title: l.Title, Note: l.ID})
	}
	l, _ = s.routines.get(l.ID, members)
	writeJSON(w, http.StatusOK, l)
}
