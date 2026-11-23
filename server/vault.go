package main

// The vault: the family's content as an Obsidian-compatible folder of Markdown
// notes (see docs/ARCHITECTURE.md → Vault and vault_guide.md, which is the format
// guide for people and agents). It lives in DATA_DIR/vault unless VAULT_DIR says
// otherwise, so it can be a folder on the host that Obsidian, Syncthing or an AI
// agent also uses.
//
// For now FamLogic keeps the folders, the home note (FamLogic.md), the guide
// (AGENTS.md) and one note per person (People/<name>.md) up to date.

import (
	"context"
	_ "embed"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

//go:embed vault_guide.md
var vaultGuide string

// vaultFolders are made when the vault is opened, so people can see where things go.
var vaultFolders = []string{"People", "Lists", "To-dos", "Chores", "Calendar", "Meals", "Meals/Recipes", "Routines", "Rhythms"}

type Vault struct {
	dir string

	mu     sync.Mutex
	err    error     // the last problem writing to the vault, nil if none
	synced time.Time // when People notes were last brought up to date
}

func openVault(dir string) *Vault {
	v := &Vault{dir: dir}
	if err := v.ensure(); err != nil {
		v.fail(err)
	}
	return v
}

// fail records a problem (and logs it once, not every minute).
func (v *Vault) fail(err error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.err == nil || v.err.Error() != err.Error() {
		msg := err.Error()
		if errors.Is(err, os.ErrPermission) {
			msg += fmt.Sprintf(". FamLogic runs as user %d:%d; set PUID and PGID to the owner of the folder "+
				"(run `id` on the host), or make that user able to write to it", os.Getuid(), os.Getgid())
		}
		log.Printf("vault: %s", msg)
	}
	v.err = err
}

func (v *Vault) ok() {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.err = nil
	v.synced = time.Now()
}

// ensure makes the folders, the home note (once: it's the family's to edit) and
// the guide (always the current version).
func (v *Vault) ensure() error {
	for _, f := range append([]string{""}, vaultFolders...) {
		if err := os.MkdirAll(filepath.Join(v.dir, f), 0o755); err != nil {
			return err
		}
	}
	home := filepath.Join(v.dir, "FamLogic.md")
	if _, err := os.Stat(home); errors.Is(err, os.ErrNotExist) {
		if err := writeAtomic(home, []byte(homeNote)); err != nil {
			return err
		}
	}
	guide := "<!-- Written by FamLogic " + version + ". FamLogic replaces this file when it updates: don't edit it. -->\n\n" + vaultGuide
	if old, err := os.ReadFile(filepath.Join(v.dir, "AGENTS.md")); err != nil || string(old) != guide {
		if err := writeAtomic(filepath.Join(v.dir, "AGENTS.md"), []byte(guide)); err != nil {
			return err
		}
	}
	return nil
}

const homeNote = `---
type: home
---
# FamLogic

Our family's notes, kept by FamLogic and readable in Obsidian or any Markdown editor.
How the notes are written: [[AGENTS]].

- **People**: one note each, kept up to date by FamLogic
- **Lists**, **To-dos**, **Chores**, **Calendar**, **Meals**, **Routines** and **Rhythms**: a folder each

This note is yours: add links to the notes your family uses most.
`

// Keep brings the vault up to date now and then (people join, rename themselves,
// change their colour).
func (v *Vault) Keep(ctx context.Context, store *Store) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		v.Sync(store.Members())
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Sync writes the folders, guide and People notes, changing files only when
// something in them is different.
func (v *Vault) Sync(members []Member) {
	if err := v.ensure(); err != nil {
		v.fail(err)
		return
	}
	if err := v.syncPeople(members); err != nil {
		v.fail(err)
		return
	}
	v.ok()
}

func (v *Vault) syncPeople(members []Member) error {
	dir := filepath.Join(v.dir, "People")
	// Which note is whose: by famlogic_id, so a renamed person keeps their note.
	byID := map[string]string{}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	taken := map[string]bool{}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		taken[strings.ToLower(e.Name())] = true
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		if id := frontValue(string(b), "famlogic_id"); id != "" {
			byID[id] = e.Name()
		}
	}
	for _, m := range members {
		want := noteName(m.Name)
		file, exists := byID[m.ID]
		if !exists || !isNoteFor(strings.TrimSuffix(file, ".md"), want) {
			// A new note, or a renamed person: a free file name (Sam.md, Sam (2).md…).
			name := want + ".md"
			for i := 2; taken[strings.ToLower(name)]; i++ {
				name = fmt.Sprintf("%s (%d).md", want, i)
			}
			if exists {
				if err := os.Rename(filepath.Join(dir, file), filepath.Join(dir, name)); err != nil {
					return err
				}
				delete(taken, strings.ToLower(file))
			}
			taken[strings.ToLower(name)] = true
			file = name
		}
		path := filepath.Join(dir, file)
		old, err := os.ReadFile(path)
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		text := string(old)
		if len(old) == 0 {
			text = "# " + m.Name + "\n"
		}
		fields := [][2]string{
			{"type", "person"},
			{"famlogic_id", m.ID},
			{"role", m.access()},
			{"color", yamlString(m.Color)},
			{"emoji", yamlString(m.Emoji)},
		}
		updated := setFront(text, fields)
		if updated != string(old) {
			if err := writeAtomic(path, []byte(updated)); err != nil {
				return err
			}
		}
	}
	return nil
}

// isNoteFor: "Sam" and "Sam (2)" are both fine names for Sam's note.
func isNoteFor(base, want string) bool {
	if strings.EqualFold(base, want) {
		return true
	}
	rest, ok := strings.CutPrefix(strings.ToLower(base), strings.ToLower(want)+" (")
	return ok && regexp.MustCompile(`^[0-9]+\)$`).MatchString(rest)
}

var badName = regexp.MustCompile(`[/\\:*?"<>|\x00-\x1f]`)

// noteName makes a person's name safe as a file name.
func noteName(name string) string {
	n := strings.TrimSpace(badName.ReplaceAllString(name, "-"))
	n = strings.Trim(n, ". ")
	if n == "" {
		n = "Someone"
	}
	return n
}

// yamlString quotes a value for front matter ("" for nothing).
func yamlString(s string) string {
	if s == "" {
		return `""`
	}
	return `"` + strings.ReplaceAll(strings.ReplaceAll(s, `\`, `\\`), `"`, `\"`) + `"`
}

// splitFront returns a note's front-matter lines and its body ("ok" false if it has none).
func splitFront(text string) (lines []string, body string, ok bool) {
	t := strings.ReplaceAll(text, "\r\n", "\n")
	if !strings.HasPrefix(t, "---\n") {
		return nil, text, false
	}
	rest := t[4:]
	end := strings.Index(rest, "\n---\n")
	if end < 0 {
		if strings.HasSuffix(rest, "\n---") {
			end = len(rest) - 4
		} else {
			return nil, text, false
		}
	}
	front := rest[:end]
	body = strings.TrimPrefix(rest[end+4:], "\n")
	if front == "" {
		return nil, body, true
	}
	return strings.Split(front, "\n"), body, true
}

// frontValue reads one top-level front-matter field (unquoted).
func frontValue(text, key string) string {
	lines, _, ok := splitFront(text)
	if !ok {
		return ""
	}
	for _, l := range lines {
		if k, val, found := strings.Cut(l, ":"); found && k == key {
			return strings.Trim(strings.TrimSpace(val), `"'`)
		}
	}
	return ""
}

// setFront sets top-level front-matter fields, replacing their lines in place and
// adding missing ones at the end. Every other line, and the body, stay as they are.
func setFront(text string, fields [][2]string) string {
	lines, body, ok := splitFront(text)
	if !ok {
		body = text
	}
	done := map[string]bool{}
	out := make([]string, 0, len(lines)+len(fields))
	dropping := false // skipping the continuation lines of a value being replaced
	for _, l := range lines {
		indented := strings.HasPrefix(l, " ") || strings.HasPrefix(l, "\t") || strings.HasPrefix(l, "- ")
		if dropping && indented {
			continue // a list under a field we replaced belongs to its old value
		}
		dropping = false
		if k, _, found := strings.Cut(l, ":"); found && !indented {
			for _, f := range fields {
				if k == f[0] {
					l = f[0] + ": " + f[1]
					done[f[0]] = true
					dropping = true
				}
			}
		}
		out = append(out, l)
	}
	lines = out
	for _, f := range fields {
		if !done[f[0]] {
			lines = append(lines, f[0]+": "+f[1])
		}
	}
	return "---\n" + strings.Join(lines, "\n") + "\n---\n" + body
}

// writeAtomic writes a file so that nobody ever sees half of it.
func writeAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".famlogic-*.tmp")
	if err != nil {
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	if err := os.Chmod(tmp.Name(), 0o644); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// Status is what the console shows: where the vault is and whether it's working.
func (v *Vault) Status() map[string]any {
	v.mu.Lock()
	defer v.mu.Unlock()
	st := map[string]any{"path": v.dir, "writable": v.err == nil, "user": fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid())}
	if v.err != nil {
		st["error"] = v.err.Error()
	}
	if !v.synced.IsZero() {
		st["synced"] = v.synced.UnixMilli()
	}
	notes := 0
	filepath.WalkDir(v.dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && strings.HasSuffix(d.Name(), ".md") {
			notes++
		}
		return nil
	})
	st["notes"] = notes
	return st
}

func (s *Server) handleVaultGuide(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Write([]byte(vaultGuide))
}

func (s *Server) handleAdminVault(w http.ResponseWriter, r *http.Request) {
	if s.vault == nil {
		writeJSON(w, http.StatusOK, map[string]any{"path": "", "writable": false, "error": "the vault isn't set up"})
		return
	}
	writeJSON(w, http.StatusOK, s.vault.Status())
}
