package main

// Rewards: what stars are for. Parents list rewards with a price in stars ("30
// minutes of screen time: 10"); a child asks for one, a parent approves (the stars
// come off) or declines. Kept in famlogic.json with the rest of the family's
// settings. Chore reminders live here too: a nudge to whoever a chore is for, at
// its time of day.

import (
	"context"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type Reward struct {
	ID    string   `json:"id"`
	Title string   `json:"title"`
	Cost  int      `json:"cost"`
	Who   []string `json:"who"` // the children it's for ([] = all of them)
}

type RewardAsk struct {
	ID       string `json:"id"`
	RewardID string `json:"rewardId"`
	Title    string `json:"title"`
	Cost     int    `json:"cost"`
	MemberID string `json:"memberId"`
	Time     int64  `json:"time"`
	Status   string `json:"status"` // asked, approved, declined
	By       string `json:"by,omitempty"`
}

const (
	EventReward   = "reward"       // to parents: someone asked (Kind "asked"); to the child: Kind "approved"/"declined"
	EventRewarded = "reward_spent" // the feed: someone spent stars on a reward
)

func (s *Store) Rewards() ([]Reward, []RewardAsk) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]Reward{}, s.d.Rewards...), append([]RewardAsk{}, s.d.RewardAsks...)
}

func (s *Store) AddReward(title string, cost int, who []string) (Reward, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if who == nil {
		who = []string{}
	}
	r := Reward{ID: "r" + randomString(8), Title: title, Cost: cost, Who: who}
	s.d.Rewards = append(s.d.Rewards, r)
	return r, s.saveLocked()
}

func (s *Store) UpdateReward(id, title string, cost int, who []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.d.Rewards, func(r Reward) bool { return r.ID == id })
	if i < 0 {
		return ErrNotFound
	}
	s.d.Rewards[i].Title, s.d.Rewards[i].Cost = title, cost
	if who != nil {
		s.d.Rewards[i].Who = who
	}
	return s.saveLocked()
}

func (s *Store) DeleteReward(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.d.Rewards, func(r Reward) bool { return r.ID == id })
	if i < 0 {
		return ErrNotFound
	}
	s.d.Rewards = slices.Delete(s.d.Rewards, i, i+1)
	return s.saveLocked()
}

// AskReward records a child's ask (one open ask per reward per person).
func (s *Store) AskReward(rewardID, memberID string) (RewardAsk, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.d.Rewards, func(r Reward) bool { return r.ID == rewardID })
	if i < 0 {
		return RewardAsk{}, ErrNotFound
	}
	for _, a := range s.d.RewardAsks {
		if a.RewardID == rewardID && a.MemberID == memberID && a.Status == "asked" {
			return a, nil
		}
	}
	r := s.d.Rewards[i]
	a := RewardAsk{ID: "q" + randomString(8), RewardID: r.ID, Title: r.Title, Cost: r.Cost, MemberID: memberID, Time: time.Now().UnixMilli(), Status: "asked"}
	s.d.RewardAsks = append(s.d.RewardAsks, a)
	// Keep the last 100 answered ones.
	if len(s.d.RewardAsks) > 150 {
		keep := []RewardAsk{}
		answered := 0
		for j := len(s.d.RewardAsks) - 1; j >= 0; j-- {
			x := s.d.RewardAsks[j]
			if x.Status == "asked" || answered < 100 {
				keep = append([]RewardAsk{x}, keep...)
				if x.Status != "asked" {
					answered++
				}
			}
		}
		s.d.RewardAsks = keep
	}
	return a, s.saveLocked()
}

var errNotEnoughStars = &httpError{http.StatusConflict, "not enough stars for that yet"}

type httpError struct {
	code int
	msg  string
}

func (e *httpError) Error() string { return e.msg }

// AnswerAsk approves (taking the stars) or declines an ask.
func (s *Store) AnswerAsk(id string, approve bool, by string) (RewardAsk, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.d.RewardAsks, func(a RewardAsk) bool { return a.ID == id })
	if i < 0 || s.d.RewardAsks[i].Status != "asked" {
		return RewardAsk{}, ErrNotFound
	}
	a := &s.d.RewardAsks[i]
	if approve {
		m, ok := s.d.Members[a.MemberID]
		if !ok {
			return RewardAsk{}, ErrNotFound
		}
		if m.Stars < a.Cost {
			return RewardAsk{}, errNotEnoughStars
		}
		m.Stars -= a.Cost
		a.Status = "approved"
	} else {
		a.Status = "declined"
	}
	a.By = by
	return *a, s.saveLocked()
}

// ---- HTTP

func validReward(w http.ResponseWriter, title string, cost int) bool {
	if strings.TrimSpace(title) == "" || utf8.RuneCountInString(title) > 60 {
		writeError(w, http.StatusBadRequest, "a reward needs a name (60 characters at most)")
		return false
	}
	if cost < 1 || cost > 1000 {
		writeError(w, http.StatusBadRequest, "a reward costs 1 to 1000 stars")
		return false
	}
	return true
}

// GET /api/rewards: the list, and asks (everyone's for parents, your own for children).
func (s *Server) handleRewards(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	rewards, asks := s.store.Rewards()
	for i := range rewards {
		if rewards[i].Who == nil {
			rewards[i].Who = []string{}
		}
	}
	if !s.canEditChores(me) {
		asks = slices.DeleteFunc(asks, func(a RewardAsk) bool { return a.MemberID != me.ID })
		// A child sees the rewards that are for them.
		rewards = slices.DeleteFunc(rewards, func(r Reward) bool { return len(r.Who) > 0 && !slices.Contains(r.Who, me.ID) })
	}
	slices.Reverse(asks) // newest first
	writeJSON(w, http.StatusOK, map[string]any{"rewards": rewards, "asks": asks, "canEdit": s.canEditChores(me)})
}

func (s *Server) handleAddReward(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	if !s.canEditChores(me) {
		writeError(w, http.StatusForbidden, "only parents set rewards")
		return
	}
	var body struct {
		Title string   `json:"title"`
		Cost  int      `json:"cost"`
		Who   []string `json:"who"`
	}
	if !readJSON(w, r, &body) || !validReward(w, body.Title, body.Cost) {
		return
	}
	rw, err := s.store.AddReward(strings.TrimSpace(body.Title), body.Cost, body.Who)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, rw)
}

func (s *Server) handleUpdateReward(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	if !s.canEditChores(me) {
		writeError(w, http.StatusForbidden, "only parents set rewards")
		return
	}
	var body struct {
		Title string   `json:"title"`
		Cost  int      `json:"cost"`
		Who   []string `json:"who"`
	}
	if !readJSON(w, r, &body) || !validReward(w, body.Title, body.Cost) {
		return
	}
	if err := s.store.UpdateReward(r.PathValue("id"), strings.TrimSpace(body.Title), body.Cost, body.Who); err != nil {
		writeError(w, http.StatusNotFound, "no such reward")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleDeleteReward(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	if !s.canEditChores(me) {
		writeError(w, http.StatusForbidden, "only parents set rewards")
		return
	}
	if err := s.store.DeleteReward(r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such reward")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/rewards/{id}/ask: a child (or anyone with stars) asks for a reward.
func (s *Server) handleAskReward(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.choreSetup(w, me) {
		return
	}
	a, err := s.store.AskReward(r.PathValue("id"), me.ID)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such reward")
		return
	}
	var to []string
	for _, m := range s.store.Members() {
		if m.role() == RoleParent && m.ID != me.ID {
			to = append(to, m.ID)
		}
	}
	s.store.ChoreEvents(to, Event{Type: EventReward, Kind: "asked", MemberID: me.ID, Name: me.Name, Title: a.Title, Level: a.Cost, Note: a.ID})
	writeJSON(w, http.StatusOK, a)
}

// POST /api/rewards/asks/{id}/approve or /decline: a parent answers.
func (s *Server) handleAnswerAsk(approve bool) deviceHandler {
	return func(w http.ResponseWriter, r *http.Request, me *Member) {
		if !s.choreSetup(w, me) {
			return
		}
		if !s.canEditChores(me) || !isAdult(me) {
			writeError(w, http.StatusForbidden, "a parent answers these")
			return
		}
		a, err := s.store.AnswerAsk(r.PathValue("id"), approve, me.ID)
		if he, ok := err.(*httpError); ok {
			writeError(w, he.code, he.msg)
			return
		}
		if err != nil {
			writeError(w, http.StatusNotFound, "no such ask (already answered?)")
			return
		}
		name := ""
		for _, m := range s.store.Members() {
			if m.ID == a.MemberID {
				name = m.Name
			}
		}
		s.store.ChoreEvents([]string{a.MemberID}, Event{Type: EventReward, Kind: a.Status, MemberID: me.ID, Name: me.Name, Title: a.Title, Level: a.Cost, Note: a.ID})
		if approve {
			s.store.AddTimeline(Event{Type: EventRewarded, MemberID: a.MemberID, Name: name, Title: a.Title, Level: a.Cost})
		}
		writeJSON(w, http.StatusOK, a)
	}
}

// ---- chore reminders

// whenClock turns a chore's "when" into a time of day ("" if it has none).
var clockRe = regexp.MustCompile(`^(\d{1,2}):(\d{2})`)

func whenClock(when string) (int, int, bool) {
	w := strings.ToLower(strings.TrimSpace(when))
	if m := clockRe.FindStringSubmatch(w); m != nil {
		h, _ := strconv.Atoi(m[1])
		mi, _ := strconv.Atoi(m[2])
		if h < 24 && mi < 60 {
			return h, mi, true
		}
	}
	switch w {
	case "morning", "before school":
		return 7, 30, true
	case "afternoon":
		return 13, 0, true
	case "after school":
		return 15, 30, true
	case "evening", "after dinner":
		return 18, 30, true
	case "bedtime", "before bed":
		return 19, 30, true
	}
	return 0, 0, false
}

type choreReminders struct {
	mu   sync.Mutex
	sent map[string]bool // chore id + date
	day  string
}

// KeepChoreReminders reminds whoever a chore is for, at its time of day, if it
// isn't done yet (once a day per chore).
func (s *Server) KeepChoreReminders(ctx context.Context) {
	cr := &choreReminders{sent: map[string]bool{}}
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			s.remindChores(cr, now)
		}
	}
}

func (s *Server) remindChores(cr *choreReminders, now time.Time) {
	if s.chores == nil || !s.store.Modules().Enabled["chores"] {
		return
	}
	day := now.Format("2006-01-02")
	cr.mu.Lock()
	defer cr.mu.Unlock()
	if cr.day != day {
		cr.day, cr.sent = day, map[string]bool{}
	}
	people := s.store.Members()
	list, err := s.chores.list(people, false)
	if err != nil {
		return
	}
	mins := now.Hour()*60 + now.Minute()
	for _, c := range list {
		h, m, ok := whenClock(c.When)
		if !ok || c.Finished || (c.Due != "" && c.Due > day) || cr.sent[c.ID] {
			continue
		}
		at := h*60 + m
		if mins < at || mins > at+30 { // within half an hour of its time (a restart doesn't miss it)
			continue
		}
		if slices.ContainsFunc(c.Done, func(d ChoreDone) bool { return d.Date == day }) {
			continue
		}
		cr.sent[c.ID] = true
		to := c.Who
		if len(to) == 0 {
			for _, p := range people {
				if p.role() != RoleDependent {
					to = append(to, p.ID)
				}
			}
		}
		s.store.ChoreEvents(to, Event{Type: EventChore, Kind: "due", Title: c.Title, Note: c.ID, Level: c.Stars})
	}
}
