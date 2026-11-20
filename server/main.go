// Thereabouts server: a tiny, private family location sharing server.
package main

import (
	"context"
	"embed"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

//go:embed web
var webFS embed.FS

var version = "dev"

type Config struct {
	Listen          string
	DataDir         string
	AdminPassword   string
	PublicURL       string
	FamilyName      string
	TileURL         string
	TileAttribution string
	InviteTTL       time.Duration
}

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func loadConfig() (Config, error) {
	c := Config{
		Listen:          env("LISTEN", ":8080"),
		DataDir:         env("DATA_DIR", "/data"),
		AdminPassword:   os.Getenv("ADMIN_PASSWORD"),
		PublicURL:       strings.TrimRight(env("PUBLIC_URL", ""), "/"),
		FamilyName:      env("FAMILY_NAME", "Family"),
		TileURL:         env("TILE_URL", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
		TileAttribution: env("TILE_ATTRIBUTION", "© OpenStreetMap contributors"),
	}
	hours, err := strconv.Atoi(env("INVITE_HOURS", "72"))
	if err != nil || hours < 1 {
		return c, errors.New("INVITE_HOURS must be a whole number of hours, at least 1")
	}
	c.InviteTTL = time.Duration(hours) * time.Hour
	if len(c.AdminPassword) < 8 {
		return c, errors.New("ADMIN_PASSWORD must be set and at least 8 characters long")
	}
	return c, nil
}

func main() {
	healthcheck := flag.Bool("healthcheck", false, "check that a server on LISTEN is healthy, then exit (used by Docker)")
	flag.Parse()

	if *healthcheck {
		os.Exit(runHealthcheck(env("LISTEN", ":8080")))
	}

	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}
	dataFile := filepath.Join(cfg.DataDir, "thereabouts.json")
	if err := migrateOldDataFile(cfg.DataDir, dataFile); err != nil {
		log.Fatalf("moving old data file: %v", err)
	}
	store, err := OpenStore(dataFile)
	if err == nil {
		err = store.EnsureDefaultCircle(cfg.FamilyName)
	}
	if err != nil {
		if errors.Is(err, os.ErrPermission) {
			log.Fatalf("can't write to %s (%v). The container runs as user %d; make sure that user owns the data folder, "+
				"or run the container as the folder's owner (e.g. user: \"568:568\" on TrueNAS).", cfg.DataDir, err, os.Getuid())
		}
		log.Fatalf("opening data file in %s: %v", cfg.DataDir, err)
	}

	static, _ := fs.Sub(webFS, "web")
	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           NewServer(cfg, store, static).Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		log.Printf("Thereabouts %s listening on %s (data in %s)", version, cfg.Listen, cfg.DataDir)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}

// migrateOldDataFile renames the data file from before the project was called
// Thereabouts (libre360.json), so upgrading keeps everyone's family and settings.
func migrateOldDataFile(dir, newPath string) error {
	oldPath := filepath.Join(dir, "libre360.json")
	if _, err := os.Stat(newPath); err == nil {
		return nil // already migrated (or a fresh install)
	}
	if _, err := os.Stat(oldPath); err != nil {
		return nil // nothing to migrate
	}
	log.Printf("Renaming %s to %s (the project is now called Thereabouts)", oldPath, newPath)
	return os.Rename(oldPath, newPath)
}

func runHealthcheck(listen string) int {
	port := listen[strings.LastIndex(listen, ":")+1:]
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/healthz")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
