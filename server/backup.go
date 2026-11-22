package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Backups: once a day the server copies its data file into DATA_DIR/backups
// and keeps the newest few. The dashboard can also download a copy any time.

// Snapshot returns the whole data file as it is now.
func (s *Store) Snapshot() ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return json.MarshalIndent(s.d, "", "  ")
}

// DailyBackup writes today's backup if there isn't one yet and deletes all but the newest `keep`.
func (s *Store) DailyBackup(dir string, keep int) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	name := filepath.Join(dir, "famlogic-"+time.Now().Format("2006-01-02")+".json")
	if _, err := os.Stat(name); err != nil {
		b, err := s.Snapshot()
		if err != nil {
			return err
		}
		tmp := name + ".tmp"
		if err := os.WriteFile(tmp, b, 0o600); err != nil {
			return err
		}
		if err := os.Rename(tmp, name); err != nil {
			return err
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	var backups []string
	for _, e := range entries {
		if n := e.Name(); (strings.HasPrefix(n, "famlogic-") || strings.HasPrefix(n, "thereabouts-")) && strings.HasSuffix(n, ".json") {
			backups = append(backups, n)
		}
	}
	sort.Strings(backups) // dates sort in order
	for len(backups) > keep {
		_ = os.Remove(filepath.Join(dir, backups[0]))
		backups = backups[1:]
	}
	return nil
}

// KeepBackups makes the daily backup now and then every hour (it's a no-op
// until the date changes). keep <= 0 turns it off.
func (s *Store) KeepBackups(ctx context.Context, dir string, keep int) {
	if keep <= 0 {
		return
	}
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		if err := s.DailyBackup(dir, keep); err != nil {
			log.Printf("backup: %v", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
