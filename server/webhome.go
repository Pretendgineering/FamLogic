package main

// The web console's home: a grid of tiles each person arranges for themselves, like
// the app's home screen. Stored on their member record (or, for the admin password
// login, on the family's data), as "id:WxH" in order.

import (
	"net/http"
	"regexp"
)

var webTile = regexp.MustCompile(`^[a-z][a-z0-9_.-]{0,40}:[1-6]x[1-4]$`)

const maxWebTiles = 40

func (s *Store) WebHome(sess Session) []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if sess.Member != nil {
		if m := s.d.Members[sess.Member.ID]; m != nil {
			return append([]string(nil), m.WebHome...)
		}
		return nil
	}
	return append([]string(nil), s.d.OwnerWebHome...)
}

func (s *Store) SetWebHome(sess Session, tiles []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if sess.Member != nil {
		m := s.d.Members[sess.Member.ID]
		if m == nil {
			return ErrNotFound
		}
		m.WebHome = tiles
		return s.saveLocked()
	}
	s.d.OwnerWebHome = tiles
	return s.saveLocked()
}

// GET /api/console/home: this person's tiles ([] = the default). PUT {tiles}: save them.
func (s *Server) handleWebHome(w http.ResponseWriter, r *http.Request) {
	sess := s.session(r)
	if !sess.Valid() {
		writeError(w, http.StatusUnauthorized, "please log in")
		return
	}
	if r.Method == http.MethodGet {
		t := s.store.WebHome(sess)
		if t == nil {
			t = []string{}
		}
		writeJSON(w, http.StatusOK, map[string]any{"tiles": t})
		return
	}
	var body struct {
		Tiles []string `json:"tiles"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if len(body.Tiles) > maxWebTiles {
		writeError(w, http.StatusBadRequest, "too many tiles")
		return
	}
	seen := map[string]bool{}
	for _, t := range body.Tiles {
		if !webTile.MatchString(t) {
			writeError(w, http.StatusBadRequest, "a tile is \"name:WxH\"")
			return
		}
		id := t[:len(t)-4]
		if seen[id] {
			writeError(w, http.StatusBadRequest, "each tile once")
			return
		}
		seen[id] = true
	}
	if err := s.store.SetWebHome(sess, body.Tiles); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tiles": body.Tiles})
}
