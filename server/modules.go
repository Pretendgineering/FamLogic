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
	Plain       string `json:"plain"` // its plain-wording name ("Chat"), for people who switch the names off
	Description string `json:"description"`
	Available   bool   `json:"available"`
	// On by default for a new family (once available).
	Default bool `json:"default"`
}

// Modules, in the order the console shows them. Keep ids stable: they're stored.
var allModules = []ModuleInfo{
	{"thereabouts", "Thereabouts", "Map", "Family map, SOS, check-ins, fences and trips.", true, true},
	{"chat", "Chitchat", "Chat", "Family, group and one-to-one chat.", true, true},
	{"calendar", "Datebook", "Calendar", "Shared calendar, synced with CalDAV.", true, true},
	{"todos", "Docket", "Lists", "What's on the docket: one-off jobs, errands and projects, and shared lists.", true, true},
	{"chores", "Upkeep", "Chores", "Chores, with optional stars.", true, true},
	{"routines", "Rhythms", "Routines", "Checklists that change with the day.", true, false},
	{"meals", "Pantry", "Meals", "Meal plans and recipes.", true, false},
	{"display", "Corkboard", "Family display", "The family's day on the TV or a wall tablet: what's on, routines, meals, quiet time and messages from a phone.", true, false},
	{"agents", "Steward", "AI & API", "Your family's AI helper, and API keys for your own agent.", true, false},
	// Coming soon: they can be switched on now (a placeholder, and Steward can keep
	// their notes in the vault already) and fill in as each is built.
	{"outings", "Outings", "Health & activity", "Walks, runs and rides, steps, meals and macros, and health data from phones and watches.", false, false},
	{"garage", "Garage", "Cars", "Fuel, mileage and maintenance for each car, with reminders.", false, false},
	{"ledger", "Ledger", "Budget", "Bills, spending, groceries and paying off debt.", false, false},
	{"pets", "Menagerie", "Pets", "Feeding, walks, medicines and vet visits.", false, false},
	{"profiles", "Who's Who", "Profiles", "Allergies, medicines, sleep, likes and emergency contacts; a page for sitters.", true, false},
	{"homestead", "Homestead", "Home care", "What your home has, and the upkeep it needs: bin days, filters, alarms, gutters, as chores and on the calendar.", true, false},
	{"contacts", "Rolodex", "Contacts", "The family's address book, synced with phones (CardDAV).", false, false},
}

func moduleCatalogByID(id string) (ModuleInfo, bool) {
	for _, m := range allModules {
		if m.ID == id {
			return m, true
		}
	}
	return ModuleInfo{}, false
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
		} else if set.Enabled[m.ID] {
			out = append(out, "soon:"+m.ID) // switched on, still being built: apps show a placeholder
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

// The app's own module switches (Settings → Look and Feel): everyone can see
// what's on; an admin can switch modules on or off for the whole family.
func (s *Server) handleDeviceModules(w http.ResponseWriter, r *http.Request, me *Member) {
	s.handleAdminModules(w, r)
}

func (s *Server) handleDeviceSetModules(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.access() != AccessAdmin {
		writeError(w, http.StatusForbidden, "only an admin can switch modules for the family")
		return
	}
	var body struct {
		Enabled map[string]bool `json:"enabled"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if err := s.store.SetModules(body.Enabled, true); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	s.handleAdminModules(w, r)
}
