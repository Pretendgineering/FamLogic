package main

// Steward's note tools: besides FamLogic's own actions (which keep permissions,
// stars and reminders right), Steward can read the vault guide and read and
// write notes directly, for anything the actions don't cover (and for modules
// that are still coming, whose notes live in their folders already).
// Writing notes is for grown-ups; children use the actions.

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf8"
)

func init() {
	stewardTools = append(stewardTools, stewardNoteTools...)
}

// notePath turns a vault-relative path into a file path, refusing anything outside
// the vault, anything that isn't a Markdown note, and FamLogic's own files.
func (s *Server) notePath(rel string, writing bool) (string, error) {
	if s.vault == nil {
		return "", errors.New("the notes vault is off on this server")
	}
	rel = filepath.ToSlash(strings.TrimSpace(rel))
	rel = strings.TrimPrefix(rel, "/")
	clean := filepath.ToSlash(filepath.Clean(rel))
	if rel == "" || clean == "." || strings.HasPrefix(clean, "../") || clean == ".." || strings.Contains(rel, "\x00") {
		return "", errors.New("that path is outside the vault")
	}
	if writing {
		if !strings.HasSuffix(strings.ToLower(clean), ".md") {
			return "", errors.New("notes end in .md")
		}
		top, _, _ := strings.Cut(clean, "/")
		if top == "_FamLogic" || clean == "AGENTS.md" || strings.HasPrefix(top, ".") {
			return "", errors.New("FamLogic keeps that file itself; don't change it")
		}
	}
	return filepath.Join(s.vault.dir, filepath.FromSlash(clean)), nil
}

var stewardNoteTools = []stewardTool{
	{"read_vault_guide", "The vault's formatting guide: folders, note types and their exact formats, and the rules. Read it before writing any note.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return map[string]any{"guide": vaultGuide}
		}},
	{"list_notes", "The notes and folders in one vault folder (e.g. Lists, Chores, Calendar, Meals/Recipes, Pets). Empty for the top.", obj(map[string]any{
		"folder": str("A folder in the vault, e.g. Meals/Recipes"),
	}),
		func(s *Server, me *Member, a map[string]any) any {
			folder := argStr(a, "folder")
			dir := ""
			if s.vault != nil {
				dir = s.vault.dir
			}
			if folder != "" {
				p, err := s.notePath(folder, false)
				if err != nil {
					return map[string]any{"error": err.Error()}
				}
				dir = p
			}
			entries, err := os.ReadDir(dir)
			if err != nil {
				return map[string]any{"error": "no such folder", "folders": []string{}, "notes": []string{}}
			}
			folders, notes := []string{}, []string{}
			for _, e := range entries {
				n := e.Name()
				if strings.HasPrefix(n, ".") {
					continue
				}
				if e.IsDir() {
					folders = append(folders, n)
				} else if strings.HasSuffix(n, ".md") {
					notes = append(notes, n)
				}
			}
			sort.Strings(folders)
			sort.Strings(notes)
			if len(notes) > 300 {
				notes = notes[:300]
			}
			return map[string]any{"folder": folder, "folders": folders, "notes": notes}
		}},
	{"read_note", "Read one note (its whole Markdown, front matter included).", obj(map[string]any{
		"path": str("Path in the vault, e.g. Lists/Shopping.md"),
	}, "path"),
		func(s *Server, me *Member, a map[string]any) any {
			p, err := s.notePath(argStr(a, "path"), false)
			if err != nil {
				return map[string]any{"error": err.Error()}
			}
			b, err := os.ReadFile(p)
			if err != nil {
				return map[string]any{"error": "no such note"}
			}
			text := string(b)
			if len(text) > 10000 {
				text = text[:10000] + "\n…(cut short)"
			}
			return map[string]any{"path": argStr(a, "path"), "text": text}
		}},
	{"write_note", "Create or replace one note with the whole Markdown given (front matter, # Title, body), following the vault guide. Re-read a note just before changing it and keep everything you don't mean to change. Prefer the specific tools (add_chore, add_event, add_to_list, add_recipe, plan_meal...) when one fits. Grown-ups only.", obj(map[string]any{
		"path": str("Path in the vault, e.g. Pets/Biscuit.md"),
		"text": str("The note's full Markdown"),
	}, "path", "text"),
		func(s *Server, me *Member, a map[string]any) any {
			if !isAdult(me) {
				return map[string]any{"error": "only grown-ups can change notes directly; use the other tools"}
			}
			p, err := s.notePath(argStr(a, "path"), true)
			if err != nil {
				return map[string]any{"error": err.Error()}
			}
			text, _ := a["text"].(string)
			if strings.TrimSpace(text) == "" || !utf8.ValidString(text) || len(text) > 200_000 {
				return map[string]any{"error": "the note is empty or too big"}
			}
			if !strings.HasSuffix(text, "\n") {
				text += "\n"
			}
			_, existed := os.Stat(p)
			if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
				return map[string]any{"error": "couldn't make the folder"}
			}
			if err := writeAtomic(p, []byte(text)); err != nil {
				return map[string]any{"error": "couldn't save the note"}
			}
			return map[string]any{"saved": argStr(a, "path"), "new": existed != nil}
		}},
}

// stewardPages describes each page Steward can be asked from, so it knows what
// "this" means ("add eggs to this", "move this to Friday").
var stewardPages = map[string]string{
	"home":        "the Home screen",
	"thereabouts": "Thereabouts (the map: who's where, fences)",
	"chores":      "Upkeep (chores and stars)",
	"todos":       "Docket (to-dos: one-off jobs, errands and projects; and shared lists like Shopping)",
	"calendar":    "Datebook (the family calendar)",
	"routines":    "Rhythms (routines)",
	"meals":       "Pantry (this week's meals and the recipes)",
	"homestead":   "Homestead (the home's profile: what it has, bin days, and the upkeep chores it keeps)",
	"display":     "Corkboard (the TV: quiet time and messages to it)",
	"meetings":    "Huddle (family meetings: slides with jobs to hand out and notes)",
	"profiles":    "Dossier (everyone's profile: allergies, medicines, emergency contacts...)",
	"family":      "the Family page",
	"settings":    "Settings",
}

// soonModules names the coming-soon modules the family has switched on ("none" if none).
func (s *Server) soonModules() string {
	on := s.store.Modules().Enabled
	names := []string{}
	for _, m := range allModules {
		if !m.Available && on[m.ID] && m.ID != "display" {
			names = append(names, m.Plain)
		}
	}
	if len(names) == 0 {
		return "none"
	}
	return strings.Join(names, ", ")
}
