package main

// Bartering: a child offers one of their chores to a brother or sister for some of
// their points. If the other child accepts, the chore is theirs (for its next time,
// if it repeats); when they've done it, the points move. Everything shows in the
// family feed, and a parent can undo any trade.

import (
	"net/http"
	"slices"
	"time"
)

type Barter struct {
	ID         string `json:"id"`
	ChoreID    string `json:"choreId"`
	ChoreTitle string `json:"choreTitle"`
	From       string `json:"from"` // who's handing the chore over (and paying)
	To         string `json:"to"`   // who's taking it on (and earning)
	Points     int    `json:"points"`
	// offered, accepted, declined, cancelled, done, undone
	Status  string `json:"status"`
	Created int64  `json:"created"`
	Settled int64  `json:"settled,omitempty"`
}

func (s *Store) Barters() []Barter {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Barter, 0, len(s.d.Barters))
	for i := len(s.d.Barters) - 1; i >= 0 && len(out) < 60; i-- {
		out = append(out, *s.d.Barters[i])
	}
	return out
}

func (s *Store) barterLocked(id string) *Barter {
	for _, b := range s.d.Barters {
		if b.ID == id {
			return b
		}
	}
	return nil
}

// changeBarter runs fn on a trade under the lock and saves.
func (s *Store) changeBarter(id string, fn func(b *Barter) error) (Barter, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	b := s.barterLocked(id)
	if b == nil {
		return Barter{}, ErrNotFound
	}
	if err := fn(b); err != nil {
		return Barter{}, err
	}
	return *b, s.saveLocked()
}

func (s *Store) addBarter(b *Barter) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, x := range s.d.Barters {
		if x.ChoreID == b.ChoreID && (x.Status == "offered" || x.Status == "accepted") {
			return &httpError{http.StatusConflict, "that chore is already up for trade"}
		}
	}
	s.d.Barters = append(s.d.Barters, b)
	if len(s.d.Barters) > 300 {
		s.d.Barters = s.d.Barters[len(s.d.Barters)-300:]
	}
	return s.saveLocked()
}

func (s *Server) barterSetup(w http.ResponseWriter, me *Member) bool {
	if !s.choreSetup(w, me) {
		return false
	}
	return true
}

func (s *Server) member(id string) (Member, bool) {
	for _, m := range s.store.Members() {
		if m.ID == id {
			return m, true
		}
	}
	return Member{}, false
}

func (s *Server) barterFeed(b Barter, kind string) {
	from, _ := s.member(b.From)
	to, _ := s.member(b.To)
	s.store.AddTimeline(Event{Type: EventBarter, Kind: kind, MemberID: b.From, Name: from.Name, Title: b.ChoreTitle, Level: b.Points, Note: to.Name})
}

// moveChore gives a chore to someone instead of someone else.
func (s *Server) moveChore(choreID, from, to string) error {
	people := s.chorePeople()
	list, err := s.chores.list(people, false)
	if err != nil {
		return err
	}
	i := slices.IndexFunc(list, func(c Chore) bool { return c.ID == choreID })
	if i < 0 {
		return errNoChore
	}
	who := []string{}
	for _, id := range list[i].Who {
		if id != from && id != to {
			who = append(who, id)
		}
	}
	who = append(who, to)
	_, err = s.chores.update(choreID, ChoreInput{Who: &who}, people)
	return err
}

// GET /api/barters: recent trades.
func (s *Server) handleBarters(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.barterSetup(w, me) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"barters": s.store.Barters()})
}

// POST /api/barters {choreId, to, points}: offer one of your chores to a brother or sister.
func (s *Server) handleOfferBarter(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.barterSetup(w, me) {
		return
	}
	var body struct {
		ChoreID string `json:"choreId"`
		To      string `json:"to"`
		Points  int    `json:"points"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	to, ok := s.member(body.To)
	if me.role() != RoleChild || !ok || to.role() != RoleChild || to.ID == me.ID {
		writeError(w, http.StatusForbidden, "chores are traded between brothers and sisters")
		return
	}
	if body.Points < 1 || body.Points > 1000 {
		writeError(w, http.StatusBadRequest, "offer 1 point or more")
		return
	}
	promised := 0 // points already offered in trades still open
	for _, b := range s.store.Barters() {
		if b.From == me.ID && (b.Status == "offered" || b.Status == "accepted") {
			promised += b.Points
		}
	}
	if body.Points+promised > me.Stars {
		writeError(w, http.StatusBadRequest, "you don't have that many points")
		return
	}
	list, _ := s.chores.list(s.chorePeople(), false)
	i := slices.IndexFunc(list, func(c Chore) bool { return c.ID == body.ChoreID })
	if i < 0 || list[i].Finished {
		writeError(w, http.StatusNotFound, "no such chore")
		return
	}
	if !slices.Contains(list[i].Who, me.ID) {
		writeError(w, http.StatusForbidden, "you can only trade your own chores")
		return
	}
	b := &Barter{ID: randomString(9), ChoreID: body.ChoreID, ChoreTitle: list[i].Title, From: me.ID, To: to.ID, Points: body.Points,
		Status: "offered", Created: time.Now().UnixMilli()}
	if err := s.store.addBarter(b); err != nil {
		if he, ok := err.(*httpError); ok {
			writeError(w, he.code, he.msg)
			return
		}
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	s.store.ChoreEvents([]string{to.ID}, Event{Type: EventChore, Kind: "barter", MemberID: me.ID, Name: me.Name, Title: b.ChoreTitle, Level: b.Points, Note: b.ID})
	s.barterFeed(*b, "offered")
	writeJSON(w, http.StatusOK, b)
}

// POST /api/barters/{id}/{accept|decline|cancel|undo}
func (s *Server) handleBarterAction(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.barterSetup(w, me) {
		return
	}
	action := r.PathValue("action")
	var prev Barter
	b, err := s.store.changeBarter(r.PathValue("id"), func(b *Barter) error {
		prev = *b
		switch action {
		case "accept", "decline":
			if b.To != me.ID || b.Status != "offered" {
				return &httpError{http.StatusForbidden, "this offer isn't yours to answer"}
			}
			b.Status = map[string]string{"accept": "accepted", "decline": "declined"}[action]
		case "cancel":
			if b.From != me.ID || b.Status != "offered" {
				return &httpError{http.StatusForbidden, "only an offer you made, before it's answered"}
			}
			b.Status = "cancelled"
		case "undo":
			if me.role() != RoleParent || (b.Status != "accepted" && b.Status != "done") {
				return &httpError{http.StatusForbidden, "a parent can undo a trade that went ahead"}
			}
			b.Status = "undone"
		default:
			return &httpError{http.StatusNotFound, "unknown action"}
		}
		b.Settled = time.Now().UnixMilli()
		return nil
	})
	if err != nil {
		if he, ok := err.(*httpError); ok {
			writeError(w, he.code, he.msg)
			return
		}
		writeError(w, http.StatusNotFound, "no such trade")
		return
	}
	switch action {
	case "accept":
		if err := s.moveChore(b.ChoreID, b.From, b.To); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't move the chore")
			return
		}
		s.barterFeed(b, "accepted")
	case "undo":
		if prev.Status == "accepted" {
			s.moveChore(b.ChoreID, b.To, b.From)
		} else { // done: the points go back
			s.store.AddStars(b.To, -b.Points)
			s.store.AddStars(b.From, b.Points)
		}
		s.barterFeed(b, "undone")
	}
	writeJSON(w, http.StatusOK, b)
}

// settleBarters runs when a chore is ticked off: an accepted trade for it, done by
// the one who took it on, moves the points (and a repeating chore goes back).
func (s *Server) settleBarters(choreID, byID string, repeats bool) {
	var done []Barter
	s.store.mu.Lock()
	for _, b := range s.store.d.Barters {
		if b.ChoreID == choreID && b.Status == "accepted" && b.To == byID {
			b.Status, b.Settled = "done", time.Now().UnixMilli()
			done = append(done, *b)
		}
	}
	if len(done) > 0 {
		_ = s.store.saveLocked()
	}
	s.store.mu.Unlock()
	for _, b := range done {
		s.store.AddStars(b.From, -b.Points)
		s.store.AddStars(b.To, b.Points)
		if repeats {
			s.moveChore(b.ChoreID, b.To, b.From)
		}
		s.barterFeed(b, "done")
	}
}
