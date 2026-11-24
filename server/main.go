// FamLogic server: a small, private, self-hosted family hub. Modules: Thereabouts
// (location sharing, SOS, fences, trips), and more to come (chat, planner, chores).
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

// OpenStreetMap's own tiles. (CARTO's basemaps, used briefly, now need an API
// key; admins who have one can set TILE_URL and TILE_URL_DARK to them.)
const defaultTiles = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

type Config struct {
	Listen          string
	DataDir         string
	AdminPassword   string
	PublicURL       string
	FamilyName      string
	TileURL         string
	TileURLDark     string // tiles for apps and browsers in dark mode
	TileAttribution string
	GeocoderURL     string
	InviteTTL       time.Duration
	SilenceAfter    time.Duration // 0 = no "hasn't been in touch" alerts
	BackupDays      int           // daily backups kept; 0 = none
	VaultDir        string        // the family's Markdown notes (DATA_DIR/vault unless VAULT_DIR is set)
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
		TileURL:         env("TILE_URL", defaultTiles),
		TileAttribution: env("TILE_ATTRIBUTION", ""),
		GeocoderURL:     env("GEOCODER_URL", "https://nominatim.openstreetmap.org/search"),
	}
	// An optional separate map for dark mode; apps dim light maps at night otherwise.
	c.TileURLDark = env("TILE_URL_DARK", "")
	if c.TileAttribution == "" {
		c.TileAttribution = "© OpenStreetMap contributors"
	}
	hours, err := strconv.Atoi(env("INVITE_HOURS", "72"))
	if err != nil || hours < 1 {
		return c, errors.New("INVITE_HOURS must be a whole number of hours, at least 1")
	}
	c.InviteTTL = time.Duration(hours) * time.Hour
	silence, err := strconv.Atoi(env("SILENCE_HOURS", "6"))
	if err != nil || silence < 0 {
		return c, errors.New("SILENCE_HOURS must be a whole number of hours (0 turns the alert off)")
	}
	c.SilenceAfter = time.Duration(silence) * time.Hour
	c.VaultDir = filepath.Clean(env("VAULT_DIR", filepath.Join(c.DataDir, "vault")))
	c.BackupDays, err = strconv.Atoi(env("BACKUP_DAYS", "7"))
	if err != nil || c.BackupDays < 0 {
		return c, errors.New("BACKUP_DAYS must be a whole number (0 turns backups off)")
	}
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
	// Started as root (the image's default): hand the folders to PUID:PGID and
	// carry on as that user. Already someone else: nothing to do.
	if err := dropPrivileges(cfg.DataDir, cfg.VaultDir); err != nil {
		log.Fatalf("switching user: %v", err)
	}
	dataFile := filepath.Join(cfg.DataDir, "famlogic.json")
	store, err := OpenStore(dataFile)
	if err == nil {
		err = store.EnsureDefaultCircle(cfg.FamilyName)
	}
	if err != nil {
		if errors.Is(err, os.ErrPermission) {
			log.Fatalf("can't write to %s (%v). The container runs as user %d; make sure that user owns the data folder, "+
				"or run the container as the folder's owner (user: in docker-compose.yml).", cfg.DataDir, err, os.Getuid())
		}
		log.Fatalf("opening data file in %s: %v", cfg.DataDir, err)
	}

	static, _ := fs.Sub(webFS, "web")
	app := NewServer(cfg, store, static)
	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           app.Routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	bg, stopBg := context.WithCancel(context.Background())
	defer stopBg()
	go store.WatchSilence(bg, cfg.SilenceAfter)
	go store.KeepBackups(bg, filepath.Join(cfg.DataDir, "backups"), cfg.BackupDays)
	if app.vault != nil {
		go app.vault.Keep(bg, store)
	}
	go app.KeepChoreReminders(bg)
	go app.KeepCheckPlans(bg)

	go func() {
		log.Printf("FamLogic %s listening on %s (data in %s, vault in %s, running as %d:%d)", version, cfg.Listen, cfg.DataDir, cfg.VaultDir, os.Getuid(), os.Getgid())
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
