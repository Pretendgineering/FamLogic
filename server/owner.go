package main

// The main admin as a family member. At first setup, whoever logged in with the
// admin password adds themselves: a parent and admin with their own username and
// password, so they show up on the map and in chats like everyone else. From then
// on they sign in as themselves; the admin password still works, for recovery.

import (
	"errors"
	"net/http"
	"strings"
	"time"
)

func (s *Store) OwnerMember() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.d.OwnerMember
}

var errOwnerSet = errors.New("the main admin is already in the family")

// AddOwner makes the main admin's own member (once).
func (s *Store) AddOwner(name, username, hash string) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.d.OwnerMember != "" && s.d.Members[s.d.OwnerMember] != nil {
		return nil, errOwnerSet
	}
	if s.usernameTakenLocked(username, "") {
		return nil, ErrUsernameTaken
	}
	m := &Member{
		ID:           randomString(9),
		Name:         name,
		Color:        palette[len(s.d.Members)%len(palette)],
		CreatedAt:    time.Now().UnixMilli(),
		Role:         RoleParent,
		Admin:        true,
		Username:     username,
		PasswordHash: hash,
	}
	if c := s.defaultCircleLocked(); c != "" {
		m.Circles = []string{c}
	}
	s.d.Members[m.ID] = m
	s.d.OwnerMember = m.ID
	cp := *m
	return &cp, s.saveLocked()
}

// LinkOwner makes an existing parent the main admin's own member.
func (s *Store) LinkOwner(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := s.d.Members[id]
	if m == nil || m.role() != RoleParent {
		return ErrNotFound
	}
	m.Admin = true
	s.d.OwnerMember = id
	return s.saveLocked()
}

// POST /api/admin/owner {name, username, password} (a new member) or {id} (an existing
// parent): who the main admin is in the family. Only with the admin password.
func (s *Server) handleOwner(w http.ResponseWriter, r *http.Request) {
	sess := s.session(r)
	if !sess.Owner || sess.Member != nil {
		writeError(w, http.StatusForbidden, "log in with the admin password to do this")
		return
	}
	var body struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.ID != "" {
		if err := s.store.LinkOwner(body.ID); err != nil {
			writeError(w, http.StatusBadRequest, "choose a parent")
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	name := strings.TrimSpace(body.Name)
	if name == "" || len([]rune(name)) > 40 {
		writeError(w, http.StatusBadRequest, "your name, up to 40 letters")
		return
	}
	username, err := NormalizeUsername(body.Username)
	if err != nil {
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
		return
	}
	if err := CheckPassword(body.Password, username, name); err != nil {
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
		return
	}
	hash, err := HashPassword(body.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	m, err := s.store.AddOwner(name, username, hash)
	switch err {
	case nil:
	case ErrUsernameTaken:
		writeError(w, http.StatusConflict, "that username is taken")
		return
	case errOwnerSet:
		writeError(w, http.StatusConflict, "the main admin is already in the family")
		return
	default:
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	// From now on, signed in as themselves.
	s.setSession(w, r, "m-"+m.ID)
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}
