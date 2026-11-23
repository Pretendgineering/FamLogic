package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestVaultPeopleNotes(t *testing.T) {
	dir := t.TempDir()
	v := openVault(dir)
	for _, f := range []string{"FamLogic.md", "AGENTS.md", "People", "Lists", "Meals/Recipes"} {
		if _, err := os.Stat(filepath.Join(dir, f)); err != nil {
			t.Fatalf("%s missing: %v", f, err)
		}
	}
	sam := Member{ID: "m1", Name: "Sam", Color: "#E0A030", Emoji: "🦊", Role: "child"}
	v.Sync([]Member{sam})
	note := filepath.Join(dir, "People", "Sam.md")
	b, err := os.ReadFile(note)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"type: person", "famlogic_id: m1", `color: "#E0A030"`, `emoji: "🦊"`, "# Sam"} {
		if !strings.Contains(string(b), want) {
			t.Fatalf("note lacks %q:\n%s", want, b)
		}
	}

	// Someone adds their own field and text; FamLogic keeps them.
	edited := strings.Replace(string(b), "---\n# Sam", "birthday: 2018-04-09\nhobbies:\n  - football\n---\n# Sam\n\nAllergic to peanuts.", 1)
	os.WriteFile(note, []byte(edited), 0o644)
	sam.Color = "#3366FF"
	v.Sync([]Member{sam})
	b, _ = os.ReadFile(note)
	for _, want := range []string{`color: "#3366FF"`, "birthday: 2018-04-09", "  - football", "Allergic to peanuts."} {
		if !strings.Contains(string(b), want) {
			t.Fatalf("after update, note lacks %q:\n%s", want, b)
		}
	}

	// Renamed: the note follows, with what's in it.
	sam.Name = "Samuel"
	v.Sync([]Member{sam})
	if _, err := os.Stat(note); !os.IsNotExist(err) {
		t.Fatal("old note still there")
	}
	b, err = os.ReadFile(filepath.Join(dir, "People", "Samuel.md"))
	if err != nil || !strings.Contains(string(b), "Allergic to peanuts.") {
		t.Fatalf("renamed note: %v\n%s", err, b)
	}

	// Two people with the same name, and a name that isn't a safe file name.
	v.Sync([]Member{sam, {ID: "m2", Name: "Samuel"}, {ID: "m3", Name: "Mum/Dad: home"}})
	for _, f := range []string{"Samuel.md", "Samuel (2).md", "Mum-Dad- home.md"} {
		if _, err := os.Stat(filepath.Join(dir, "People", f)); err != nil {
			t.Fatalf("%s: %v", f, err)
		}
	}
	// Nothing changes on a second pass, and no temp files are left behind.
	info, _ := os.Stat(filepath.Join(dir, "People", "Samuel (2).md"))
	v.Sync([]Member{sam, {ID: "m2", Name: "Samuel"}, {ID: "m3", Name: "Mum/Dad: home"}})
	again, _ := os.Stat(filepath.Join(dir, "People", "Samuel (2).md"))
	if !info.ModTime().Equal(again.ModTime()) {
		t.Fatal("an unchanged note was rewritten")
	}
	left, _ := filepath.Glob(filepath.Join(dir, "*", ".famlogic-*"))
	if len(left) > 0 {
		t.Fatalf("temp files left: %v", left)
	}
	if st := v.Status(); st["writable"] != true || st["notes"].(int) < 5 {
		t.Fatalf("status: %v", st)
	}
}

func TestVaultHomeNoteIsTheFamilys(t *testing.T) {
	dir := t.TempDir()
	openVault(dir)
	os.WriteFile(filepath.Join(dir, "FamLogic.md"), []byte("# Ours\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "AGENTS.md"), []byte("scribbles"), 0o644)
	openVault(dir).Sync(nil)
	if b, _ := os.ReadFile(filepath.Join(dir, "FamLogic.md")); string(b) != "# Ours\n" {
		t.Fatalf("home note overwritten: %s", b)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, "AGENTS.md")); !strings.Contains(string(b), "formatting guide") {
		t.Fatal("guide not restored")
	}
}

func TestVaultGuideServedAndInDocs(t *testing.T) {
	store, err := OpenStore(filepath.Join(t.TempDir(), "famlogic.json"))
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(Config{AdminPassword: "correct horse"}, store, nil)
	rec := httptest.NewRecorder()
	s.Routes().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/vault/guide", nil))
	if rec.Code != 200 || !strings.HasPrefix(rec.Header().Get("Content-Type"), "text/markdown") || rec.Body.String() != vaultGuide {
		t.Fatalf("guide: %d %s", rec.Code, rec.Header().Get("Content-Type"))
	}
	// The copy in docs/agents must match the one the server ships.
	if b, err := os.ReadFile("../docs/agents/famlogic-vault.md"); err == nil && string(b) != vaultGuide {
		t.Fatal("docs/agents/famlogic-vault.md differs from server/vault_guide.md: copy it over")
	}
}

func TestSetFront(t *testing.T) {
	got := setFront("no front matter\n", [][2]string{{"type", "person"}})
	if got != "---\ntype: person\n---\nno front matter\n" {
		t.Fatalf("%q", got)
	}
	got = setFront("---\ntags:\n  - a\nrole: old\n---\nbody", [][2]string{{"tags", "[b]"}, {"role", "parent"}})
	if got != "---\ntags: [b]\nrole: parent\n---\nbody" {
		t.Fatalf("%q", got)
	}
}
