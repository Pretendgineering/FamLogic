package main

// Safety: who's coming to get you ("Where's my ride?"), check-in windows for
// children ("arrive at School between 8:00 and 8:45 on school days"), and phone
// numbers for the Call buttons.
//
// Rides: answering a "Pick me up" (or someone's SOS) with "On my way" (request
// kind "coming") marks you as driving to them, for up to 90 minutes or until you
// get within 150 m of them. Their app shows how far you are.
//
// Check-in windows: a parent picks a child, a fence, the days and a window. At the
// window's start the child is nudged to check in when they get there; if by its
// end they haven't arrived at the fence or checked in, the parents are told.

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
)

const (
	rideMaxMinutes = 90
	rideArriveM    = 150

	EventRideHere      = "ride_here"      // to the person waiting: their ride is here
	EventCheckRemind   = "checkin_remind" // to a child: check in when you get to <place>
	EventCheckinMissed = "checkin_missed" // to parents: <child> hasn't arrived at <place>
)

// ---- rides

// startRideLocked: from is on the way to to.
func (s *Store) startRideLocked(from, to *Member) {
	from.RideTo, from.RideSince = to.ID, time.Now().UnixMilli()
}

// checkRideLocked ends a ride when the driver gets there (or it's gone on too long).
func (s *Store) checkRideLocked(m *Member, loc Location) {
	if m.RideTo == "" {
		return
	}
	to, ok := s.d.Members[m.RideTo]
	if !ok || time.Since(time.UnixMilli(m.RideSince)) > rideMaxMinutes*time.Minute {
		m.RideTo, m.RideSince = "", 0
		return
	}
	if to.Loc != nil && haversine(loc.Lat, loc.Lon, to.Loc.Lat, to.Loc.Lon) < rideArriveM {
		s.addEventLocked(Event{Type: EventRideHere, MemberID: m.ID, Name: m.Name, To: to.ID})
		m.RideTo, m.RideSince = "", 0
	}
}

func haversine(lat1, lon1, lat2, lon2 float64) float64 {
	const r = 6371000.0
	p1, p2 := lat1*math.Pi/180, lat2*math.Pi/180
	dp, dl := (lat2-lat1)*math.Pi/180, (lon2-lon1)*math.Pi/180
	a := math.Sin(dp/2)*math.Sin(dp/2) + math.Cos(p1)*math.Cos(p2)*math.Sin(dl/2)*math.Sin(dl/2)
	return 2 * r * math.Asin(math.Sqrt(a))
}

// activeRide is who m is driving to, if they still are.
func activeRide(m *Member) string {
	if m.RideTo == "" || time.Since(time.UnixMilli(m.RideSince)) > rideMaxMinutes*time.Minute {
		return ""
	}
	return m.RideTo
}

// ---- phone numbers

var phoneRe = regexp.MustCompile(`^\+?[0-9 ()\-.]{5,24}$`)

func validPhone(p string) bool { return p == "" || phoneRe.MatchString(p) }

// ---- check-in windows

type CheckPlan struct {
	ID       string `json:"id"`
	MemberID string `json:"memberId"` // the child
	PlaceID  string `json:"placeId"`
	Days     []int  `json:"days"`  // 0 Sunday … 6 Saturday
	From     string `json:"from"`  // "08:00"
	Until    string `json:"until"` // "08:45"
	By       string `json:"by"`    // the parent who set it
}

var clockOnly = regexp.MustCompile(`^([01]\d|2[0-3]):[0-5]\d$`)

func (s *Store) CheckPlans() []CheckPlan {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]CheckPlan{}, s.d.CheckPlans...)
}

func (s *Store) AddCheckPlan(p CheckPlan) (CheckPlan, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.d.Members[p.MemberID]; !ok {
		return p, ErrNotFound
	}
	if _, ok := s.d.Places[p.PlaceID]; !ok {
		return p, ErrNotFound
	}
	p.ID = "k" + randomString(8)
	s.d.CheckPlans = append(s.d.CheckPlans, p)
	return p, s.saveLocked()
}

func (s *Store) DeleteCheckPlan(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.d.CheckPlans, func(p CheckPlan) bool { return p.ID == id })
	if i < 0 {
		return ErrNotFound
	}
	s.d.CheckPlans = slices.Delete(s.d.CheckPlans, i, i+1)
	return s.saveLocked()
}

type checkWatch struct {
	mu       sync.Mutex
	reminded map[string]bool // plan id + date
	missed   map[string]bool
	day      string
}

func clockMinutes(c string) int {
	var h, m int
	fmt.Sscanf(c, "%d:%d", &h, &m)
	return h*60 + m
}

// KeepCheckPlans nudges children at a window's start and tells parents at its end.
func (s *Server) KeepCheckPlans(ctx context.Context) {
	cw := &checkWatch{reminded: map[string]bool{}, missed: map[string]bool{}}
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			s.store.checkPlansAt(cw, now)
		}
	}
}

func (s *Store) checkPlansAt(cw *checkWatch, now time.Time) {
	day := now.Format("2006-01-02")
	cw.mu.Lock()
	defer cw.mu.Unlock()
	if cw.day != day {
		cw.day, cw.reminded, cw.missed = day, map[string]bool{}, map[string]bool{}
	}
	mins := now.Hour()*60 + now.Minute()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, p := range s.d.CheckPlans {
		if !slices.Contains(p.Days, int(now.Weekday())) {
			continue
		}
		kid, ok := s.d.Members[p.MemberID]
		place, ok2 := s.d.Places[p.PlaceID]
		if !ok || !ok2 {
			continue
		}
		from, until := clockMinutes(p.From), clockMinutes(p.Until)
		key := p.ID + day
		if mins >= from && mins < until && !cw.reminded[key] {
			cw.reminded[key] = true
			if !s.checkedInLocked(kid, place, now, from) {
				s.addEventLocked(Event{Type: EventCheckRemind, To: kid.ID, MemberID: kid.ID, Name: kid.Name, Place: place.Name, PlaceID: place.ID})
			}
		}
		if mins >= until && mins < until+30 && !cw.missed[key] {
			cw.missed[key] = true
			if !s.checkedInLocked(kid, place, now, from) {
				for _, m := range s.d.Members {
					if m.role() == RoleParent && len(sharedCircles(m, kid)) > 0 {
						s.addEventLocked(Event{Type: EventCheckinMissed, To: m.ID, MemberID: kid.ID, Name: kid.Name, Place: place.Name, PlaceID: place.ID})
					}
				}
			}
		}
	}
	_ = s.saveLocked()
}

// checkedInLocked: since the window opened, the child arrived at the place, is
// there now, or checked in.
func (s *Store) checkedInLocked(kid *Member, place *Place, now time.Time, fromMin int) bool {
	start := time.Date(now.Year(), now.Month(), now.Day(), fromMin/60, fromMin%60, 0, 0, now.Location()).UnixMilli()
	if kid.Loc != nil && !kid.Paused && haversine(kid.Loc.Lat, kid.Loc.Lon, place.Lat, place.Lon) <= place.Radius {
		return true
	}
	for _, e := range s.d.Events {
		if e.MemberID == kid.ID && e.Time >= start && ((e.Type == EventArrived && e.PlaceID == place.ID) || e.Type == EventCheckIn) {
			return true
		}
	}
	return false
}

// ---- HTTP

// GET /api/checkplans: the windows (parents see all; a child, their own).
func (s *Server) handleCheckPlans(w http.ResponseWriter, r *http.Request, me *Member) {
	out := []CheckPlan{}
	for _, p := range s.store.CheckPlans() {
		if isAdult(me) || p.MemberID == me.ID {
			out = append(out, p)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"plans": out})
}

// POST /api/checkplans {memberId, placeId, days, from, until}: parents only.
func (s *Server) handleAddCheckPlan(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "a parent sets these")
		return
	}
	var p CheckPlan
	if !readJSON(w, r, &p) {
		return
	}
	if !clockOnly.MatchString(p.From) || !clockOnly.MatchString(p.Until) || clockMinutes(p.Until) <= clockMinutes(p.From) {
		writeError(w, http.StatusBadRequest, "the window is two times, HH:MM, the second after the first")
		return
	}
	days := []int{}
	for _, d := range p.Days {
		if d >= 0 && d <= 6 && !slices.Contains(days, d) {
			days = append(days, d)
		}
	}
	if len(days) == 0 {
		writeError(w, http.StatusBadRequest, "choose at least one day")
		return
	}
	p.Days, p.By = days, me.ID
	p, err := s.store.AddCheckPlan(p)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such person or fence")
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (s *Server) handleDeleteCheckPlan(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "a parent sets these")
		return
	}
	if err := s.store.DeleteCheckPlan(r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such check-in")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

var _ = strings.TrimSpace
