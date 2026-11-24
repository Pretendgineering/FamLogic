package main

import (
	"errors"
	"net/http"
	"strings"
	"time"
)

// Roles. Each person in the family is one of:
//   - a parent: full app; can log in to the web console with their own account
//     and use its parent tools (and see their children's chats);
//   - a child: the app, without family settings. "Older" children may set a
//     password (PasswordAllowed); "younger" ones join with a one-time code from a
//     parent and stay signed in on their device;
//   - a non-user ("dependent"): a family member who doesn't use the app, such as a
//     baby, so events and reminders can still be about them.
//
// On top of that, Admin gives a person the web console's admin pages (modules,
// devices, invites, server). Only the main admin (the admin password) grants it.
// People from before roles existed are parents.
const (
	RoleParent    = "parent"
	RoleChild     = "child"
	RoleDependent = "dependent"
	// A grandparent: the whole app for themselves (chat, the map, nudges), but no
	// admin pages and no view of the children's chats. Larger buttons and picture
	// icons are an option.
	RoleGrandparent = "grandparent"
)

// The console's single choice per person.
const (
	AccessAdmin        = "admin"
	AccessParent       = "parent"
	AccessOlderChild   = "older_child"
	AccessYoungerChild = "younger_child"
	AccessDependent    = "dependent"
	AccessGrandparent  = "grandparent"
)

var ErrNeedsOwner = errors.New("only the main admin can change who is an admin")

func (m *Member) role() string {
	switch m.Role {
	case RoleChild, RoleDependent, RoleGrandparent:
		return m.Role
	}
	return RoleParent
}

// access is the console's label for this person's role.
func (m *Member) access() string {
	switch {
	case m.role() == RoleDependent:
		return AccessDependent
	case m.role() == RoleGrandparent:
		return AccessGrandparent
	case m.role() == RoleChild && m.PasswordAllowed:
		return AccessOlderChild
	case m.role() == RoleChild:
		return AccessYoungerChild
	case m.Admin:
		return AccessAdmin
	}
	return AccessParent
}

// canUsePassword: parents and older children sign in with a password; younger
// children and non-users don't.
func (m *Member) canUsePassword() bool {
	switch m.role() {
	case RoleParent, RoleGrandparent:
		return true
	case RoleChild:
		return m.PasswordAllowed
	}
	return false
}

// SetAccess changes someone's role. byOwner says whether the main admin is asking:
// only they can make someone an admin or take it away. Non-users stay non-users
// (invite them to the app instead), and app users can't become non-users.
func (s *Store) SetAccess(id, access string, byOwner bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	if (access == AccessDependent) != (m.role() == RoleDependent) {
		return ErrBadRequest
	}
	if (access == AccessAdmin) != m.Admin && !byOwner {
		return ErrNeedsOwner
	}
	switch access {
	case AccessAdmin:
		m.Role, m.Admin, m.PasswordAllowed = RoleParent, true, false
	case AccessParent:
		m.Role, m.Admin, m.PasswordAllowed = RoleParent, false, false
	case AccessOlderChild:
		m.Role, m.Admin, m.PasswordAllowed = RoleChild, false, true
	case AccessYoungerChild:
		m.Role, m.Admin, m.PasswordAllowed = RoleChild, false, false
	case AccessGrandparent:
		m.Role, m.Admin, m.PasswordAllowed = RoleGrandparent, false, false
	case AccessDependent:
		return nil
	default:
		return ErrBadRequest
	}
	return s.saveLocked()
}

// AddDependent adds a family member who doesn't use the app.
func (s *Store) AddDependent(name string) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := &Member{
		ID:        randomString(9),
		Name:      name,
		Color:     palette[len(s.d.Members)%len(palette)],
		CreatedAt: time.Now().UnixMilli(),
		Role:      RoleDependent,
	}
	if c := s.defaultCircleLocked(); c != "" {
		m.Circles = []string{c}
	}
	s.d.Members[m.ID] = m
	cp := *m
	return &cp, s.saveLocked()
}

// RedeemDeviceCode signs a device in for someone without a password (a younger
// child): the parent's one-time code is enough. Their other devices stay signed in.
func (s *Store) RedeemDeviceCode(code string) (*Member, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	code = normalizeCode(code)
	inv, ok := s.d.Invites[code]
	if !ok || inv.ExpiresAt < time.Now().UnixMilli() || inv.MemberID == "" {
		return nil, "", ErrNotFound
	}
	m, ok := s.d.Members[inv.MemberID]
	if !ok || m.canUsePassword() || m.role() == RoleDependent {
		return nil, "", ErrNotFound
	}
	token := s.rotateTokenLocked(m)
	delete(s.d.Invites, code)
	cp := *m
	return &cp, token, s.saveLocked()
}

// inviteAccess cleans up the role an invite is for ("" = parent).
func inviteAccess(a string) (string, bool) {
	switch a {
	case "", AccessParent:
		return AccessParent, true
	case AccessOlderChild, AccessYoungerChild, AccessGrandparent:
		return a, true
	}
	return "", false // admins are made from parents; non-users aren't invited
}

// applyInviteAccess sets a new member's role from their invite.
func applyInviteAccess(m *Member, inv *Invite) {
	switch inv.Access {
	case AccessOlderChild:
		m.Role, m.PasswordAllowed = RoleChild, true
	case AccessYoungerChild:
		m.Role = RoleChild
	case AccessGrandparent:
		m.Role = RoleGrandparent
	default:
		m.Role = RoleParent
	}
}

// ---- HTTP

func (s *Server) handleAdminSetRole(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Role string `json:"role"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	// Older consoles sent "parent" / "child".
	if body.Role == RoleChild {
		body.Role = AccessYoungerChild
	}
	switch err := s.store.SetAccess(r.PathValue("id"), body.Role, s.session(r).Owner); err {
	case nil:
		w.WriteHeader(http.StatusNoContent)
	case ErrNotFound:
		writeError(w, http.StatusNotFound, "no such member")
	case ErrNeedsOwner:
		writeError(w, http.StatusForbidden, "Only the main admin (with the admin password) can change who is an admin.")
	default:
		writeError(w, http.StatusBadRequest, "that role can't be set here")
	}
}

func (s *Server) handleAdminAddDependent(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name string `json:"name"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	name, ok := cleanName(strings.TrimSpace(body.Name))
	if !ok {
		writeError(w, http.StatusBadRequest, "name must be 1-40 characters")
		return
	}
	m, err := s.store.AddDependent(name)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}

// MemberByID returns a copy of a member.
func (s *Store) MemberByID(id string) (*Member, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, false
	}
	cp := *m
	return &cp, true
}
