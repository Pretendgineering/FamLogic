package main

import (
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// The image's own user, which owns /data in every install so far.
const defaultUID = 65532

// dropPrivileges runs once at start-up, before anything else. When the container
// starts as root (the image's default), the server makes sure its folders belong
// to PUID:PGID and then switches to that user for good, so it never runs as root.
//
//   - PUID/PGID unset: the image's own user (65532), as before. The data folder
//     is handed to it; a vault folder mounted from the host is left alone.
//   - PUID/PGID set (your user on the host: run `id`): the data folder and the
//     vault are handed to that user, so files FamLogic writes are yours, and
//     Obsidian, Syncthing or an agent on the host can edit them.
//
// Started as someone else already (user: in docker-compose.yml), it does nothing.
func dropPrivileges(dataDir, vaultDir string) error {
	if os.Getuid() != 0 {
		return nil
	}
	uid, gid, chosen, err := wantedUser()
	if err != nil {
		return err
	}
	if uid == 0 {
		log.Printf("PUID is 0: running as root. That works, but a normal user is safer.")
		return nil
	}
	for _, d := range []string{dataDir, vaultDir} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	// Our data folder, except the vault when it's somebody else's folder (mounted
	// from the host and no PUID given): that one we never take over.
	skip := ""
	if !chosen {
		skip = vaultDir
	}
	if err := chownTree(dataDir, uid, gid, skip); err != nil {
		return err
	}
	if chosen && !inside(vaultDir, dataDir) {
		if err := chownTree(vaultDir, uid, gid, ""); err != nil {
			return err
		}
	} else if !chosen {
		// Not ours to take over, except the bare folder when root owns it (it was
		// just made, by us or by Docker for a mount that didn't exist yet).
		if fi, err := os.Stat(vaultDir); err == nil {
			if st, ok := fi.Sys().(*syscall.Stat_t); ok && st.Uid == 0 {
				os.Lchown(vaultDir, uid, gid)
			}
		}
	}
	if err := syscall.Setgroups([]int{}); err != nil {
		return fmt.Errorf("setgroups: %w", err)
	}
	if err := syscall.Setgid(gid); err != nil {
		return fmt.Errorf("setgid %d: %w", gid, err)
	}
	if err := syscall.Setuid(uid); err != nil {
		return fmt.Errorf("setuid %d: %w", uid, err)
	}
	return nil
}

// wantedUser reads PUID and PGID ("chosen" when either is set).
func wantedUser() (uid, gid int, chosen bool, err error) {
	uid, gid = defaultUID, defaultUID
	if v := strings.TrimSpace(os.Getenv("PUID")); v != "" {
		if uid, err = strconv.Atoi(v); err != nil || uid < 0 {
			return 0, 0, false, errors.New("PUID must be a user ID number (run `id -u` on the host)")
		}
		gid, chosen = uid, true
	}
	if v := strings.TrimSpace(os.Getenv("PGID")); v != "" {
		if gid, err = strconv.Atoi(v); err != nil || gid < 0 {
			return 0, 0, false, errors.New("PGID must be a group ID number (run `id -g` on the host)")
		}
		chosen = true
	}
	return uid, gid, chosen, nil
}

// chownTree hands a folder and everything in it to uid:gid, touching only files
// that belong to someone else, and leaving out skip (and what's in it).
func chownTree(root string, uid, gid int, skip string) error {
	return filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // unreadable: leave it, the server says so if it matters
		}
		if skip != "" && p == skip {
			return filepath.SkipDir
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		if st, ok := info.Sys().(*syscall.Stat_t); ok && (int(st.Uid) != uid || int(st.Gid) != gid) {
			if err := os.Lchown(p, uid, gid); err != nil {
				return fmt.Errorf("can't hand %s to %d:%d: %w", p, uid, gid, err)
			}
		}
		return nil
	})
}

// inside reports whether p is dir or somewhere in it.
func inside(p, dir string) bool {
	rel, err := filepath.Rel(dir, p)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
