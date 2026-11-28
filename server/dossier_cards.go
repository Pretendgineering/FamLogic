package main

// Dossier's pets and contacts: a note each in the vault's Pets and Contacts folders,
// with plain "## Section" blocks like the People notes. (They were the Menagerie and
// Rolodex modules; now they live in Dossier, beside the people.)

import (
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

var cardSections = map[string][]string{
	"pet":     {"Kind", "Birthday", "Food", "Walks", "Medicines", "Vet", "Microchip", "Notes"},
	"contact": {"Relationship", "Phone", "Email", "Address", "Birthday", "Notes"},
}

var cardFolder = map[string]string{"pet": "Pets", "contact": "Contacts"}

var cardID = regexp.MustCompile(`^[a-z0-9]{6,16}$`)

type dossierCard struct {
	ID      string            `json:"id"`
	Kind    string            `json:"kind"`
	Name    string            `json:"name"`
	Emoji   string            `json:"emoji,omitempty"`
	Profile map[string]string `json:"profile"`
	path    string
}

func sectionIn(list []string, h string) string {
	for _, s := range list {
		if strings.EqualFold(s, strings.TrimSpace(h)) {
			return s
		}
	}
	return ""
}

// readSections and writeSections: readProfile and writeProfile for any section list.
func readSections(body string, list []string) map[string]string {
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
			cur = sectionIn(list, h)
			continue
		}
		if cur != "" {
			b.WriteString(line + "\n")
		}
	}
	flush()
	return out
}

func writeSections(body string, p map[string]string, list []string) string {
	var keep []string
	in := false
	for _, line := range strings.Split(body, "\n") {
		if h, ok := strings.CutPrefix(line, "## "); ok {
			in = sectionIn(list, h) != ""
			if in {
				continue
			}
		}
		if !in {
			keep = append(keep, line)
		}
	}
	out := strings.TrimRight(strings.Join(keep, "\n"), "\n") + "\n"
	for _, s := range list {
		if v := strings.TrimSpace(p[s]); v != "" {
			out += "\n## " + s + "\n" + v + "\n"
		}
	}
	return out
}

// dossierCards reads every pet or contact note (a note without our id gets one).
func (s *Server) dossierCards(kind string) []dossierCard {
	dir := filepath.Join(s.vault.dir, cardFolder[kind])
	entries, _ := os.ReadDir(dir)
	out := []dossierCard{}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") || strings.HasPrefix(e.Name(), ".") {
			continue
		}
		path := filepath.Join(dir, e.Name())
		n, err := readNote(path)
		if err != nil {
			continue
		}
		id := frontValue("---\n"+strings.Join(n.front, "\n")+"\n---\n", "famlogic_id")
		if !cardID.MatchString(id) {
			// Written by hand (or by Steward): give it an id so it can be edited here.
			id = plainID()
			n.front = append(n.front, "famlogic_id: "+id)
			writeAtomic(path, []byte("---\n"+strings.Join(n.front, "\n")+"\n---\n"+n.body))
		}
		name := strings.TrimSuffix(e.Name(), ".md")
		front := "---\n" + strings.Join(n.front, "\n") + "\n---\n"
		out = append(out, dossierCard{ID: id, Kind: kind, Name: name, Emoji: frontValue(front, "emoji"),
			Profile: readSections(n.body, cardSections[kind]), path: path})
	}
	return out
}

func (s *Server) findCard(id string) (dossierCard, bool) {
	for kind := range cardFolder {
		for _, c := range s.dossierCards(kind) {
			if c.ID == id {
				return c, true
			}
		}
	}
	return dossierCard{}, false
}

// Anyone but younger children (and the TV) looks after the pets and the address book.
func canEditCards(me *Member) bool {
	return !me.Display && me.access() != AccessYoungerChild && me.role() != RoleDependent && me.level("profiles") != LevelView
}

// cleanCardName makes a name safe as a file name.
func cleanCardName(name string) string {
	name = strings.TrimSpace(strings.NewReplacer("/", "-", "\\", "-", ":", "-", "\n", " ", "\r", "").Replace(name))
	name = strings.TrimLeft(name, ".")
	if utf8.RuneCountInString(name) > 60 {
		name = string([]rune(name)[:60])
	}
	return name
}

// POST /api/profiles/cards {kind: pet|contact, name, emoji?, profile?}
func (s *Server) handleAddCard(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.profilesSetup(w, me) {
		return
	}
	if !canEditCards(me) {
		writeError(w, http.StatusForbidden, "ask a parent to add this")
		return
	}
	var body struct {
		Kind    string            `json:"kind"`
		Name    string            `json:"name"`
		Emoji   string            `json:"emoji"`
		Profile map[string]string `json:"profile"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	folder, ok := cardFolder[body.Kind]
	name := cleanCardName(body.Name)
	if !ok || name == "" {
		writeError(w, http.StatusBadRequest, "a pet or a contact, with a name")
		return
	}
	path := filepath.Join(s.vault.dir, folder, name+".md")
	if _, err := os.Stat(path); err == nil {
		writeError(w, http.StatusConflict, "there's already one called "+name)
		return
	}
	id := plainID()
	front := []string{"famlogic_id: " + id, "type: " + body.Kind}
	if e := strings.TrimSpace(body.Emoji); e != "" && utf8.RuneCountInString(e) <= 8 {
		front = append(front, "emoji: "+e)
	}
	text := "---\n" + strings.Join(front, "\n") + "\n---\n" + writeSections("# "+name+"\n", body.Profile, cardSections[body.Kind])
	if err := writeAtomic(path, []byte(text)); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	c, _ := s.findCard(id)
	writeJSON(w, http.StatusOK, c)
}

// PUT /api/profiles/cards/{id} {name?, emoji?, profile}: only the sections sent change.
// DELETE /api/profiles/cards/{id}: remove it.
func (s *Server) handleCard(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.profilesSetup(w, me) {
		return
	}
	if !canEditCards(me) {
		writeError(w, http.StatusForbidden, "ask a parent to change this")
		return
	}
	c, ok := s.findCard(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	peopleNotes.Lock()
	defer peopleNotes.Unlock()
	if r.Method == http.MethodDelete {
		journalBefore(c.path)
		if err := os.Remove(c.path); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't remove it")
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	var body struct {
		Name    *string           `json:"name"`
		Emoji   *string           `json:"emoji"`
		Profile map[string]string `json:"profile"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	list := cardSections[c.Kind]
	for k, v := range body.Profile {
		if sectionIn(list, k) == "" || utf8.RuneCountInString(v) > 4000 {
			writeError(w, http.StatusBadRequest, "unknown section, or too long: "+k)
			return
		}
	}
	n, err := readNote(c.path)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't read it")
		return
	}
	prof := readSections(n.body, list)
	for k, v := range body.Profile {
		prof[sectionIn(list, k)] = strings.ReplaceAll(v, "\r", "")
	}
	if body.Emoji != nil {
		var front []string
		for _, l := range n.front {
			if !strings.HasPrefix(l, "emoji:") {
				front = append(front, l)
			}
		}
		if e := strings.TrimSpace(*body.Emoji); e != "" && utf8.RuneCountInString(e) <= 8 {
			front = append(front, "emoji: "+e)
		}
		n.front = front
	}
	path := c.path
	if body.Name != nil {
		name := cleanCardName(*body.Name)
		if name == "" {
			writeError(w, http.StatusBadRequest, "it needs a name")
			return
		}
		if np := filepath.Join(filepath.Dir(c.path), name+".md"); np != c.path {
			if _, err := os.Stat(np); err == nil {
				writeError(w, http.StatusConflict, "there's already one called "+name)
				return
			}
			path = np
		}
	}
	text := "---\n" + strings.Join(n.front, "\n") + "\n---\n" + writeSections(n.body, prof, list)
	if err := writeAtomic(path, []byte(text)); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	if path != c.path {
		journalBefore(c.path)
		os.Remove(c.path)
	}
	out, _ := s.findCard(c.ID)
	writeJSON(w, http.StatusOK, out)
}
