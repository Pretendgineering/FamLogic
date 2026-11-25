package main

// Full backups and restoring them.
//
// A backup is one zip of everything the family has made:
//
//	famlogic.json   people, devices, fences, groups, settings, keys (hashes only)
//	chat.db         every chat, as a clean copy of the database
//	media/          chat photos and voice clips
//	timeline/       the feed, a file a day
//	steward/        Steward conversations
//	caldav/         calendar details only calendar apps use
//	vault/          the Markdown notes (chores, lists, calendar, routines, meals, recipes, people)
//	backup.json     what made it, and when
//
// Restoring checks the zip, saves it as DATA_DIR/restore.zip and restarts the
// server; on the way up it moves what's there now to
// DATA_DIR/backups/before-restore-<time>/ and unpacks the zip in its place
// (the notes go to VAULT_DIR, wherever that is now). A zip dropped into
// DATA_DIR/restore.zip by hand is restored the same way at the next start.

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
)

// backupDirs are the folders in DATA_DIR a backup carries (besides the vault).
var backupDirs = []string{"media", "timeline", "steward", "caldav"}

// writeBackup writes the whole family to w as a zip.
func (s *Server) writeBackup(w io.Writer) error {
	z := zip.NewWriter(w)
	dataDir := filepath.Dir(s.store.path)
	add := func(name string, b []byte) error {
		f, err := z.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: time.Now()})
		if err != nil {
			return err
		}
		_, err = f.Write(b)
		return err
	}
	addFile := func(name, src string, mod time.Time) error {
		in, err := os.Open(src)
		if err != nil {
			return err
		}
		defer in.Close()
		f, err := z.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: mod})
		if err != nil {
			return err
		}
		_, err = io.Copy(f, in)
		return err
	}
	addTree := func(prefix, root string) error {
		err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				if errors.Is(err, fs.ErrNotExist) {
					return nil
				}
				return err
			}
			rel, _ := filepath.Rel(root, p)
			if d.IsDir() {
				return nil
			}
			if !d.Type().IsRegular() || strings.HasSuffix(d.Name(), ".tmp") {
				return nil
			}
			info, err := d.Info()
			if err != nil {
				return nil
			}
			return addFile(path.Join(prefix, filepath.ToSlash(rel)), p, info.ModTime())
		})
		if errors.Is(err, fs.ErrNotExist) {
			return nil
		}
		return err
	}

	meta, _ := json.MarshalIndent(map[string]any{"app": "FamLogic", "version": version, "made": time.Now().UTC().Format(time.RFC3339), "family": s.familyName()}, "", "  ")
	if err := add("backup.json", meta); err != nil {
		return err
	}
	snap, err := s.store.Snapshot()
	if err != nil {
		return err
	}
	if err := add("famlogic.json", snap); err != nil {
		return err
	}
	if s.chat != nil {
		tmp := filepath.Join(dataDir, fmt.Sprintf("chat-backup-%d.db.tmp", time.Now().UnixNano()))
		if err := s.chat.Backup(tmp); err != nil {
			return fmt.Errorf("copying the chat database: %w", err)
		}
		err := addFile("chat.db", tmp, time.Now())
		os.Remove(tmp)
		if err != nil {
			return err
		}
	}
	for _, d := range backupDirs {
		if err := addTree(d, filepath.Join(dataDir, d)); err != nil {
			return err
		}
	}
	if s.vault != nil {
		if err := addTree("vault", s.vault.dir); err != nil {
			return err
		}
	}
	return z.Close()
}

// handleAdminBackup downloads the whole family as a zip. It holds password and
// token hashes, so keep it as safe as the server itself.
func (s *Server) handleAdminBackup(w http.ResponseWriter, r *http.Request) {
	rc := http.NewResponseController(w)
	_ = rc.SetWriteDeadline(time.Now().Add(30 * time.Minute)) // big photo folders take a while
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="famlogic-backup-`+time.Now().Format("2006-01-02")+`.zip"`)
	if err := s.writeBackup(w); err != nil {
		log.Printf("backup download: %v", err)
	}
}

// restoreName is where a backup waits to be restored at the next start.
const restoreName = "restore.zip"

// checkBackup opens a backup zip and makes sure it is one: a famlogic.json that
// reads, and no file that would land outside its folder.
func checkBackup(p string) (map[string]any, error) {
	z, err := zip.OpenReader(p)
	if err != nil {
		return nil, errors.New("that isn't a zip file")
	}
	defer z.Close()
	var meta map[string]any
	found := false
	for _, f := range z.File {
		if _, ok := restoreTarget(f.Name); !ok && !strings.HasSuffix(f.Name, "/") && f.Name != "backup.json" {
			return nil, fmt.Errorf("unexpected file in the backup: %s", f.Name)
		}
		switch f.Name {
		case "famlogic.json":
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			var d data
			err = json.NewDecoder(rc).Decode(&d)
			rc.Close()
			if err != nil {
				return nil, errors.New("the family file in the backup can't be read")
			}
			found = true
		case "backup.json":
			if rc, err := f.Open(); err == nil {
				_ = json.NewDecoder(rc).Decode(&meta)
				rc.Close()
			}
		}
	}
	if !found {
		return nil, errors.New("this zip has no famlogic.json, so it isn't a FamLogic backup")
	}
	if meta == nil {
		meta = map[string]any{}
	}
	return meta, nil
}

// restoreTarget says which folder a backup entry goes to ("data" or "vault")
// and refuses anything that would escape it.
func restoreTarget(name string) (string, bool) {
	if name == "" || strings.HasPrefix(name, "/") || strings.Contains(name, "\\") {
		return "", false
	}
	clean := path.Clean(name)
	if clean != strings.TrimSuffix(name, "/") || clean == ".." || strings.HasPrefix(clean, "../") {
		return "", false
	}
	top, _, _ := strings.Cut(clean, "/")
	switch {
	case clean == "famlogic.json" || clean == "chat.db":
		return "data", true
	case top == "vault" && clean != "vault":
		return "vault", true
	}
	for _, d := range backupDirs {
		if top == d && clean != d {
			return "data", true
		}
	}
	return "", false
}

// handleAdminRestore takes a backup zip, checks it, and restarts the server to restore it.
func (s *Server) handleAdminRestore(w http.ResponseWriter, r *http.Request) {
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Now().Add(30 * time.Minute))
	_ = rc.SetWriteDeadline(time.Now().Add(31 * time.Minute))
	dataDir := filepath.Dir(s.store.path)
	r.Body = http.MaxBytesReader(w, r.Body, 8<<30)
	tmp, err := os.CreateTemp(dataDir, "restore-*.zip.tmp")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save the upload")
		return
	}
	defer os.Remove(tmp.Name())
	var src io.Reader = r.Body
	if strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data") {
		mr, err := r.MultipartReader()
		if err != nil {
			tmp.Close()
			writeError(w, http.StatusBadRequest, "send the backup zip")
			return
		}
		part, err := mr.NextPart()
		if err != nil {
			tmp.Close()
			writeError(w, http.StatusBadRequest, "send the backup zip")
			return
		}
		src = part
	}
	if _, err := io.Copy(tmp, src); err != nil {
		tmp.Close()
		writeError(w, http.StatusBadRequest, "the upload didn't finish")
		return
	}
	if err := tmp.Close(); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save the upload")
		return
	}
	meta, err := checkBackup(tmp.Name())
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := os.Rename(tmp.Name(), filepath.Join(dataDir, restoreName)); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save the upload")
		return
	}
	log.Printf("restore: a backup (%v, made %v) is ready; restarting to restore it", meta["family"], meta["made"])
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "family": meta["family"], "made": meta["made"], "restarting": s.restart != nil})
	if s.restart != nil {
		go func() { time.Sleep(500 * time.Millisecond); s.restart() }()
	}
}

// applyPendingRestore runs at start, before anything is opened: if a backup is
// waiting, what's there now moves aside to backups/before-restore-<time>/ and
// the backup is unpacked in its place.
func applyPendingRestore(dataDir, vaultDir string) error {
	zipPath := filepath.Join(dataDir, restoreName)
	if _, err := os.Stat(zipPath); err != nil {
		return nil
	}
	if _, err := checkBackup(zipPath); err != nil {
		bad := zipPath + ".bad"
		_ = os.Rename(zipPath, bad)
		return fmt.Errorf("%s isn't a backup FamLogic can restore (%v); it's now %s", zipPath, err, bad)
	}
	aside := filepath.Join(dataDir, "backups", "before-restore-"+time.Now().Format("2006-01-02-150405"))
	if err := os.MkdirAll(aside, 0o700); err != nil {
		return err
	}
	// Move today's data aside.
	for _, n := range append([]string{"famlogic.json", "chat.db", "chat.db-wal", "chat.db-shm"}, backupDirs...) {
		if err := moveAside(filepath.Join(dataDir, n), filepath.Join(aside, n)); err != nil {
			return err
		}
	}
	// The vault may be a mounted folder: move what's inside it, not the folder.
	if entries, err := os.ReadDir(vaultDir); err == nil {
		for _, e := range entries {
			if err := moveAside(filepath.Join(vaultDir, e.Name()), filepath.Join(aside, "vault", e.Name())); err != nil {
				return err
			}
		}
	}
	z, err := zip.OpenReader(zipPath)
	if err != nil {
		return err
	}
	defer z.Close()
	for _, f := range z.File {
		where, ok := restoreTarget(f.Name)
		if !ok || strings.HasSuffix(f.Name, "/") {
			continue
		}
		dest := filepath.Join(dataDir, filepath.FromSlash(path.Clean(f.Name)))
		if where == "vault" {
			dest = filepath.Join(vaultDir, filepath.FromSlash(strings.TrimPrefix(path.Clean(f.Name), "vault/")))
		}
		if err := unzipFile(f, dest); err != nil {
			return fmt.Errorf("restoring %s: %w", f.Name, err)
		}
	}
	z.Close()
	if err := os.Rename(zipPath, filepath.Join(aside, "restored-from.zip")); err != nil {
		return err
	}
	log.Printf("restore: done. What was here before is in %s", aside)
	return nil
}

func unzipFile(f *zip.File, dest string) error {
	if err := os.MkdirAll(filepath.Dir(dest), 0o700); err != nil {
		return err
	}
	in, err := f.Open()
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if !f.Modified.IsZero() {
		_ = os.Chtimes(dest, f.Modified, f.Modified)
	}
	return nil
}

// moveAside renames src to dst, copying when they're on different drives.
func moveAside(src, dst string) error {
	if _, err := os.Lstat(src); errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil {
		return err
	}
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	if err := copyTree(src, dst); err != nil {
		return err
	}
	return os.RemoveAll(src)
}

func copyTree(src, dst string) error {
	return filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel(src, p)
		target := filepath.Join(dst, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		if !d.Type().IsRegular() {
			return nil
		}
		in, err := os.Open(p)
		if err != nil {
			return err
		}
		defer in.Close()
		out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
		if err != nil {
			return err
		}
		if _, err := io.Copy(out, in); err != nil {
			out.Close()
			return err
		}
		return out.Close()
	})
}
