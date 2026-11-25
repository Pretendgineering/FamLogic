package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"html/template"
	"io/fs"
	"math"
	"net/http"
	"net/url"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	adminCookie     = "famlogic_admin"
	adminSessionTTL = 30 * 24 * time.Hour
	maxBody         = 64 << 10
)

type Server struct {
	typing typingNow
	lists  *lists
	cal    *calendar
	dav    davLogins
	cfg    Config
	store  *Store
	static fs.FS

	loginMu sync.Mutex    // serialises failed logins so guessing is slow
	limiter *loginLimiter // locks a username after repeated wrong passwords
	geo     *geocoder     // address search for fences

	indexSeen *indexDeliveries // Index 01 ring deliveries already handled
	chat      *ChatStore       // nil if the chat database couldn't be opened
	voice     *Voice           // speech to text and reading aloud, when the tools are there
	vault     *Vault           // the family's Markdown notes (nil in tests that don't need it)
	chores    *chores          // Upkeep: chores as notes in the vault (nil without a vault)
}

func NewServer(cfg Config, store *Store, static fs.FS) *Server {
	s := &Server{cfg: cfg, store: store, static: static, limiter: newLoginLimiter(), geo: newGeocoder(cfg.GeocoderURL),
		indexSeen: &indexDeliveries{seen: map[string]time.Time{}}}
	if cfg.VaultDir != "" {
		s.vault = openVault(cfg.VaultDir)
		s.chores = newChores(s.vault)
		s.lists = newLists(s.vault)
		s.cal = newCalendar(s.vault, cfg.DataDir)
	}
	s.voice = openVoice(env("VOICE_DIR", "/voice"), filepath.Join(filepath.Dir(store.path), "tmp"))
	if s.chat = openChatNextTo(store.path); s.chat != nil {
		chat := s.chat
		store.AddBackupHook(func(dir string, keep int) error { return keepDaily(dir, "chat-", ".db", keep, chat.Backup) })
	}
	return s
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("GET /api/info", s.handleInfo)

	// Phone app API (authenticated with the device token).
	mux.HandleFunc("POST /api/join", s.handleJoin)
	mux.HandleFunc("GET /api/invites/{code}", s.handleInviteInfo)
	mux.HandleFunc("POST /api/signin", s.handleSignIn)
	mux.HandleFunc("POST /api/me/password", s.device(s.handleSetPassword))
	mux.HandleFunc("POST /api/me/circles/join", s.device(s.handleJoinCircle))
	mux.HandleFunc("GET /api/members/{id}/trail", s.device(s.handleTrail))
	mux.HandleFunc("GET /api/members/{id}/trips", s.device(s.handleTrips))
	mux.HandleFunc("GET /api/geocode", s.device(s.handleGeocode))
	mux.HandleFunc("POST /api/places", s.device(s.handleCreatePlace))
	mux.HandleFunc("PATCH /api/places/{id}", s.device(s.handleUpdatePlace))
	mux.HandleFunc("POST /api/places/{id}", s.device(s.handleUpdatePlace)) // for clients that can't send PATCH
	mux.HandleFunc("DELETE /api/places/{id}", s.device(s.handleDeletePlace))
	mux.HandleFunc("POST /api/me/circles/{id}", s.device(s.handleSetCircleSharing))
	mux.HandleFunc("GET /api/members", s.device(s.handleMembers))
	mux.HandleFunc("POST /api/location", s.device(s.handleLocation))
	mux.HandleFunc("GET /api/events/wait", s.device(s.handleWaitEvents))
	mux.HandleFunc("POST /api/sos", s.device(s.handleSOS))
	mux.HandleFunc("POST /api/sos/end", s.device(s.handleSOSEnd))
	mux.HandleFunc("POST /api/sos/{id}/seen", s.device(s.handleSOSSeen))
	mux.HandleFunc("POST /api/requests", s.device(s.handleRequest))
	mux.HandleFunc("GET /api/reminders", s.device(s.handleListReminders))
	mux.HandleFunc("POST /api/reminders", s.device(s.handleAddReminder))
	mux.HandleFunc("DELETE /api/reminders/{id}", s.device(s.handleDeleteReminder))
	mux.HandleFunc("POST /api/reminders/{id}/delete", s.device(s.handleDeleteReminder)) // for clients that can't send DELETE
	mux.HandleFunc("GET /api/me/index", s.device(s.handleIndexSettings))
	mux.HandleFunc("POST /api/me/index/reset", s.device(s.handleIndexSettings))
	// Pebble Index 01 ring webhooks: authenticated by the secret in the URL (and optional signing).
	mux.HandleFunc("POST /api/index/{token}/{action}", s.handleIndexWebhook)
	mux.HandleFunc("POST /api/checkin", s.device(s.handleCheckIn))
	mux.HandleFunc("POST /api/me/status", s.device(s.handleStatus))
	mux.HandleFunc("PATCH /api/me", s.device(s.handlePatchMe))
	mux.HandleFunc("POST /api/me", s.device(s.handlePatchMe)) // for clients that can't send PATCH
	mux.HandleFunc("DELETE /api/me", s.device(s.handleLeave))

	// Web dashboard API (authenticated with the admin password).
	mux.HandleFunc("POST /api/admin/login", s.handleLogin)
	mux.HandleFunc("POST /api/admin/logout", s.handleLogout)
	mux.HandleFunc("GET /api/admin/members", s.admin(s.handleAdminMembers))
	mux.HandleFunc("GET /api/admin/backup", s.admin(s.handleAdminBackup))
	mux.HandleFunc("GET /api/admin/modules", s.consoleUser(s.handleAdminModules)) // any console user: which modules are on
	mux.HandleFunc("PUT /api/admin/modules", s.admin(s.handleAdminSetModules))
	mux.HandleFunc("GET /api/modules", s.device(s.handleDeviceModules))
	mux.HandleFunc("PUT /api/modules", s.device(s.handleDeviceSetModules))
	mux.HandleFunc("POST /api/modules", s.device(s.handleDeviceSetModules)) // for clients that can't send PUT
	mux.HandleFunc("PUT /api/admin/members/{id}/role", s.admin(s.handleAdminSetRole))
	mux.HandleFunc("POST /api/admin/dependents", s.admin(s.handleAdminAddDependent))
	mux.HandleFunc("GET /api/admin/session", s.handleSession)
	mux.HandleFunc("GET /api/admin/chat", s.admin(s.handleAdminChatList))
	mux.HandleFunc("GET /api/admin/chat/{id}/messages", s.admin(s.handleAdminChatMessages))

	// Chat (phones, and parents in the web console).
	mux.HandleFunc("GET /api/chat", s.device(s.handleChatList))
	mux.HandleFunc("POST /api/chat/direct", s.device(s.handleChatDirect))
	mux.HandleFunc("POST /api/chat/groups", s.device(s.handleChatCreateGroup))
	mux.HandleFunc("PATCH /api/chat/{id}", s.device(s.handleChatUpdateGroup))
	mux.HandleFunc("POST /api/chat/{id}/update", s.device(s.handleChatUpdateGroup)) // for clients that can't send PATCH
	mux.HandleFunc("GET /api/chat/{id}/messages", s.device(s.handleChatMessages))
	mux.HandleFunc("POST /api/chat/{id}/messages", s.device(s.handleChatPost))
	mux.HandleFunc("POST /api/chat/{id}/read", s.device(s.handleChatRead))
	mux.HandleFunc("POST /api/chat/{id}/messages/{mid}/react", s.device(s.handleChatReact))
	mux.HandleFunc("POST /api/chat/{id}/typing", s.device(s.handleChatTyping))
	mux.HandleFunc("GET /api/chat/search", s.device(s.handleChatSearch))
	mux.HandleFunc("GET /api/chat-media/{name}", s.handleChatMedia)
	mux.HandleFunc("POST /api/chat/{id}/moderate", s.device(s.handleChatModerate))
	mux.HandleFunc("DELETE /api/chat/{id}/messages/{mid}", s.device(s.handleChatDeleteMessage))
	mux.HandleFunc("POST /api/chat/{id}/messages/{mid}/delete", s.device(s.handleChatDeleteMessage))
	mux.HandleFunc("POST /api/admin/chat/{id}/moderate", s.admin(s.handleAdminChatModerate))
	mux.HandleFunc("DELETE /api/admin/chat/{id}/messages/{mid}", s.admin(s.handleAdminChatDeleteMessage))
	// Parents' controls (kids.go).
	mux.HandleFunc("POST /api/pin/check", s.device(s.handleCheckPIN))
	mux.HandleFunc("POST /api/voice/transcribe", s.device(s.handleTranscribe))
	mux.HandleFunc("POST /api/voice/speak", s.device(s.handleSpeak))
	mux.HandleFunc("GET /api/v1/vault/guide", s.handleVaultGuide)
	// Upkeep (chores) and the family feed.
	mux.HandleFunc("GET /api/chores", s.choreAuth(s.handleChores))
	mux.HandleFunc("POST /api/chores", s.choreAuth(s.handleCreateChore))
	mux.HandleFunc("PATCH /api/chores/{id}", s.choreAuth(s.handleUpdateChore))
	mux.HandleFunc("POST /api/chores/{id}", s.choreAuth(s.handleUpdateChore)) // for clients that can't send PATCH
	mux.HandleFunc("DELETE /api/chores/{id}", s.choreAuth(s.handleArchiveChore))
	mux.HandleFunc("POST /api/chores/{id}/delete", s.choreAuth(s.handleArchiveChore))
	mux.HandleFunc("POST /api/chores/{id}/done", s.choreAuth(s.handleChoreDone))
	mux.HandleFunc("POST /api/chores/{id}/undo", s.choreAuth(s.handleChoreUndo))
	mux.HandleFunc("POST /api/members/{id}/stars", s.choreAuth(s.handleStars))
	mux.HandleFunc("GET /api/rewards", s.choreAuth(s.handleRewards))
	mux.HandleFunc("GET /api/lists", s.choreAuth(s.handleLists))
	mux.HandleFunc("GET /api/events", s.choreAuth(s.handleEvents))
	mux.HandleFunc("POST /api/events", s.choreAuth(s.handleSaveEvent))
	mux.HandleFunc("GET /api/events/{id}", s.choreAuth(s.handleEvent))
	mux.HandleFunc("PATCH /api/events/{id}", s.choreAuth(s.handleSaveEvent))
	mux.HandleFunc("POST /api/events/{id}", s.choreAuth(s.handleSaveEvent))
	mux.HandleFunc("DELETE /api/events/{id}", s.choreAuth(s.handleDeleteEvent))
	mux.HandleFunc("POST /api/events/{id}/delete", s.choreAuth(s.handleDeleteEvent))
	mux.HandleFunc("POST /api/lists", s.choreAuth(s.handleCreateList))
	mux.HandleFunc("DELETE /api/lists/{id}", s.choreAuth(s.handleArchiveList))
	mux.HandleFunc("POST /api/lists/{id}/delete", s.choreAuth(s.handleArchiveList))
	mux.HandleFunc("POST /api/lists/{id}/items", s.choreAuth(s.handleAddItem))
	mux.HandleFunc("PATCH /api/lists/{id}/items/{item}", s.choreAuth(s.handleTickItem))
	mux.HandleFunc("POST /api/lists/{id}/items/{item}", s.choreAuth(s.handleTickItem))
	mux.HandleFunc("DELETE /api/lists/{id}/items/{item}", s.choreAuth(s.handleRemoveItem))
	mux.HandleFunc("POST /api/lists/{id}/items/{item}/delete", s.choreAuth(s.handleRemoveItem))
	mux.HandleFunc("POST /api/lists/{id}/clear", s.choreAuth(s.handleClearList))
	mux.HandleFunc("GET /api/checkplans", s.choreAuth(s.handleCheckPlans))
	mux.HandleFunc("POST /api/checkplans", s.choreAuth(s.handleAddCheckPlan))
	mux.HandleFunc("DELETE /api/checkplans/{id}", s.choreAuth(s.handleDeleteCheckPlan))
	mux.HandleFunc("POST /api/checkplans/{id}/delete", s.choreAuth(s.handleDeleteCheckPlan))
	mux.HandleFunc("GET /api/steward", s.device(s.handleStewardGet))
	mux.HandleFunc("POST /api/steward", s.device(s.handleStewardSay))
	mux.HandleFunc("DELETE /api/steward", s.device(s.handleStewardClear))
	mux.HandleFunc("GET /api/admin/steward", s.admin(s.handleAdminSteward))
	mux.HandleFunc("PUT /api/admin/steward", s.admin(s.handleAdminSetSteward))
	mux.HandleFunc("POST /api/admin/steward/test", s.admin(s.handleAdminTestSteward))
	mux.HandleFunc("POST /api/rewards", s.choreAuth(s.handleAddReward))
	mux.HandleFunc("PATCH /api/rewards/{id}", s.choreAuth(s.handleUpdateReward))
	mux.HandleFunc("DELETE /api/rewards/{id}", s.choreAuth(s.handleDeleteReward))
	mux.HandleFunc("POST /api/rewards/{id}/delete", s.choreAuth(s.handleDeleteReward))
	mux.HandleFunc("POST /api/rewards/{id}/ask", s.choreAuth(s.handleAskReward))
	mux.HandleFunc("POST /api/rewards/asks/{id}/approve", s.choreAuth(s.handleAnswerAsk(true)))
	mux.HandleFunc("POST /api/rewards/asks/{id}/decline", s.choreAuth(s.handleAnswerAsk(false)))
	mux.HandleFunc("GET /api/feed", s.choreAuth(s.handleFeed))
	// Steward: AI agents' keys and what they can use.
	mux.HandleFunc("GET /api/v1/people", s.device(s.handlePeople))
	mux.HandleFunc("GET /api/v1/openapi.json", s.handleOpenAPI)
	mux.HandleFunc("GET /api/admin/keys", s.admin(s.handleAdminKeys))
	mux.HandleFunc("POST /api/admin/keys", s.admin(s.handleAdminCreateKey))
	mux.HandleFunc("DELETE /api/admin/keys/{id}", s.admin(s.handleAdminRevokeKey))
	mux.HandleFunc("GET /api/admin/vault", s.admin(s.handleAdminVault))
	mux.HandleFunc("PUT /api/family/pin", s.device(s.handleFamilyPIN))
	mux.HandleFunc("GET /api/admin/pin", s.admin(s.handleAdminPIN))
	mux.HandleFunc("PUT /api/admin/pin", s.admin(s.handleAdminPIN))
	mux.HandleFunc("GET /api/members/{id}/kid", s.device(s.handleKidSettings))
	mux.HandleFunc("PUT /api/members/{id}/kid", s.device(s.handleKidSettings))
	mux.HandleFunc("POST /api/members/{id}/kid", s.device(s.handleKidSettings)) // for clients that can't send PUT
	mux.HandleFunc("POST /api/members/{id}/unlock", s.device(s.handleUnlock))
	mux.HandleFunc("POST /api/admin/members/{id}/unlock", s.admin(s.handleAdminUnlock))
	mux.HandleFunc("GET /api/admin/members/{id}/kid", s.admin(s.kidSettingsHTTP))
	mux.HandleFunc("PUT /api/admin/members/{id}/kid", s.admin(s.kidSettingsHTTP))
	mux.HandleFunc("PATCH /api/admin/members/{id}", s.admin(s.handleAdminPatchMember))
	mux.HandleFunc("DELETE /api/admin/members/{id}", s.admin(s.handleAdminDeleteMember))
	mux.HandleFunc("POST /api/admin/members/{id}/reset", s.admin(s.handleAdminResetSignIn))
	mux.HandleFunc("DELETE /api/admin/members/{id}/devices/{dev}", s.admin(s.handleAdminSignOutDevice))
	mux.HandleFunc("POST /api/admin/members/{id}/devices/{dev}/delete", s.admin(s.handleAdminSignOutDevice))
	mux.HandleFunc("GET /api/admin/members/{id}/trail", s.admin(s.handleAdminTrail))
	mux.HandleFunc("GET /api/admin/members/{id}/trips", s.admin(s.handleAdminTrips))
	mux.HandleFunc("GET /api/admin/geocode", s.admin(s.handleAdminGeocode))
	mux.HandleFunc("GET /api/admin/places", s.admin(s.handleAdminPlaces))
	mux.HandleFunc("POST /api/admin/places", s.admin(s.handleAdminCreatePlace))
	mux.HandleFunc("PATCH /api/admin/places/{id}", s.admin(s.handleAdminUpdatePlace))
	mux.HandleFunc("DELETE /api/admin/places/{id}", s.admin(s.handleAdminDeletePlace))
	mux.HandleFunc("GET /api/admin/circles", s.admin(s.handleAdminCircles))
	mux.HandleFunc("POST /api/admin/circles", s.admin(s.handleAdminCreateCircle))
	mux.HandleFunc("PATCH /api/admin/circles/{id}", s.admin(s.handleAdminRenameCircle))
	mux.HandleFunc("DELETE /api/admin/circles/{id}", s.admin(s.handleAdminDeleteCircle))
	mux.HandleFunc("PUT /api/admin/circles/{id}/members/{mid}", s.admin(s.handleAdminMembership))
	mux.HandleFunc("DELETE /api/admin/circles/{id}/members/{mid}", s.admin(s.handleAdminMembership))
	mux.HandleFunc("GET /api/admin/invites", s.admin(s.handleListInvites))
	mux.HandleFunc("POST /api/admin/invites", s.admin(s.handleCreateInvite))
	mux.HandleFunc("DELETE /api/admin/invites/{code}", s.admin(s.handleDeleteInvite))

	// Invite landing page, opened on the phone from a shared link.
	mux.HandleFunc("GET /join/{code}", s.handleJoinPage)

	mux.Handle("GET /", http.FileServerFS(s.static))

	// CalDAV (PROPFIND, REPORT…): phones and computers sync the family calendar.
	return securityHeaders(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, davPrefix+"/") || r.URL.Path == davPrefix || r.URL.Path == "/.well-known/caldav" {
			s.handleDAV(w, r)
			return
		}
		mux.ServeHTTP(w, r)
	}))
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		// Other sites only ever see our origin (never paths like /join/<code>). Sending at
		// least the origin is required by the OpenStreetMap tile usage policy; with
		// no-referrer the dashboard's map tiles get blocked with a 403.
		h.Set("Referrer-Policy", "strict-origin-when-cross-origin")
		h.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			h.Set("Cache-Control", "no-store")
		}
		next.ServeHTTP(w, r)
	})
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON body")
		return false
	}
	return true
}

func cleanName(n string) (string, bool) {
	n = strings.Join(strings.Fields(n), " ")
	if n == "" || utf8.RuneCountInString(n) > 40 {
		return "", false
	}
	return n, true
}

var colorRe = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// publicMember is what other family members (and the dashboard) see.
type publicMember struct {
	ID     string    `json:"id"`
	Name   string    `json:"name"`
	Color  string    `json:"color"`
	Emoji  string    `json:"emoji,omitempty"`
	Voice  string    `json:"voice,omitempty"` // read aloud in: "female", "male" or "" (default)
	Stars  int       `json:"stars,omitempty"` // earned for chores
	Role   string    `json:"role"`            // "parent", "child" or "dependent"
	Access string    `json:"access"`          // admin, parent, older_child, younger_child, dependent
	Paused bool      `json:"paused"`
	Loc    *Location `json:"loc"`
	// When this person's active SOS started (unix ms); absent if no SOS.
	SOSSince int64 `json:"sosSince,omitempty"`
	// Who has seen it (names).
	SOSSeenBy []string `json:"sosSeenBy,omitempty"`
	// A phone number for Call buttons, and who they're driving to ("On my way").
	Phone  string `json:"phone,omitempty"`
	RideTo string `json:"rideTo,omitempty"`
	// Circles: for phones, the ones in common with the viewer; for the dashboard, all of theirs.
	Circles []string `json:"circles"`
	// The place they're at (e.g. "Home"), if any.
	Place string `json:"place,omitempty"`
	// Whether they keep a trail you can look at, and trips.
	Trail bool `json:"trail,omitempty"`
	Trips bool `json:"trips,omitempty"`
}

func toPublic(ms []Member) []publicMember {
	out := make([]publicMember, len(ms))
	for i, m := range ms {
		out[i] = publicMember{ID: m.ID, Name: m.Name, Color: m.Color, Emoji: m.Emoji, Voice: m.Voice, Stars: m.Stars, Role: m.role(), Access: m.access(), Paused: m.Paused, Loc: m.Loc, SOSSince: m.SOSSince, Circles: m.Circles, Phone: m.Phone, RideTo: activeRide(&m)}
		if out[i].Circles == nil {
			out[i].Circles = []string{}
		}
	}
	return out
}

// serverFeatures tells apps what this server can do, so a newer app talking to
// an older server can hide what won't work instead of failing.
var serverFeatures = []string{"accounts", "instant-alerts", "sos", "checkin", "circles", "places", "trails",
	"emoji", "status", "schedules", "trips", "driving", "sos-seen", "requests", "nudges", "reminders", "index01",
	"modules", "roles", "kids", "chat-moderation", "access-levels", "grandparents", "unlock", "nudges2", "rides", "checkplans", "phones"}

func (s *Server) serverInfo() map[string]any {
	features := slices.Clone(serverFeatures)
	if s.geo != nil && s.geo.enabled() {
		features = append(features, "geocode")
	}
	if s.cfg.SilenceAfter > 0 {
		features = append(features, "silence")
	}
	features = append(features, s.store.moduleFeatures()...)
	features = append(features, s.voice.features()...)
	if s.stewardReady() {
		features = append(features, "steward")
		if s.store.Steward().Everyone {
			features = append(features, "steward-kids")
		}
	}
	return map[string]any{
		"name":            s.cfg.FamilyName,
		"version":         version,
		"tileUrl":         s.cfg.TileURL,
		"tileUrlDark":     s.cfg.TileURLDark,
		"tileAttribution": s.cfg.TileAttribution,
		"features":        features,
	}
}

// baseURL is how phones reach this server; used to build invite links.
func (s *Server) baseURL(r *http.Request) string {
	if s.cfg.PublicURL != "" {
		return s.cfg.PublicURL
	}
	scheme := "http"
	if r.TLS != nil || strings.EqualFold(r.Header.Get("X-Forwarded-Proto"), "https") {
		scheme = "https"
	}
	host := r.Host
	if fh := r.Header.Get("X-Forwarded-Host"); fh != "" {
		host = fh
	}
	return scheme + "://" + host
}

// ---- device (phone) auth ----

type deviceHandler func(w http.ResponseWriter, r *http.Request, me *Member)

func (s *Server) device(h deviceHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			// A parent logged in to the web console uses the same API as their phone.
			if sess := s.session(r); sess.Member != nil {
				h(w, r, sess.Member)
				return
			}
			writeError(w, http.StatusUnauthorized, "missing token")
			return
		}
		// An AI agent's API key (Steward), limited to its scopes.
		if s.agentRequest(w, r, strings.TrimSpace(token), h) {
			return
		}
		me, devID, signedOut := s.store.lookupDevice(strings.TrimSpace(token))
		if me == nil {
			// 401 tells the app this phone is no longer signed in, and why.
			if signedOut {
				writeJSON(w, http.StatusUnauthorized, map[string]string{
					"error": "you signed in on another device", "reason": "signed_in_elsewhere"})
			} else {
				writeJSON(w, http.StatusUnauthorized, map[string]string{
					"error": "unknown device; it may have been removed", "reason": "removed"})
			}
			return
		}
		s.store.Touch(me.ID, devID)
		h(w, r.WithContext(context.WithValue(r.Context(), deviceKey{}, devID)), me)
	}
}

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.serverInfo())
}

// capitalize makes "use at least..." into "Use at least..." for messages shown to people.
func capitalize(msg string) string {
	if msg == "" {
		return msg
	}
	return strings.ToUpper(msg[:1]) + msg[1:]
}

// signedInResponse is what join, sign-in and password resets return to the phone.
func (s *Server) signedInResponse(w http.ResponseWriter, m *Member, token string) {
	writeJSON(w, http.StatusOK, map[string]any{
		"token": token, "member": toPublic([]Member{*m})[0], "server": s.serverInfo(),
		"username": m.Username,
	})
}

// handleInviteInfo lets the app see what a code is for before asking for details:
// joining as someone new, or resetting an existing member's sign-in.
func (s *Server) handleInviteInfo(w http.ResponseWriter, r *http.Request) {
	inv, m, ok := s.store.InviteInfo(r.PathValue("code"))
	if !ok {
		time.Sleep(500 * time.Millisecond) // slow down code guessing
		writeError(w, http.StatusNotFound, "that invite code is not valid or has expired")
		return
	}
	access := inv.Access
	if access == "" {
		access = AccessParent
	}
	// passwordless: a younger child's code; the app asks for no username or password.
	resp := map[string]any{"kind": "join", "name": inv.Name, "server": s.serverInfo(), "circle": s.circleName(inv.CircleID),
		"access": access, "passwordless": access == AccessYoungerChild}
	if m != nil {
		resp["kind"] = "reset"
		resp["name"] = m.Name
		resp["username"] = m.Username
		resp["access"] = m.access()
		resp["passwordless"] = !m.canUsePassword()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleJoin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code     string `json:"code"`
		Name     string `json:"name"`
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	inv, resetFor, ok := s.store.InviteInfo(req.Code)
	if !ok {
		time.Sleep(500 * time.Millisecond) // slow down code guessing
		writeError(w, http.StatusNotFound, "that invite code is not valid or has expired")
		return
	}

	// A younger child's code: sign this device in, no password.
	if resetFor != nil && !resetFor.canUsePassword() {
		m, token, err := s.store.RedeemDeviceCode(req.Code)
		s.writeAccountResult(w, m, token, err)
		return
	}
	// A reset link: keep who they are, set a new password, sign this phone in.
	if resetFor != nil {
		username := resetFor.Username
		if username == "" {
			var err error
			if username, err = NormalizeUsername(req.Username); err != nil {
				writeError(w, http.StatusBadRequest, capitalize(err.Error()))
				return
			}
		}
		if err := CheckPassword(req.Password, username, resetFor.Name); err != nil {
			writeError(w, http.StatusBadRequest, capitalize(err.Error()))
			return
		}
		hash, err := HashPassword(req.Password)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
		m, token, err := s.store.RedeemReset(req.Code, username, hash)
		s.writeAccountResult(w, m, token, err)
		return
	}

	name := ""
	if strings.TrimSpace(req.Name) != "" {
		var ok bool
		if name, ok = cleanName(req.Name); !ok {
			writeError(w, http.StatusBadRequest, "name must be 1-40 characters")
			return
		}
	}
	// Apps from before accounts existed send neither username nor password, and
	// younger children don't get them.
	username, hash := "", ""
	if (req.Username != "" || req.Password != "") && inv.Access != AccessYoungerChild {
		var err error
		if username, err = NormalizeUsername(req.Username); err != nil {
			writeError(w, http.StatusBadRequest, capitalize(err.Error()))
			return
		}
		if _, taken := s.store.MemberByUsername(username); taken {
			writeError(w, http.StatusConflict, capitalize(ErrUsernameTaken.Error()))
			return
		}
		shownName := name
		if shownName == "" {
			shownName = inv.Name
		}
		if err := CheckPassword(req.Password, username, shownName); err != nil {
			writeError(w, http.StatusBadRequest, capitalize(err.Error()))
			return
		}
		if hash, err = HashPassword(req.Password); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	m, token, err := s.store.Join(req.Code, name, username, hash)
	if err == nil && m.Name == "" {
		// Neither the invite nor the phone gave a name.
		n := "Member"
		m, _ = s.store.UpdateMember(m.ID, MemberPatch{Name: &n})
	}
	s.writeAccountResult(w, m, token, err)
}

func (s *Server) writeAccountResult(w http.ResponseWriter, m *Member, token string, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		writeError(w, http.StatusNotFound, "that invite code is not valid or has expired")
	case errors.Is(err, ErrUsernameTaken):
		writeError(w, http.StatusConflict, capitalize(err.Error()))
	case err != nil:
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
	default:
		s.signedInResponse(w, m, token)
	}
}

var wrongPasswordDelay = time.Second // shortened in tests

// wrongPassword records a failed attempt and waits a moment. Failures are
// handled one at a time, so guessing is limited to about one try per second.
func (s *Server) wrongPassword(username string) {
	s.limiter.Fail(username)
	s.loginMu.Lock()
	time.Sleep(wrongPasswordDelay)
	s.loginMu.Unlock()
}

// handleSignIn signs a phone in with a username and password. The latest sign-in
// wins: the phone that was signed in before is signed out.
func (s *Server) handleSignIn(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	username := strings.ToLower(strings.TrimSpace(req.Username))
	if d := s.limiter.Locked(username); d > 0 {
		writeError(w, http.StatusTooManyRequests, "Too many wrong passwords. Try again in "+minutesText(d)+", or ask your family's admin to reset your sign-in.")
		return
	}
	m, found := s.store.MemberByUsername(username)
	hash := ""
	if found {
		hash = m.PasswordHash
	}
	// Always check a hash (a dummy one for unknown usernames) so timing gives nothing away.
	if !VerifyPassword(req.Password, hash) || !found || hash == "" {
		s.wrongPassword(username)
		writeError(w, http.StatusUnauthorized, "Wrong username or password")
		return
	}
	s.limiter.Succeed(username)
	if !m.canUsePassword() {
		writeError(w, http.StatusForbidden, "Signing in with a password is switched off for you. Ask a parent for a new join code.")
		return
	}
	m, token, err := s.store.SignIn(m.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	s.signedInResponse(w, m, token)
}

// handleSetPassword lets a signed-in phone set a password (members from before
// accounts existed, who also choose a username) or change it (needs the current one).
func (s *Server) handleSetPassword(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		CurrentPassword string `json:"currentPassword"`
		NewPassword     string `json:"newPassword"`
		Username        string `json:"username"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !me.canUsePassword() {
		writeError(w, http.StatusForbidden, "A parent can let you use a password (in the web console's Family page).")
		return
	}
	if me.PasswordHash != "" {
		if d := s.limiter.Locked(me.Username); d > 0 {
			writeError(w, http.StatusTooManyRequests, "Too many wrong passwords. Try again in "+minutesText(d)+".")
			return
		}
		if !VerifyPassword(req.CurrentPassword, me.PasswordHash) {
			s.wrongPassword(me.Username)
			writeError(w, http.StatusForbidden, "Your current password is wrong")
			return
		}
		s.limiter.Succeed(me.Username)
	}
	username, newUsername := me.Username, ""
	if username == "" {
		var err error
		if username, err = NormalizeUsername(req.Username); err != nil {
			writeError(w, http.StatusBadRequest, capitalize(err.Error()))
			return
		}
		newUsername = username
	}
	if err := CheckPassword(req.NewPassword, username, me.Name); err != nil {
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
		return
	}
	hash, err := HashPassword(req.NewPassword)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	m, err := s.store.SetCredentials(me.ID, newUsername, hash)
	switch {
	case errors.Is(err, ErrUsernameTaken):
		writeError(w, http.StatusConflict, capitalize(err.Error()))
	case err != nil:
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
	default:
		writeJSON(w, http.StatusOK, map[string]any{"username": m.Username, "hasPassword": true})
	}
}

// sinceParam reads the ?since= event cursor; ok is false when the client didn't send one
// (older apps), in which case no events are included in the reply.
func sinceParam(r *http.Request) (int64, bool) {
	v := r.URL.Query().Get("since")
	if v == "" {
		return 0, false
	}
	n, err := strconv.ParseInt(v, 10, 64)
	return n, err == nil
}

func (s *Server) handleMembers(w http.ResponseWriter, r *http.Request, me *Member) {
	views, circles := s.store.VisibleTo(me.ID)
	members := make([]publicMember, len(views))
	for i, v := range views {
		m := v.Member
		m.Circles = v.SharedCircles
		members[i] = toPublic([]Member{m})[0]
		members[i].Place = v.Place
		members[i].SOSSeenBy = v.SOSSeenNames
		members[i].Trail = v.TrailHours > 0 && v.Loc != nil
		members[i].Trips = v.TripDays > 0
	}
	myCircles := make([]map[string]any, len(circles))
	for i, c := range circles {
		myCircles[i] = map[string]any{"id": c.ID, "name": c.Name, "sharing": !slices.Contains(me.HiddenFrom, c.ID)}
	}
	resp := map[string]any{
		"me":      me.ID,
		"members": members,
		"circles": myCircles,
		"places":  s.store.PlacesFor(me.ID),
		"server":  s.serverInfo(),
		"settings": map[string]any{
			"shareLowBattery": me.SharesLowBattery(),
			"shareArrivals":   me.SharesArrivals(),
			"trailHours":      me.TrailHours,
			"tripDays":        me.TripDays,
			"username":        me.Username,
			"hasPassword":     me.PasswordHash != "",
			"access":          me.access(),
			"kid":             me.kidSettings(), // what this person may use (all on for adults)
			"home":            me.Home,
			"pinSet":          s.store.ParentPINHash() != "",
			"big":             me.big(),
			"pictures":        me.pictures(),
		},
	}
	if since, ok := sinceParam(r); ok {
		resp["events"], resp["cursor"] = s.store.EventsFor(me.ID, since)
	}
	writeJSON(w, http.StatusOK, resp)
}

// cleanLocation checks a location from a phone and tidies odd values.
// It returns false if the coordinates are impossible.
func cleanLocation(l *Location) bool {
	if math.IsNaN(l.Lat) || math.IsNaN(l.Lon) || l.Lat < -90 || l.Lat > 90 || l.Lon < -180 || l.Lon > 180 {
		return false
	}
	now := time.Now().UnixMilli()
	// Phones with a wrong clock shouldn't make a fix look like it's from the future.
	if l.Time <= 0 || l.Time > now+60_000 {
		l.Time = now
	}
	if l.Battery < -1 || l.Battery > 100 {
		l.Battery = -1
	}
	if l.Accuracy < 0 || math.IsNaN(l.Accuracy) {
		l.Accuracy = 0
	}
	if l.Speed < 0 || math.IsNaN(l.Speed) {
		l.Speed = 0
	}
	if l.Heading < 0 || l.Heading > 360 {
		l.Heading = 0
	}
	l.Received = now
	return true
}

// optionalLocation reads {"loc": {...}} from an SOS or check-in; nil if absent or invalid.
func optionalLocation(l *Location) *Location {
	if l == nil || (l.Lat == 0 && l.Lon == 0) || !cleanLocation(l) {
		return nil
	}
	return l
}

func (s *Server) handleSOS(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		Loc       *Location `json:"loc"`
		Source    string    `json:"source"`    // "watch" or "fall" when raised from a Pebble
		HeartRate int       `json:"heartRate"` // from the watch, if the person allows it
		Note      string    `json:"note"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Source != "watch" && req.Source != "fall" {
		req.Source = ""
	}
	m, err := s.store.StartSOSWith(me.ID, optionalLocation(req.Loc), SOSDetails{Note: req.Note, Source: req.Source, HeartRate: req.HeartRate})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}

func (s *Server) handleSOSEnd(w http.ResponseWriter, r *http.Request, me *Member) {
	m, err := s.store.EndSOS(me.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}

func (s *Server) handleCheckIn(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		Note string    `json:"note"`
		Loc  *Location `json:"loc"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if err := s.store.CheckIn(me.ID, cleanNote(req.Note), optionalLocation(req.Loc)); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

const (
	defaultWait = 50 * time.Second // under the 60s idle timeout of common reverse proxies
	maxWait     = 5 * time.Minute
)

// handleWaitEvents answers as soon as there's a new alert for this phone, or
// after ?wait= seconds (default 50) with nothing. Phones keep one of these open
// in the background so alerts arrive within seconds without a push service.
func (s *Server) handleWaitEvents(w http.ResponseWriter, r *http.Request, me *Member) {
	since, ok := sinceParam(r)
	if !ok {
		writeError(w, http.StatusBadRequest, "since is required")
		return
	}
	wait := defaultWait
	if v, err := strconv.Atoi(r.URL.Query().Get("wait")); err == nil && v >= 0 {
		wait = min(time.Duration(v)*time.Second, maxWait)
	}
	// The server's normal write timeout is shorter than a long wait.
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(wait + 15*time.Second))
	ctx, cancel := context.WithTimeout(r.Context(), wait)
	defer cancel()
	events, cursor := s.store.WaitForEvents(ctx, me.ID, since)
	writeJSON(w, http.StatusOK, map[string]any{"events": events, "cursor": cursor})
}

func (s *Server) handleLocation(w http.ResponseWriter, r *http.Request, me *Member) {
	var l Location
	if !readJSON(w, r, &l) {
		return
	}
	if !cleanLocation(&l) {
		writeError(w, http.StatusBadRequest, "lat/lon out of range")
		return
	}
	// Several devices: a tablet's fix is ignored while the person's phone is
	// reporting (the phone goes where they go; the tablet usually stays home).
	if s.store.deferToPhone(me.ID, deviceOf(r)) {
		if since, ok := sinceParam(r); ok {
			events, cursor := s.store.EventsFor(me.ID, since)
			writeJSON(w, http.StatusOK, map[string]any{"events": events, "cursor": cursor})
		} else {
			w.WriteHeader(http.StatusNoContent)
		}
		return
	}
	// While paused, a fix that was still in flight is dropped (the store ignores it).
	// The app un-pauses explicitly with PATCH /api/me.
	if err := s.store.UpdateLocation(me.ID, l); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	// The reply carries any new alerts, so phones get them without a push service.
	if since, ok := sinceParam(r); ok {
		events, cursor := s.store.EventsFor(me.ID, since)
		writeJSON(w, http.StatusOK, map[string]any{"events": events, "cursor": cursor})
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handlePatchMe(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		Name            *string  `json:"name"`
		Paused          *bool    `json:"paused"`
		Color           *string  `json:"color"`
		Emoji           *string  `json:"emoji"`
		Voice           *string  `json:"voice"`
		Phone           *string  `json:"phone"`
		ShareLowBattery *bool    `json:"shareLowBattery"`
		ShareArrivals   *bool    `json:"shareArrivals"`
		TrailHours      *int     `json:"trailHours"`
		TripDays        *int     `json:"tripDays"`
		Home            []string `json:"home"`
		Big             *bool    `json:"big"`
		Pictures        *bool    `json:"pictures"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Big != nil || req.Pictures != nil {
		if me.access() == AccessYoungerChild {
			writeError(w, http.StatusForbidden, "a parent sets this for you")
			return
		}
		if err := s.store.SetAssist(me.ID, req.Big, req.Pictures); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	if req.Paused != nil && *req.Paused && !me.can("pause") {
		writeError(w, http.StatusForbidden, "a parent has to switch on pausing for you")
		return
	}
	if req.Home != nil {
		home, ok := cleanHome(req.Home)
		if !ok {
			writeError(w, http.StatusBadRequest, "unknown home tile, or too many")
			return
		}
		if me.role() == RoleChild {
			writeError(w, http.StatusForbidden, "a parent arranges your home screen")
			return
		}
		if err := s.store.SetHome(me.ID, home); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	if !s.validatePatch(w, req.Name, req.Color, req.Emoji) {
		return
	}
	if req.TrailHours != nil {
		if !validTrailHours(*req.TrailHours) {
			writeError(w, http.StatusBadRequest, "trailHours must be 0, 6, 12 or 24")
			return
		}
		if err := s.store.SetTrailHours(me.ID, *req.TrailHours); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	if req.TripDays != nil {
		if !validTripDays(*req.TripDays) {
			writeError(w, http.StatusBadRequest, "tripDays must be 0, 1, 3 or 7")
			return
		}
		if err := s.store.SetTripDays(me.ID, *req.TripDays); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	if req.Voice != nil && !validVoice(*req.Voice) {
		writeError(w, http.StatusBadRequest, `voice must be "female", "male" or ""`)
		return
	}
	if req.Phone != nil && !validPhone(strings.TrimSpace(*req.Phone)) {
		writeError(w, http.StatusBadRequest, "a phone number, like +44 7700 900123")
		return
	}
	m, err := s.store.UpdateMember(me.ID, MemberPatch{Name: req.Name, Paused: req.Paused, Color: req.Color, Emoji: req.Emoji, Voice: req.Voice, Phone: req.Phone, ShareLowBattery: req.ShareLowBattery, ShareArrivals: req.ShareArrivals})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}

func (s *Server) validatePatch(w http.ResponseWriter, name, color, emoji *string) bool {
	if emoji != nil {
		e, ok := cleanEmoji(*emoji)
		if !ok {
			writeError(w, http.StatusBadRequest, "Pick a single emoji (or leave it empty to show the initial)")
			return false
		}
		*emoji = e
	}
	if name != nil {
		n, ok := cleanName(*name)
		if !ok {
			writeError(w, http.StatusBadRequest, "name must be 1-40 characters")
			return false
		}
		*name = n
	}
	if color != nil && !colorRe.MatchString(*color) {
		writeError(w, http.StatusBadRequest, "color must look like #rrggbb")
		return false
	}
	return true
}

func (s *Server) handleSOSSeen(w http.ResponseWriter, r *http.Request, me *Member) {
	switch err := s.store.SOSSeen(me.ID, r.PathValue("id")); {
	case errors.Is(err, ErrNoSOS):
		writeError(w, http.StatusConflict, capitalize(err.Error()))
	case err != nil:
		writeError(w, http.StatusNotFound, "no such person")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) handleRequest(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		To   string    `json:"to"`
		Kind string    `json:"kind"`
		Loc  *Location `json:"loc"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	switch err := s.store.Request(me.ID, req.To, req.Kind, optionalLocation(req.Loc)); {
	case errors.Is(err, ErrBadRequest):
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
	case errors.Is(err, ErrNotAllowed):
		writeError(w, http.StatusForbidden, "a parent has switched that off for you")
	case err != nil:
		writeError(w, http.StatusNotFound, "no such person")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s *Server) handleListReminders(w http.ResponseWriter, r *http.Request, me *Member) {
	writeJSON(w, http.StatusOK, map[string]any{"reminders": s.store.RemindersFor(me.ID)})
}

func (s *Server) handleAddReminder(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		PlaceID string `json:"placeId"`
		Text    string `json:"text"`
		OnLeave bool   `json:"onLeave"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	rem, err := s.store.AddReminder(me.ID, req.PlaceID, req.Text, req.OnLeave)
	if err != nil {
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
		return
	}
	writeJSON(w, http.StatusOK, rem)
}

func (s *Server) handleDeleteReminder(w http.ResponseWriter, r *http.Request, me *Member) {
	if err := s.store.DeleteReminder(me.ID, r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such reminder")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleIndexSettings returns the person's Index 01 ring webhook URLs (POST .../reset makes new ones).
func (s *Server) handleIndexSettings(w http.ResponseWriter, r *http.Request, me *Member) {
	token, secret, err := s.store.IndexSettings(me.ID, r.Method == http.MethodPost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	base := s.baseURL(r) + "/api/index/" + token + "/"
	writeJSON(w, http.StatusOK, map[string]string{
		"sos": base + "sos", "checkin": base + "checkin", "remind": base + "remind", "signingSecret": secret,
	})
}

// deviceKey: the request's device id, in its context.
type deviceKey struct{}

func deviceOf(r *http.Request) string { id, _ := r.Context().Value(deviceKey{}).(string); return id }

// handleStatus: the phone reports its app version and anything that could stop it sharing.
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		App    string   `json:"app"`
		Device string   `json:"device"` // phone, tablet or display
		Issues []string `json:"issues"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	s.store.SetDeviceStatus(me.ID, deviceOf(r), req.Device, req.App)
	if err := s.store.SetStatus(me.ID, req.App, req.Device, req.Issues); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleLeave(w http.ResponseWriter, r *http.Request, me *Member) {
	_ = s.store.DeleteMember(me.ID)
	if s.chat != nil {
		_ = s.chat.ForgetMember(me.ID)
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- web console auth ----
//
// The console cookie says who is logged in: the main admin (with the admin
// password from the server's settings), or one person with their own username
// and password. Admins (the main admin, and anyone they made an admin) get every
// page; parents get the parent tools: everything the app does, in the browser.
// Children and non-users can't log in to the console.

// Session is who's using the console.
type Session struct {
	Owner  bool    // logged in with the admin password
	Member *Member // logged in with their own account
}

func (c Session) Admin() bool {
	return c.Owner || (c.Member != nil && c.Member.Admin && c.Member.role() == RoleParent)
}
func (c Session) Valid() bool { return c.Owner || c.Member != nil }

func (s *Server) sign(payload string) string {
	mac := hmac.New(sha256.New, []byte(s.store.Secret()))
	mac.Write([]byte("admin|" + payload))
	return hex.EncodeToString(mac.Sum(nil))
}

// session reads the console cookie: "exp.sig" (main admin, the original format)
// or "exp.m-<memberID>.sig" (a person).
func (s *Server) session(r *http.Request) Session {
	c, err := r.Cookie(adminCookie)
	if err != nil {
		return Session{}
	}
	parts := strings.Split(c.Value, ".")
	exp, who, sig := parts[0], "", parts[len(parts)-1]
	switch len(parts) {
	case 2:
	case 3:
		who = parts[1]
	default:
		return Session{}
	}
	e, err := strconv.ParseInt(exp, 10, 64)
	if err != nil || time.Now().Unix() > e {
		return Session{}
	}
	payload := exp
	if who != "" {
		payload += "|" + who
	}
	if !hmac.Equal([]byte(sig), []byte(s.sign(payload))) {
		return Session{}
	}
	if who == "" {
		return Session{Owner: true}
	}
	id, ok := strings.CutPrefix(who, "m-")
	if !ok {
		return Session{}
	}
	m, found := s.store.MemberByID(id)
	if !found || m.role() != RoleParent || m.PasswordHash == "" {
		return Session{} // removed, or no longer a parent
	}
	return Session{Member: m}
}

func (s *Server) isAdmin(r *http.Request) bool { return s.session(r).Admin() }

// admin guards the console's admin pages.
func (s *Server) admin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		sess := s.session(r)
		if !sess.Valid() {
			writeError(w, http.StatusUnauthorized, "please log in")
			return
		}
		if !sess.Admin() {
			writeError(w, http.StatusForbidden, "this needs an admin")
			return
		}
		h(w, r)
	}
}

// consoleUser guards console pages any logged-in parent may see.
func (s *Server) consoleUser(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.session(r).Valid() {
			writeError(w, http.StatusUnauthorized, "please log in")
			return
		}
		h(w, r)
	}
}

func (s *Server) setSession(w http.ResponseWriter, r *http.Request, who string) {
	exp := strconv.FormatInt(time.Now().Add(adminSessionTTL).Unix(), 10)
	value, payload := exp, exp
	if who != "" {
		value += "." + who
		payload += "|" + who
	}
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    value + "." + s.sign(payload),
		Path:     "/",
		MaxAge:   int(adminSessionTTL.Seconds()),
		HttpOnly: true,
		Secure:   strings.HasPrefix(s.baseURL(r), "https://"),
		SameSite: http.SameSiteStrictMode,
	})
}

func (s *Server) slowDown() {
	s.loginMu.Lock()
	time.Sleep(time.Second)
	s.loginMu.Unlock()
}

// handleLogin: the admin password alone (main admin), or a parent's username and password.
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.Username) == "" {
		a := sha256.Sum256([]byte(req.Password))
		b := sha256.Sum256([]byte(s.cfg.AdminPassword))
		if subtle.ConstantTimeCompare(a[:], b[:]) != 1 {
			s.slowDown()
			writeError(w, http.StatusUnauthorized, "wrong password")
			return
		}
		s.setSession(w, r, "")
		w.WriteHeader(http.StatusNoContent)
		return
	}
	username, _ := NormalizeUsername(req.Username)
	if d := s.limiter.Locked(username); d > 0 {
		writeError(w, http.StatusTooManyRequests, "Too many wrong passwords. Try again in "+minutesText(d)+".")
		return
	}
	m, found := s.store.MemberByUsername(username)
	hash := dummyHash
	if found && m.PasswordHash != "" {
		hash = m.PasswordHash
	}
	if !VerifyPassword(req.Password, hash) || !found || m.PasswordHash == "" {
		s.wrongPassword(username)
		writeError(w, http.StatusUnauthorized, "wrong username or password")
		return
	}
	s.limiter.Succeed(username)
	if m.role() != RoleParent {
		writeError(w, http.StatusForbidden, "the web console is for parents")
		return
	}
	s.setSession(w, r, "m-"+m.ID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: adminCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true})
	w.WriteHeader(http.StatusNoContent)
}

// handleSession tells the console who's logged in and what they may do.
func (s *Server) handleSession(w http.ResponseWriter, r *http.Request) {
	sess := s.session(r)
	if !sess.Valid() {
		writeError(w, http.StatusUnauthorized, "please log in")
		return
	}
	out := map[string]any{"owner": sess.Owner, "admin": sess.Admin(), "server": s.serverInfo()}
	if sess.Member != nil {
		out["member"] = toPublic([]Member{*sess.Member})[0]
	}
	writeJSON(w, http.StatusOK, out)
}

// adminMember adds sign-in details that only the dashboard sees.
type adminMember struct {
	publicMember
	Username    string   `json:"username"`
	HasPassword bool     `json:"hasPassword"`
	LastSignIn  int64    `json:"lastSignIn,omitempty"`
	HiddenFrom  []string `json:"hiddenFrom"`
	// Phone health
	App         string   `json:"app,omitempty"`
	Device      string   `json:"device,omitempty"`
	Devices     []Device `json:"devices"`
	Issues      []string `json:"issues"`
	StatusAt    int64    `json:"statusAt,omitempty"`
	LastContact int64    `json:"lastContact,omitempty"`
	Silent      bool     `json:"silent,omitempty"`
}

func (s *Server) handleAdminMembers(w http.ResponseWriter, r *http.Request) {
	ms := s.store.Members()
	pub := toPublic(ms)
	at := s.store.AdminPlaceNames()
	for i := range pub {
		pub[i].Place = at[pub[i].ID]
		pub[i].Trail = ms[i].TrailHours > 0 && !ms[i].Paused
		pub[i].Trips = ms[i].TripDays > 0
	}
	out := make([]adminMember, len(ms))
	for i, m := range ms {
		out[i] = adminMember{pub[i], m.Username, m.PasswordHash != "", m.LastSignIn, append([]string{}, m.HiddenFrom...),
			m.App, m.Device, publicDevices(m.Devices), append([]string{}, m.Issues...), m.StatusAt, m.LastContact, m.SilentSince != 0}
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": out, "circles": s.store.Circles(), "places": s.store.AllPlaces(), "server": s.serverInfo()})
}

// handleAdminBackup downloads the whole data file. It holds password and token
// hashes, so keep it as safe as the server itself.
func (s *Server) handleAdminBackup(w http.ResponseWriter, r *http.Request) {
	b, err := s.store.Snapshot()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not make a backup")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Content-Disposition", `attachment; filename="famlogic-backup-`+time.Now().Format("2006-01-02")+`.json"`)
	_, _ = w.Write(b)
}

// handleAdminResetSignIn makes a one-time link that lets a member who forgot
// their password choose a new one, keeping their place in the family.
func (s *Server) handleAdminResetSignIn(w http.ResponseWriter, r *http.Request) {
	inv, err := s.store.CreateResetInvite(r.PathValue("id"), s.cfg.InviteTTL)
	if errors.Is(err, ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such member")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, inviteResponse{*inv, s.inviteLink(r, inv.Code)})
}

func (s *Server) handleAdminPatchMember(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name  *string `json:"name"`
		Color *string `json:"color"`
		Emoji *string `json:"emoji"`
		Voice *string `json:"voice"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !s.validatePatch(w, req.Name, req.Color, req.Emoji) {
		return
	}
	if req.Voice != nil && !validVoice(*req.Voice) {
		writeError(w, http.StatusBadRequest, `voice must be "female", "male" or ""`)
		return
	}
	m, err := s.store.UpdateMember(r.PathValue("id"), MemberPatch{Name: req.Name, Color: req.Color, Emoji: req.Emoji, Voice: req.Voice})
	if errors.Is(err, ErrNotFound) {
		writeError(w, http.StatusNotFound, "no such member")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}

func (s *Server) handleAdminDeleteMember(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeleteMember(r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such member")
		return
	}
	if s.chat != nil {
		_ = s.chat.ForgetMember(r.PathValue("id"))
	}
	w.WriteHeader(http.StatusNoContent)
}

type inviteResponse struct {
	Invite
	Link string `json:"link"`
}

func (s *Server) inviteLink(r *http.Request, code string) string {
	return s.baseURL(r) + "/join/" + url.PathEscape(code)
}

func (s *Server) handleListInvites(w http.ResponseWriter, r *http.Request) {
	invs := s.store.Invites()
	out := make([]inviteResponse, len(invs))
	for i, inv := range invs {
		out[i] = inviteResponse{inv, s.inviteLink(r, inv.Code)}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleCreateInvite(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name     string `json:"name"`
		CircleID string `json:"circleId"`
		Access   string `json:"access"` // parent (default), older_child, younger_child
	}
	if !readJSON(w, r, &req) {
		return
	}
	access, ok := inviteAccess(req.Access)
	if !ok {
		writeError(w, http.StatusBadRequest, "invites are for parents or children")
		return
	}
	name := ""
	if strings.TrimSpace(req.Name) != "" {
		var ok bool
		if name, ok = cleanName(req.Name); !ok {
			writeError(w, http.StatusBadRequest, "name must be 1-40 characters")
			return
		}
	}
	inv, err := s.store.CreateInvite(name, req.CircleID, s.cfg.InviteTTL, access)
	if errors.Is(err, ErrNotFound) {
		writeError(w, http.StatusBadRequest, "no such group")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, inviteResponse{*inv, s.inviteLink(r, inv.Code)})
}

func (s *Server) handleDeleteInvite(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeleteInvite(r.PathValue("code")); err != nil {
		writeError(w, http.StatusNotFound, "no such invite")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- circles ----

// circleName looks up a circle's name ("" if it no longer exists).
func (s *Server) circleName(id string) string {
	for _, c := range s.store.Circles() {
		if c.ID == id {
			return c.Name
		}
	}
	return ""
}

// handleSetCircleSharing lets a person share or stop sharing their location with one circle.
func (s *Server) handleSetCircleSharing(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		Sharing bool `json:"sharing"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if err := s.store.SetSharingIn(me.ID, r.PathValue("id"), req.Sharing); err != nil {
		writeError(w, http.StatusNotFound, "you're not in that group")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleJoinCircle lets someone already signed in use an invite code to join another circle.
func (s *Server) handleJoinCircle(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		Code string `json:"code"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	c, err := s.store.JoinCircleWithCode(me.ID, req.Code)
	if err != nil {
		time.Sleep(500 * time.Millisecond) // slow down code guessing
		writeError(w, http.StatusNotFound, "that invite code is not valid or has expired")
		return
	}
	writeJSON(w, http.StatusOK, c)
}

func (s *Server) handleAdminCircles(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.store.Circles())
}

func cleanCircleName(n string) (string, bool) {
	n = strings.Join(strings.Fields(n), " ")
	return n, n != "" && utf8.RuneCountInString(n) <= 40
}

func (s *Server) handleAdminCreateCircle(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	name, ok := cleanCircleName(req.Name)
	if !ok {
		writeError(w, http.StatusBadRequest, "group names are 1-40 characters")
		return
	}
	c, err := s.store.CreateCircle(name)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, c)
}

func (s *Server) handleAdminRenameCircle(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	name, ok := cleanCircleName(req.Name)
	if !ok {
		writeError(w, http.StatusBadRequest, "group names are 1-40 characters")
		return
	}
	if err := s.store.RenameCircle(r.PathValue("id"), name); err != nil {
		writeError(w, http.StatusNotFound, "no such group")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAdminDeleteCircle(w http.ResponseWriter, r *http.Request) {
	switch err := s.store.DeleteCircle(r.PathValue("id")); {
	case errors.Is(err, ErrLastCircle):
		writeError(w, http.StatusConflict, "You can't delete the last group")
	case err != nil:
		writeError(w, http.StatusNotFound, "no such group")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// handleAdminMembership puts a member in a circle (PUT) or takes them out (DELETE).
func (s *Server) handleAdminMembership(w http.ResponseWriter, r *http.Request) {
	if err := s.store.SetMembership(r.PathValue("mid"), r.PathValue("id"), r.Method == http.MethodPut); err != nil {
		writeError(w, http.StatusNotFound, "no such member or group")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- trails ----

func (s *Server) handleTrail(w http.ResponseWriter, r *http.Request, me *Member) {
	points, ok := s.store.TrailFor(me.ID, r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no trail to show")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"points": points})
}

func (s *Server) handleAdminTrail(w http.ResponseWriter, r *http.Request) {
	points, ok := s.store.TrailFor("", r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such member")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"points": points})
}

// Trips: the trail split into journeys between stops, newest first (see trips.go).
func (s *Server) handleTrips(w http.ResponseWriter, r *http.Request, me *Member) {
	trips, ok := s.store.TripsFor(me.ID, r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no trips to show")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"trips": s.nameTripEnds(trips)})
}

// nameTripEnds adds the nearest street to trip ends that aren't in a fence, when
// the server's geocoder already knows it (unknown ones are looked up in the
// background, one per second, and show next time).
func (s *Server) nameTripEnds(trips []Trip) []Trip {
	for i := range trips {
		t := &trips[i]
		if t.From == "" {
			t.FromNear = s.geo.NearName(t.Points[0].Lat, t.Points[0].Lon)
		}
		if t.To == "" && !t.Ongoing {
			last := t.Points[len(t.Points)-1]
			t.ToNear = s.geo.NearName(last.Lat, last.Lon)
		}
	}
	return trips
}

func (s *Server) handleAdminTrips(w http.ResponseWriter, r *http.Request) {
	trips, ok := s.store.TripsFor("", r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such member")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"trips": s.nameTripEnds(trips)})
}

// ---- places ----

type placeRequest struct {
	CircleID string   `json:"circleId"`
	Name     *string  `json:"name"`
	Lat      *float64 `json:"lat"`
	Lon      *float64 `json:"lon"`
	Radius   *float64 `json:"radius"`
	// Absent: unchanged. null: always alert. Object: alert only at those times.
	Schedule json.RawMessage `json:"schedule"`
}

// schedule reads the schedule field: whether it was sent, and what it says.
func (req *placeRequest) schedule() (set bool, sc *Schedule, ok bool) {
	if len(req.Schedule) == 0 {
		return false, nil, true
	}
	if string(req.Schedule) == "null" {
		return true, nil, true
	}
	sc = &Schedule{}
	if err := json.Unmarshal(req.Schedule, sc); err != nil || !sc.valid() {
		return true, nil, false
	}
	return true, sc, true
}

func (req *placeRequest) cleanName() bool {
	if req.Name == nil {
		return true
	}
	n, ok := cleanName(*req.Name)
	req.Name = &n
	return ok
}

func (s *Server) createPlace(w http.ResponseWriter, r *http.Request, byMember string) {
	var req placeRequest
	if !readJSON(w, r, &req) {
		return
	}
	if req.Name == nil || req.Lat == nil || req.Lon == nil || req.Radius == nil || !req.cleanName() {
		writeError(w, http.StatusBadRequest, capitalize(ErrBadPlace.Error()))
		return
	}
	_, sc, ok := req.schedule()
	if !ok {
		writeError(w, http.StatusBadRequest, capitalize(ErrBadSchedule.Error()))
		return
	}
	p, err := s.store.CreatePlace(byMember, Place{CircleID: req.CircleID, Name: *req.Name, Lat: *req.Lat, Lon: *req.Lon, Radius: *req.Radius, Schedule: sc})
	s.writePlaceResult(w, p, err)
}

func (s *Server) updatePlace(w http.ResponseWriter, r *http.Request, byMember string) {
	var req placeRequest
	if !readJSON(w, r, &req) {
		return
	}
	if !req.cleanName() {
		writeError(w, http.StatusBadRequest, capitalize(ErrBadPlace.Error()))
		return
	}
	set, sc, ok := req.schedule()
	if !ok {
		writeError(w, http.StatusBadRequest, capitalize(ErrBadSchedule.Error()))
		return
	}
	p, err := s.store.UpdatePlace(byMember, r.PathValue("id"), PlaceUpdate{Name: req.Name, Lat: req.Lat, Lon: req.Lon, Radius: req.Radius, SetSchedule: set, Schedule: sc})
	s.writePlaceResult(w, p, err)
}

func (s *Server) writePlaceResult(w http.ResponseWriter, p *Place, err error) {
	switch {
	case errors.Is(err, ErrBadPlace), errors.Is(err, ErrBadSchedule):
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
	case err != nil:
		writeError(w, http.StatusNotFound, "no such fence or group")
	default:
		writeJSON(w, http.StatusOK, p)
	}
}

func (s *Server) handleCreatePlace(w http.ResponseWriter, r *http.Request, me *Member) {
	s.createPlace(w, r, me.ID)
}

func (s *Server) handleUpdatePlace(w http.ResponseWriter, r *http.Request, me *Member) {
	s.updatePlace(w, r, me.ID)
}

func (s *Server) handleDeletePlace(w http.ResponseWriter, r *http.Request, me *Member) {
	if err := s.store.DeletePlace(me.ID, r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such fence")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAdminPlaces(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.store.AllPlaces())
}

func (s *Server) handleAdminCreatePlace(w http.ResponseWriter, r *http.Request) {
	s.createPlace(w, r, "")
}
func (s *Server) handleAdminUpdatePlace(w http.ResponseWriter, r *http.Request) {
	s.updatePlace(w, r, "")
}

func (s *Server) handleAdminDeletePlace(w http.ResponseWriter, r *http.Request) {
	if err := s.store.DeletePlace("", r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such fence")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- invite landing page ----

var joinTmpl = template.Must(template.New("join").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{if .Reset}}Sign back in to{{else}}Join{{end}} {{.Family}} on FamLogic</title><link rel="stylesheet" href="/style.css"></head>
<body class="join"><main class="card">
{{if .Invalid}}
<h1>This link has expired</h1>
<p>It was already used, or it's more than a few days old. Ask your family's admin for a new one.</p>
{{else}}
{{if .Reset}}
<h1>Sign back in, {{.Name}}</h1>
<p>Use this link to choose a new password for <strong>{{.Name}}</strong> in <strong>{{.Family}}</strong> and sign in on this phone. It works once.</p>
{{else}}
<h1>Join {{.Family}}</h1>
<p>You've been invited to share your location with <strong>{{.Family}}</strong> using FamLogic.</p>
{{end}}
<ol>
<li>Install the FamLogic app on your Android phone.</li>
<li>Tap the button below to open it with everything filled in.</li>
</ol>
<p><a class="button" href="{{.AppLink}}">Open in FamLogic</a></p>
<p class="muted">Button not working? Open the app and enter these by hand:</p>
<dl><dt>Server</dt><dd><code>{{.Server}}</code></dd><dt>Invite code</dt><dd><code>{{.Code}}</code></dd></dl>
{{end}}
</main></body></html>`))

func (s *Server) handleJoinPage(w http.ResponseWriter, r *http.Request) {
	code := normalizeCode(r.PathValue("code"))
	base := s.baseURL(r)
	q := url.Values{"server": {base}, "code": {code}}
	inv, member, ok := s.store.InviteInfo(code)
	data := map[string]any{
		"Family":  s.cfg.FamilyName,
		"Server":  base,
		"Code":    code,
		"AppLink": template.URL("famlogic://join?" + q.Encode()),
		"Invalid": !ok,
		"Reset":   ok && member != nil,
		"Name":    "",
	}
	if ok {
		data["Name"] = inv.Name
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = joinTmpl.Execute(w, data)
}

// publicDevices: a member's devices without their token hashes.
func publicDevices(ds []Device) []Device {
	out := make([]Device, 0, len(ds))
	for _, d := range ds {
		d.Hash = ""
		out = append(out, d)
	}
	return out
}

// DELETE /api/admin/members/{id}/devices/{dev}: sign out one device.
func (s *Server) handleAdminSignOutDevice(w http.ResponseWriter, r *http.Request) {
	if err := s.store.SignOutDevice(r.PathValue("id"), r.PathValue("dev")); err != nil {
		writeError(w, http.StatusNotFound, "no such device")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
