package main

import (
	"errors"
	"slices"
	"sort"
	"strings"
	"time"
)

// Circles are groups within one server, such as "Family", "Grandparents" or
// "Friends". A person can be in several. You see someone only if you share a
// circle with them, and their location only in circles where they share it.

// Circle is one group of people.
type Circle struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	CreatedAt int64  `json:"createdAt"`
}

var ErrLastCircle = errors.New("a server needs at least one group")

// EnsureDefaultCircle creates the first circle on a server that has none (a new
// server, or one from before circles existed) and puts everyone in it, so
// upgrading changes nothing until a second circle is made.
func (s *Store) EnsureDefaultCircle(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.d.Circles) > 0 {
		return nil
	}
	c := &Circle{ID: randomString(6), Name: name, CreatedAt: time.Now().UnixMilli()}
	s.d.Circles[c.ID] = c
	for _, m := range s.d.Members {
		m.Circles = []string{c.ID}
	}
	return s.saveLocked()
}

// defaultCircleLocked is the oldest circle: where invites without a circle lead.
func (s *Store) defaultCircleLocked() string {
	var best *Circle
	for _, c := range s.d.Circles {
		if best == nil || c.CreatedAt < best.CreatedAt || (c.CreatedAt == best.CreatedAt && c.ID < best.ID) {
			best = c
		}
	}
	if best == nil {
		return ""
	}
	return best.ID
}

// Circles returns all circles, oldest first.
func (s *Store) Circles() []Circle {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.circlesLocked(nil)
}

// circlesLocked returns the circles with the given IDs (all if ids is nil), oldest first.
func (s *Store) circlesLocked(ids []string) []Circle {
	out := []Circle{}
	for _, c := range s.d.Circles {
		if ids == nil || slices.Contains(ids, c.ID) {
			out = append(out, *c)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt != out[j].CreatedAt {
			return out[i].CreatedAt < out[j].CreatedAt
		}
		return out[i].ID < out[j].ID
	})
	return out
}

func (s *Store) CreateCircle(name string) (*Circle, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// Strictly after every existing circle, so "oldest" (the default) never changes.
	created := time.Now().UnixMilli()
	for _, c := range s.d.Circles {
		if c.CreatedAt >= created {
			created = c.CreatedAt + 1
		}
	}
	c := &Circle{ID: randomString(6), Name: name, CreatedAt: created}
	s.d.Circles[c.ID] = c
	cp := *c
	return &cp, s.saveLocked()
}

func (s *Store) RenameCircle(id, name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.d.Circles[id]
	if !ok {
		return ErrNotFound
	}
	c.Name = name
	return s.saveLocked()
}

// DeleteCircle removes a circle: nobody is in it any more and its invites stop
// working. People stay on the server (they may be in other circles).
func (s *Store) DeleteCircle(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.d.Circles[id]; !ok {
		return ErrNotFound
	}
	if len(s.d.Circles) == 1 {
		return ErrLastCircle
	}
	delete(s.d.Circles, id)
	for _, m := range s.d.Members {
		m.Circles = slices.DeleteFunc(m.Circles, func(c string) bool { return c == id })
		m.HiddenFrom = slices.DeleteFunc(m.HiddenFrom, func(c string) bool { return c == id })
	}
	for code, inv := range s.d.Invites {
		if inv.CircleID == id {
			delete(s.d.Invites, code)
		}
	}
	s.deleteCircleDataLocked(id)
	return s.saveLocked()
}

// SetMembership puts a member in a circle or takes them out.
func (s *Store) SetMembership(memberID, circleID string, in bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return ErrNotFound
	}
	if _, ok := s.d.Circles[circleID]; !ok {
		return ErrNotFound
	}
	if in {
		if !slices.Contains(m.Circles, circleID) {
			m.Circles = append(m.Circles, circleID)
		}
	} else {
		m.Circles = slices.DeleteFunc(m.Circles, func(c string) bool { return c == circleID })
		m.HiddenFrom = slices.DeleteFunc(m.HiddenFrom, func(c string) bool { return c == circleID })
		for pid, p := range s.d.Places {
			if p.CircleID == circleID {
				delete(m.Presence, pid)
			}
		}
	}
	return s.saveLocked()
}

// SetSharingIn lets a member share or stop sharing their location with one circle.
func (s *Store) SetSharingIn(memberID, circleID string, sharing bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok || !slices.Contains(m.Circles, circleID) {
		return ErrNotFound
	}
	m.HiddenFrom = slices.DeleteFunc(m.HiddenFrom, func(c string) bool { return c == circleID })
	if !sharing {
		m.HiddenFrom = append(m.HiddenFrom, circleID)
	}
	return s.saveLocked()
}

// JoinCircleWithCode lets someone who's already signed in use an invite to join
// another circle, keeping who they are.
func (s *Store) JoinCircleWithCode(memberID, code string) (*Circle, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	code = normalizeCode(code)
	inv, ok := s.d.Invites[code]
	if !ok || inv.ExpiresAt < time.Now().UnixMilli() || inv.MemberID != "" {
		return nil, ErrNotFound
	}
	m, ok := s.d.Members[memberID]
	if !ok {
		return nil, ErrNotFound
	}
	circleID := s.inviteCircleLocked(inv)
	c, ok := s.d.Circles[circleID]
	if !ok {
		return nil, ErrNotFound
	}
	if !slices.Contains(m.Circles, circleID) {
		m.Circles = append(m.Circles, circleID)
	}
	delete(s.d.Invites, code)
	cp := *c
	return &cp, s.saveLocked()
}

// inviteCircleLocked is the circle an invite leads to (older invites: the default circle).
func (s *Store) inviteCircleLocked(inv *Invite) string {
	if _, ok := s.d.Circles[inv.CircleID]; ok {
		return inv.CircleID
	}
	return s.defaultCircleLocked()
}

// deleteCircleDataLocked removes things that belong to a deleted circle.
func (s *Store) deleteCircleDataLocked(id string) {
	for pid, p := range s.d.Places {
		if p.CircleID == id {
			delete(s.d.Places, pid)
		}
	}
}

// ---- who sees whom ----

// sharedCircles lists the circles both people are in.
func sharedCircles(a, b *Member) []string {
	var out []string
	for _, c := range a.Circles {
		if slices.Contains(b.Circles, c) {
			out = append(out, c)
		}
	}
	return out
}

// sharesLocationWith reports whether `m` shares their location with `viewer`:
// they have a circle in common where `m` hasn't turned sharing off.
func sharesLocationWith(m, viewer *Member) bool {
	if m.Paused {
		return false
	}
	for _, c := range sharedCircles(m, viewer) {
		if !slices.Contains(m.HiddenFrom, c) {
			return true
		}
	}
	return false
}

// MemberView is someone as one particular person sees them.
type MemberView struct {
	Member
	SharedCircles []string // circles they have in common with the viewer
	Place         string   // the place they're at, if any, among places the viewer can see
}

// VisibleTo returns the people `viewerID` can see (themselves included), with
// locations hidden where they aren't shared with the viewer. Sorted by name.
func (s *Store) VisibleTo(viewerID string) ([]MemberView, []Circle) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	viewer, ok := s.d.Members[viewerID]
	if !ok {
		return nil, nil
	}
	var out []MemberView
	for _, m := range s.d.Members {
		shared := sharedCircles(m, viewer)
		if m.ID != viewerID && len(shared) == 0 {
			continue
		}
		cp := *m
		if m.ID == viewerID {
			shared = slices.Clone(m.Circles)
		} else if !sharesLocationWith(m, viewer) {
			// To this viewer they look paused: no location, no battery.
			cp.Loc = nil
			cp.Paused = true
		}
		if cp.Loc != nil {
			l := *cp.Loc
			cp.Loc = &l
		}
		place := ""
		if cp.Loc != nil {
			place = s.placeNameForLocked(m, viewer)
		}
		out = append(out, MemberView{cp, shared, place})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out, s.circlesLocked(viewer.Circles)
}

// eventVisibleLocked decides whether `viewer` should get event `e`. SOS, "I'm OK"
// and check-ins go to everyone sharing a circle with the sender; low-battery
// alerts only to people the sender shares their location with.
func (s *Store) eventVisibleLocked(e Event, viewer *Member) bool {
	if e.MemberID == viewer.ID {
		return false
	}
	sender, ok := s.d.Members[e.MemberID]
	if !ok {
		return false // they've been removed since
	}
	if e.CircleID != "" {
		// Place alerts belong to one circle, and reveal where someone is: only
		// for that circle, and only if they share their location with it.
		return slices.Contains(viewer.Circles, e.CircleID) && slices.Contains(sender.Circles, e.CircleID) &&
			!sender.Paused && !slices.Contains(sender.HiddenFrom, e.CircleID)
	}
	if e.Type == EventBatteryLow || e.Type == EventSilent || e.Type == EventTrip {
		return sharesLocationWith(sender, viewer)
	}
	return len(sharedCircles(sender, viewer)) > 0
}
