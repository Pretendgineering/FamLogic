package main

// Steward: API keys for AI agents (a Hermes agent, a local Ollama tool, a script).
// An admin makes a key in the console with a name and the scopes it may use; the
// key is shown once and stored hashed; every use is logged and it can be revoked
// at any time. An agent sends it as "Authorization: Bearer fl_...". It acts as a
// grown-up of the family called by the key's name ("Steward asked Sam to unload
// the dishwasher"), limited to its scopes. The rules agents should follow are in
// the vault guide (AGENTS.md), and the API is described at /api/v1/openapi.json.

import (
	"crypto/rand"
	"crypto/sha256"
	_ "embed"
	"encoding/base32"
	"encoding/hex"
	"net/http"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

//go:embed openapi.json
var openAPISpec []byte

// APIKey is one agent's key.
type APIKey struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	Hash     string   `json:"hash"`   // sha256 of the key
	Prefix   string   `json:"prefix"` // the first characters, to tell keys apart
	Scopes   []string `json:"scopes"`
	Created  int64    `json:"created"`
	LastUsed int64    `json:"lastUsed,omitempty"`
	Uses     int      `json:"uses,omitempty"`
	Expires  int64    `json:"expires,omitempty"` // unix ms; 0 = never
}

// KeyUse is one request made with a key (the latest few hundred are kept).
type KeyUse struct {
	Time   int64  `json:"time"`
	Key    string `json:"key"` // key id
	Method string `json:"method"`
	Path   string `json:"path"`
}

// What a key can be allowed to do.
var keyScopes = []struct{ ID, Text string }{
	{"people", "See who's in the family (names, roles, stars), not where they are"},
	{"chores", "See, add, change and tick off chores (Upkeep), and give or spend stars"},
	{"calendar", "See and change the family calendar (Datebook)"},
	{"lists", "See, add to and tick off lists and to-dos (Docket)"},
	{"feed", "Read the family feed (who arrived where, who did which chore)"},
	{"location", "See where everyone is (the map)"},
}

const keyPrefix = "fl_"

// scopeFor is the scope a request needs, or "" if keys can't use it at all.
func scopeFor(method, path string) string {
	switch {
	case path == "/api/v1/people":
		return "people"
	case strings.HasPrefix(path, "/api/chores"), strings.HasPrefix(path, "/api/rewards"), strings.HasPrefix(path, "/api/members/") && strings.HasSuffix(path, "/stars"):
		return "chores"
	case strings.HasPrefix(path, "/api/events"):
		return "calendar"
	case strings.HasPrefix(path, "/api/lists"), strings.HasPrefix(path, "/api/routines"), strings.HasPrefix(path, "/api/meals"), strings.HasPrefix(path, "/api/recipes"):
		return "lists"
	case path == "/api/feed":
		return "feed"
	case path == "/api/members" && method == http.MethodGet:
		return "location"
	}
	return ""
}

func hashKey(k string) string {
	sum := sha256.Sum256([]byte(k))
	return hex.EncodeToString(sum[:])
}

// CreateKey makes a key; the plain key is returned only this once.
func (s *Store) CreateKey(name string, scopes []string, days int) (APIKey, string, error) {
	b := make([]byte, 20)
	rand.Read(b)
	plain := keyPrefix + strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
	k := APIKey{ID: randomString(8), Name: name, Hash: hashKey(plain), Prefix: plain[:7], Scopes: scopes, Created: time.Now().UnixMilli()}
	if days > 0 {
		k.Expires = time.Now().AddDate(0, 0, days).UnixMilli()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.d.APIKeys = append(s.d.APIKeys, &k)
	return k, plain, s.saveLocked()
}

func (s *Store) Keys() []APIKey {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := []APIKey{}
	for _, k := range s.d.APIKeys {
		c := *k
		c.Hash = ""
		out = append(out, c)
	}
	return out
}

func (s *Store) RevokeKey(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.d.APIKeys, func(k *APIKey) bool { return k.ID == id })
	if i < 0 {
		return false
	}
	s.d.APIKeys = slices.Delete(s.d.APIKeys, i, i+1)
	_ = s.saveLocked()
	return true
}

// useKey finds a key and logs the use (nil if it's unknown).
func (s *Store) useKey(plain string, r *http.Request) *APIKey {
	h := hashKey(plain)
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, k := range s.d.APIKeys {
		if k.Hash == h {
			if k.Expires > 0 && time.Now().UnixMilli() > k.Expires {
				return nil // expired: as good as revoked
			}
			k.LastUsed = time.Now().UnixMilli()
			k.Uses++
			s.d.KeyLog = append(s.d.KeyLog, KeyUse{Time: k.LastUsed, Key: k.ID, Method: r.Method, Path: r.URL.Path})
			if len(s.d.KeyLog) > 300 {
				s.d.KeyLog = s.d.KeyLog[len(s.d.KeyLog)-300:]
			}
			_ = s.saveLocked()
			c := *k
			return &c
		}
	}
	return nil
}

func (s *Store) KeyLog() []KeyUse {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]KeyUse{}, s.d.KeyLog...)
}

// agentMember is who an agent's requests act as: a grown-up named after the key.
func agentMember(k *APIKey) *Member {
	return &Member{ID: "agent:" + k.ID, Name: k.Name, Role: RoleParent, Color: "#6f5aa8", Emoji: "🤖"}
}

// agentRequest handles a request made with an API key. Returns false if the
// token isn't a key (so it's checked as a device token).
func (s *Server) agentRequest(w http.ResponseWriter, r *http.Request, token string, h deviceHandler) bool {
	if !strings.HasPrefix(token, keyPrefix) {
		return false
	}
	if !s.store.Modules().Enabled["agents"] {
		writeError(w, http.StatusForbidden, "AI & API access (Steward) is switched off for this family")
		return true
	}
	k := s.store.useKey(token, r)
	if k == nil {
		writeError(w, http.StatusUnauthorized, "unknown, expired or revoked API key")
		return true
	}
	scope := scopeFor(r.Method, r.URL.Path)
	if scope == "" || !slices.Contains(k.Scopes, scope) {
		writeError(w, http.StatusForbidden, "this key may not do that (it needs the \""+scope+"\" scope)")
		return true
	}
	h(w, r, agentMember(k))
	return true
}

// ---- HTTP

// GET /api/v1/people: everyone, without locations.
func (s *Server) handlePeople(w http.ResponseWriter, r *http.Request, me *Member) {
	type person struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Role   string `json:"role"`
		Access string `json:"access"`
		Stars  int    `json:"stars"`
		Voice  string `json:"voice,omitempty"`
	}
	out := []person{}
	for _, m := range s.store.Members() {
		out = append(out, person{m.ID, m.Name, m.role(), m.access(), m.Stars, m.Voice})
	}
	writeJSON(w, http.StatusOK, map[string]any{"people": out})
}

func (s *Server) handleOpenAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Write(openAPISpec)
}

func (s *Server) handleAdminKeys(w http.ResponseWriter, r *http.Request) {
	scopes := []map[string]string{}
	for _, sc := range keyScopes {
		scopes = append(scopes, map[string]string{"id": sc.ID, "text": sc.Text})
	}
	writeJSON(w, http.StatusOK, map[string]any{"keys": s.store.Keys(), "scopes": scopes, "log": s.store.KeyLog()})
}

func (s *Server) handleAdminCreateKey(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name   string   `json:"name"`
		Scopes []string `json:"scopes"`
		Days   int      `json:"days"` // 0 = never expires
	}
	if !readJSON(w, r, &body) {
		return
	}
	name := strings.TrimSpace(body.Name)
	if name == "" || utf8.RuneCountInString(name) > 40 {
		writeError(w, http.StatusBadRequest, "give the key a name (who'll use it), up to 40 characters")
		return
	}
	for _, sc := range body.Scopes {
		if !slices.ContainsFunc(keyScopes, func(k struct{ ID, Text string }) bool { return k.ID == sc }) {
			writeError(w, http.StatusBadRequest, "unknown scope "+sc)
			return
		}
	}
	if len(body.Scopes) == 0 {
		writeError(w, http.StatusBadRequest, "choose at least one thing the key may do")
		return
	}
	if body.Days < 0 || body.Days > 3650 {
		writeError(w, http.StatusBadRequest, "expiry: 0 (never) to 3650 days")
		return
	}
	k, plain, err := s.store.CreateKey(name, body.Scopes, body.Days)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"key": plain, "info": k})
}

func (s *Server) handleAdminRevokeKey(w http.ResponseWriter, r *http.Request) {
	if !s.store.RevokeKey(r.PathValue("id")) {
		writeError(w, http.StatusNotFound, "no such key")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
