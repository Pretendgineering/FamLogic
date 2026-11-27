package main

// Dossier: each person's profile (birthday, allergies, medicines, sleep, likes,
// sizes, doctor, emergency contacts...), kept in their People note in the vault
// as plain sections, so it reads well in Obsidian and prints as a page for a
// babysitter. Everyone in the family can see profiles (it's emergency
// information); parents can change anyone's, older children and grandparents
// their own, younger children none.

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unicode/utf8"
)

// profileSections, in the order they're written and shown.
var profileSections = []string{"Birthday", "Allergies", "Medicines", "Sleep", "Likes", "Dislikes", "Sizes", "Doctor", "Emergency contacts", "Notes"}

// peopleNotes guards People notes between the vault's sync and profile edits.
var peopleNotes sync.Mutex

func isProfileSection(h string) bool {
	for _, s := range profileSections {
		if strings.EqualFold(s, h) {
			return true
		}
	}
	return false
}

// readProfile finds the "## Section" blocks in a note's body.
func readProfile(body string) map[string]string {
	out := map[string]string{}
	cur := ""
	var b strings.Builder
	flush := func() {
		if cur != "" {
			out[cur] = strings.TrimSpace(b.String())
		}
		b.Reset()
	}
	for _, line := range strings.Split(body, "\n") {
		if h, ok := strings.CutPrefix(line, "## "); ok {
			flush()
			cur = ""
			for _, s := range profileSections {
				if strings.EqualFold(s, strings.TrimSpace(h)) {
					cur = s
				}
			}
			continue
		}
		if cur != "" {
			b.WriteString(line + "\n")
		}
	}
	flush()
	return out
}

// writeProfile replaces the profile sections in a note's body, keeping everything
// else (the title, other headings and text) as it was.
func writeProfile(body string, p map[string]string) string {
	var keep []string // lines outside profile sections
	in := false
	for _, line := range strings.Split(body, "\n") {
		if h, ok := strings.CutPrefix(line, "## "); ok {
			in = isProfileSection(strings.TrimSpace(h))
			if in {
				continue
			}
		}
		if !in {
			keep = append(keep, line)
		}
	}
	out := strings.TrimRight(strings.Join(keep, "\n"), "\n") + "\n"
	for _, s := range profileSections {
		if v := strings.TrimSpace(p[s]); v != "" {
			out += "\n## " + s + "\n" + v + "\n"
		}
	}
	return out
}

// personNote finds someone's People note.
func (s *Server) personNote(id string) (string, bool) {
	if s.vault == nil {
		return "", false
	}
	dir := filepath.Join(s.vault.dir, "People")
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", false
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err == nil && frontValue(string(b), "famlogic_id") == id {
			return filepath.Join(dir, e.Name()), true
		}
	}
	return "", false
}

func (s *Server) canEditProfile(me, of *Member) bool {
	switch {
	case me.ID == "admin" || me.role() == RoleParent:
		return true
	case me.access() == AccessYoungerChild || me.role() == RoleDependent:
		return false
	default: // older children and grandparents: their own
		return me.ID == of.ID
	}
}

func (s *Server) profilesSetup(w http.ResponseWriter, me *Member) bool {
	if s.vault == nil {
		writeError(w, http.StatusServiceUnavailable, "profiles need the notes vault, which isn't available on this server")
		return false
	}
	if !s.store.Modules().Enabled["profiles"] || me.level("profiles") == LevelOff {
		writeError(w, http.StatusForbidden, "Dossier isn't switched on for you")
		return false
	}
	return true
}

// GET /api/profiles: everyone's profile, with who may change which, and the
// parents' names and numbers (for the printed sheet).
func (s *Server) handleProfiles(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.profilesSetup(w, me) {
		return
	}
	s.vault.Sync(s.store.Members()) // a person who just joined has a note
	members := s.store.Members()
	people := []map[string]any{}
	parents := []map[string]string{}
	for i := range members {
		m := &members[i]
		if m.access() == "display" {
			continue
		}
		prof := map[string]string{}
		if path, ok := s.personNote(m.ID); ok {
			peopleNotes.Lock()
			if n, err := readNote(path); err == nil {
				prof = readProfile(n.body)
			}
			peopleNotes.Unlock()
		}
		people = append(people, map[string]any{"id": m.ID, "name": m.Name, "color": m.Color, "emoji": m.Emoji, "access": m.access(),
			"profile": prof, "canEdit": s.canEditProfile(me, m)})
		if m.role() == RoleParent {
			parents = append(parents, map[string]string{"name": m.Name, "phone": m.Phone})
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"people": people, "sections": profileSections, "parents": parents})
}

// PUT /api/profiles/{id} {profile: {Section: text}}: change someone's profile (only
// the sections sent; an empty one is removed).
func (s *Server) handleSetProfile(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.profilesSetup(w, me) {
		return
	}
	id := r.PathValue("id")
	var of *Member
	for _, m := range s.store.Members() {
		if m.ID == id {
			m := m
			of = &m
		}
	}
	if of == nil {
		writeError(w, http.StatusNotFound, "no such person")
		return
	}
	if !s.canEditProfile(me, of) {
		writeError(w, http.StatusForbidden, "only a parent can change this profile")
		return
	}
	var body struct {
		Profile map[string]string `json:"profile"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	for k, v := range body.Profile {
		if !isProfileSection(k) || utf8.RuneCountInString(v) > 4000 {
			writeError(w, http.StatusBadRequest, "unknown section, or too long: "+k)
			return
		}
	}
	s.vault.Sync(s.store.Members())
	path, ok := s.personNote(id)
	if !ok {
		writeError(w, http.StatusInternalServerError, "couldn't find their note")
		return
	}
	peopleNotes.Lock()
	defer peopleNotes.Unlock()
	n, err := readNote(path)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't read their note")
		return
	}
	prof := readProfile(n.body)
	for k, v := range body.Profile {
		for _, sec := range profileSections {
			if strings.EqualFold(sec, k) {
				prof[sec] = strings.ReplaceAll(v, "\r", "")
			}
		}
	}
	text := "---\n" + strings.Join(n.front, "\n") + "\n---\n" + writeProfile(n.body, prof)
	if err := writeAtomic(path, []byte(text)); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "profile": prof})
}
