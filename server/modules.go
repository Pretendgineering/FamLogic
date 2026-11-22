package main

import (
	"net/http"
	"slices"
)

// FamLogic is modular: each family switches on what it wants at first setup (and
// can change it later in Settings → Modules). Apps learn what's on from the
// server's features ("module:<id>") and hide the rest.

// ModuleInfo describes one module. Available is false while it's still being built.
type ModuleInfo struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Available   bool   `json:"available"`
	// On by default for a new family (once available).
	Default bool `json:"default"`
}

// Modules, in the order the console shows them. Keep ids stable: they're stored.
var allModules = []ModuleInfo{
	{"thereabouts", "Thereabouts", "Location sharing: everyone on a map, SOS, check-ins, fences, trips, driving, plus Pebble watches and the Index 01 ring.", true, true},
	{"chat", "Chat", "A family chat, group chats and one-to-one messages, with photos. Parents can see children's chats.", true, true},
	{"calendar", "Calendar", "Shared family calendar, with CalDAV so phones and other apps can sync.", false, true},
	{"todos", "To-dos & lists", "Quick to-dos and shared lists (shopping, packing), stored as Markdown.", false, true},
	{"chores", "Chores", "A chore chart with who does what and when, ticked off on phones or the family display.", false, false},
	{"meals", "Meals & menus", "Weekly meal plans and recipes, with shopping lists.", false, false},
	{"display", "Family display", "A wall tablet or Android TV showing the day: calendar, chores, menus and who's where.", false, false},
	{"agents", "AI & API access", "API keys for AI agents (Hermes, Ollama-driven tools and others) to read and edit your family's Markdown notes.", false, false},
}

// ModuleSettings are the family's choices.
type ModuleSettings struct {
	Enabled   map[string]bool `json:"enabled"`
	SetupDone bool            `json:"setupDone"`
}

func moduleKnown(id string) bool {
	return slices.ContainsFunc(allModules, func(m ModuleInfo) bool { return m.ID == id })
}

// modulesLocked returns the family's module settings. A data file from before
// modules existed is an existing family: location on, setup done.
// A brand-new server (nobody joined yet) shows the first-run setup.
func (s *Store) modulesLocked() ModuleSettings {
	if s.d.Modules != nil {
		return *s.d.Modules
	}
	if len(s.d.Members) > 0 {
		return ModuleSettings{Enabled: map[string]bool{"thereabouts": true}, SetupDone: true}
	}
	on := map[string]bool{}
	for _, m := range allModules {
		if m.Available && m.Default {
			on[m.ID] = true
		}
	}
	return ModuleSettings{Enabled: on}
}

func (s *Store) Modules() ModuleSettings {
	s.mu.RLock()
	defer s.mu.RUnlock()
	m := s.modulesLocked()
	m.Enabled = cloneBoolMap(m.Enabled)
	return m
}

// ModuleOn reports whether a module is switched on (and available).
func (s *Store) ModuleOn(id string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.modulesLocked().Enabled[id]
}

// SetModules saves the family's choices. Unknown ids are ignored; modules that
// aren't built yet can be chosen in advance and switch on when they arrive.
func (s *Store) SetModules(enabled map[string]bool, setupDone bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	clean := map[string]bool{}
	for id, on := range enabled {
		if on && moduleKnown(id) {
			clean[id] = true
		}
	}
	s.d.Modules = &ModuleSettings{Enabled: clean, SetupDone: setupDone || s.modulesLocked().SetupDone}
	return s.saveLocked()
}

func cloneBoolMap(m map[string]bool) map[string]bool {
	out := make(map[string]bool, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

// moduleFeatures are the "module:<id>" features for modules that are on and available.
func (s *Store) moduleFeatures() []string {
	set := s.Modules()
	var out []string
	for _, m := range allModules {
		if m.Available && set.Enabled[m.ID] {
			out = append(out, "module:"+m.ID)
		}
	}
	return out
}

// ---- HTTP

type moduleView struct {
	ModuleInfo
	Enabled bool `json:"enabled"`
}

func (s *Server) handleAdminModules(w http.ResponseWriter, r *http.Request) {
	set := s.store.Modules()
	out := make([]moduleView, 0, len(allModules))
	for _, m := range allModules {
		out = append(out, moduleView{m, set.Enabled[m.ID]})
	}
	writeJSON(w, http.StatusOK, map[string]any{"modules": out, "setupDone": set.SetupDone})
}

func (s *Server) handleAdminSetModules(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Enabled   map[string]bool `json:"enabled"`
		SetupDone bool            `json:"setupDone"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if err := s.store.SetModules(body.Enabled, body.SetupDone); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	s.handleAdminModules(w, r)
}
