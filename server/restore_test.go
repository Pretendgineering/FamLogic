package main

import (
	"archive/zip"
	"bytes"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBackupAndRestore(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.cookie = f.owner
	if r, _ := e.do("POST", "/api/lists", "", map[string]any{"title": "Groceries"}); r.StatusCode >= 300 {
		t.Fatalf("list: %d", r.StatusCode)
	}
	if r, _ := e.do("POST", "/api/chat/conversations/family/messages", f.mom, map[string]any{"text": "hello from the backup"}); r.StatusCode >= 300 {
		t.Logf("chat message: %d (carrying on)", r.StatusCode)
	}

	// Download.
	req, _ := http.NewRequest("GET", e.srv.URL+"/api/admin/backup", nil)
	req.AddCookie(f.owner)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	zb, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.Header.Get("Content-Type") != "application/zip" {
		t.Fatalf("backup type %q", resp.Header.Get("Content-Type"))
	}
	z, err := zip.NewReader(bytes.NewReader(zb), int64(len(zb)))
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, f := range z.File {
		names[f.Name] = true
	}
	for _, want := range []string{"backup.json", "famlogic.json", "chat.db", "vault/Lists/Groceries.md"} {
		if !names[want] {
			t.Errorf("backup has no %s (has %v)", want, names)
		}
	}

	// A zip that isn't a backup, or tries to escape, is refused.
	bad := func(files map[string]string) []byte {
		var b bytes.Buffer
		w := zip.NewWriter(&b)
		for n, c := range files {
			fw, _ := w.Create(n)
			fw.Write([]byte(c))
		}
		w.Close()
		return b.Bytes()
	}
	upload := func(body []byte) int {
		req, _ := http.NewRequest("POST", e.srv.URL+"/api/admin/restore", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/zip")
		req.AddCookie(f.owner)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if c := upload([]byte("not a zip")); c != 400 {
		t.Fatalf("junk restore: %d", c)
	}
	if c := upload(bad(map[string]string{"notes.txt": "x"})); c != 400 {
		t.Fatalf("no famlogic.json: %d", c)
	}
	if c := upload(bad(map[string]string{"famlogic.json": "{}", "vault/../../etc/x": "x"})); c != 400 {
		t.Fatalf("escaping path: %d", c)
	}

	// A real one is accepted and waits for the restart.
	dataDir := filepath.Dir(e.path)
	if c := upload(zb); c != 200 {
		t.Fatalf("restore: %d", c)
	}
	if _, err := os.Stat(filepath.Join(dataDir, restoreName)); err != nil {
		t.Fatal("restore.zip not saved")
	}

	// Change things, then restore at "start": today's data moves aside and the backup comes back.
	vault := filepath.Join(dataDir, "vault")
	os.WriteFile(filepath.Join(vault, "Lists", "Groceries.md"), []byte("changed"), 0o600)
	os.WriteFile(filepath.Join(vault, "stray.md"), []byte("new since"), 0o600)
	if err := applyPendingRestore(dataDir, vault); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filepath.Join(vault, "Lists", "Groceries.md"))
	if strings.Contains(string(b), "changed") || len(b) == 0 {
		t.Fatalf("list not restored: %q", b)
	}
	if _, err := os.Stat(filepath.Join(vault, "stray.md")); err == nil {
		t.Fatal("a note made after the backup is still in the vault")
	}
	aside, _ := filepath.Glob(filepath.Join(dataDir, "backups", "before-restore-*", "vault", "stray.md"))
	if len(aside) != 1 {
		t.Fatal("the old vault wasn't kept aside")
	}
	if _, err := os.Stat(filepath.Join(dataDir, restoreName)); err == nil {
		t.Fatal("restore.zip still waiting")
	}
	if _, err := OpenStore(filepath.Join(dataDir, "famlogic.json")); err != nil {
		t.Fatalf("restored data file: %v", err)
	}
}
