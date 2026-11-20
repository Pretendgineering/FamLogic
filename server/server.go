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
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	adminCookie     = "thereabouts_admin"
	adminSessionTTL = 30 * 24 * time.Hour
	maxBody         = 64 << 10
)

type Server struct {
	cfg    Config
	store  *Store
	static fs.FS

	loginMu sync.Mutex    // serialises failed logins so guessing is slow
	limiter *loginLimiter // locks a username after repeated wrong passwords
}

func NewServer(cfg Config, store *Store, static fs.FS) *Server {
	return &Server{cfg: cfg, store: store, static: static, limiter: newLoginLimiter()}
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
	mux.HandleFunc("POST /api/checkin", s.device(s.handleCheckIn))
	mux.HandleFunc("PATCH /api/me", s.device(s.handlePatchMe))
	mux.HandleFunc("POST /api/me", s.device(s.handlePatchMe)) // for clients that can't send PATCH
	mux.HandleFunc("DELETE /api/me", s.device(s.handleLeave))

	// Web dashboard API (authenticated with the admin password).
	mux.HandleFunc("POST /api/admin/login", s.handleLogin)
	mux.HandleFunc("POST /api/admin/logout", s.handleLogout)
	mux.HandleFunc("GET /api/admin/members", s.admin(s.handleAdminMembers))
	mux.HandleFunc("PATCH /api/admin/members/{id}", s.admin(s.handleAdminPatchMember))
	mux.HandleFunc("DELETE /api/admin/members/{id}", s.admin(s.handleAdminDeleteMember))
	mux.HandleFunc("POST /api/admin/members/{id}/reset", s.admin(s.handleAdminResetSignIn))
	mux.HandleFunc("GET /api/admin/members/{id}/trail", s.admin(s.handleAdminTrail))
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

	return securityHeaders(mux)
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
	Paused bool      `json:"paused"`
	Loc    *Location `json:"loc"`
	// When this person's active SOS started (unix ms); absent if no SOS.
	SOSSince int64 `json:"sosSince,omitempty"`
	// Circles: for phones, the ones in common with the viewer; for the dashboard, all of theirs.
	Circles []string `json:"circles"`
	// The place they're at (e.g. "Home"), if any.
	Place string `json:"place,omitempty"`
	// Whether they keep a trail you can look at.
	Trail bool `json:"trail,omitempty"`
}

func toPublic(ms []Member) []publicMember {
	out := make([]publicMember, len(ms))
	for i, m := range ms {
		out[i] = publicMember{ID: m.ID, Name: m.Name, Color: m.Color, Paused: m.Paused, Loc: m.Loc, SOSSince: m.SOSSince, Circles: m.Circles}
		if out[i].Circles == nil {
			out[i].Circles = []string{}
		}
	}
	return out
}

func (s *Server) serverInfo() map[string]any {
	return map[string]any{
		"name":            s.cfg.FamilyName,
		"version":         version,
		"tileUrl":         s.cfg.TileURL,
		"tileAttribution": s.cfg.TileAttribution,
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
			writeError(w, http.StatusUnauthorized, "missing token")
			return
		}
		me, signedOut := s.store.lookupToken(strings.TrimSpace(token))
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
		h(w, r, me)
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
	resp := map[string]any{"kind": "join", "name": inv.Name, "server": s.serverInfo(), "circle": s.circleName(inv.CircleID)}
	if m != nil {
		resp["kind"] = "reset"
		resp["name"] = m.Name
		resp["username"] = m.Username
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
	// Apps from before accounts existed send neither username nor password.
	username, hash := "", ""
	if req.Username != "" || req.Password != "" {
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
		members[i].Trail = v.TrailHours > 0 && v.Loc != nil
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
			"username":        me.Username,
			"hasPassword":     me.PasswordHash != "",
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
		Loc *Location `json:"loc"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	m, err := s.store.StartSOS(me.ID, optionalLocation(req.Loc))
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
		Name            *string `json:"name"`
		Paused          *bool   `json:"paused"`
		Color           *string `json:"color"`
		ShareLowBattery *bool   `json:"shareLowBattery"`
		ShareArrivals   *bool   `json:"shareArrivals"`
		TrailHours      *int    `json:"trailHours"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !s.validatePatch(w, req.Name, req.Color) {
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
	m, err := s.store.UpdateMember(me.ID, MemberPatch{Name: req.Name, Paused: req.Paused, Color: req.Color, ShareLowBattery: req.ShareLowBattery, ShareArrivals: req.ShareArrivals})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, toPublic([]Member{*m})[0])
}

func (s *Server) validatePatch(w http.ResponseWriter, name, color *string) bool {
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

func (s *Server) handleLeave(w http.ResponseWriter, r *http.Request, me *Member) {
	_ = s.store.DeleteMember(me.ID)
	w.WriteHeader(http.StatusNoContent)
}

// ---- admin (web dashboard) auth ----

func (s *Server) sign(exp string) string {
	mac := hmac.New(sha256.New, []byte(s.store.Secret()))
	mac.Write([]byte("admin|" + exp))
	return hex.EncodeToString(mac.Sum(nil))
}

func (s *Server) isAdmin(r *http.Request) bool {
	c, err := r.Cookie(adminCookie)
	if err != nil {
		return false
	}
	exp, sig, ok := strings.Cut(c.Value, ".")
	if !ok {
		return false
	}
	e, err := strconv.ParseInt(exp, 10, 64)
	if err != nil || time.Now().Unix() > e {
		return false
	}
	return hmac.Equal([]byte(sig), []byte(s.sign(exp)))
}

func (s *Server) admin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.isAdmin(r) {
			writeError(w, http.StatusUnauthorized, "please log in")
			return
		}
		h(w, r)
	}
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	a := sha256.Sum256([]byte(req.Password))
	b := sha256.Sum256([]byte(s.cfg.AdminPassword))
	if subtle.ConstantTimeCompare(a[:], b[:]) != 1 {
		s.loginMu.Lock()
		time.Sleep(time.Second)
		s.loginMu.Unlock()
		writeError(w, http.StatusUnauthorized, "wrong password")
		return
	}
	exp := strconv.FormatInt(time.Now().Add(adminSessionTTL).Unix(), 10)
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    exp + "." + s.sign(exp),
		Path:     "/",
		MaxAge:   int(adminSessionTTL.Seconds()),
		HttpOnly: true,
		Secure:   strings.HasPrefix(s.baseURL(r), "https://"),
		SameSite: http.SameSiteStrictMode,
	})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: adminCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true})
	w.WriteHeader(http.StatusNoContent)
}

// adminMember adds sign-in details that only the dashboard sees.
type adminMember struct {
	publicMember
	Username    string   `json:"username"`
	HasPassword bool     `json:"hasPassword"`
	LastSignIn  int64    `json:"lastSignIn,omitempty"`
	HiddenFrom  []string `json:"hiddenFrom"`
}

func (s *Server) handleAdminMembers(w http.ResponseWriter, r *http.Request) {
	ms := s.store.Members()
	pub := toPublic(ms)
	at := s.store.AdminPlaceNames()
	for i := range pub {
		pub[i].Place = at[pub[i].ID]
		pub[i].Trail = ms[i].TrailHours > 0 && !ms[i].Paused
	}
	out := make([]adminMember, len(ms))
	for i, m := range ms {
		out[i] = adminMember{pub[i], m.Username, m.PasswordHash != "", m.LastSignIn, append([]string{}, m.HiddenFrom...)}
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": out, "circles": s.store.Circles(), "places": s.store.AllPlaces(), "server": s.serverInfo()})
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
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !s.validatePatch(w, req.Name, req.Color) {
		return
	}
	m, err := s.store.UpdateMember(r.PathValue("id"), MemberPatch{Name: req.Name, Color: req.Color})
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
	}
	if !readJSON(w, r, &req) {
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
	inv, err := s.store.CreateInvite(name, req.CircleID, s.cfg.InviteTTL)
	if errors.Is(err, ErrNotFound) {
		writeError(w, http.StatusBadRequest, "no such circle")
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
		writeError(w, http.StatusNotFound, "you're not in that circle")
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
		writeError(w, http.StatusBadRequest, "circle names are 1-40 characters")
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
		writeError(w, http.StatusBadRequest, "circle names are 1-40 characters")
		return
	}
	if err := s.store.RenameCircle(r.PathValue("id"), name); err != nil {
		writeError(w, http.StatusNotFound, "no such circle")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAdminDeleteCircle(w http.ResponseWriter, r *http.Request) {
	switch err := s.store.DeleteCircle(r.PathValue("id")); {
	case errors.Is(err, ErrLastCircle):
		writeError(w, http.StatusConflict, "You can't delete the last circle")
	case err != nil:
		writeError(w, http.StatusNotFound, "no such circle")
	default:
		w.WriteHeader(http.StatusNoContent)
	}
}

// handleAdminMembership puts a member in a circle (PUT) or takes them out (DELETE).
func (s *Server) handleAdminMembership(w http.ResponseWriter, r *http.Request) {
	if err := s.store.SetMembership(r.PathValue("mid"), r.PathValue("id"), r.Method == http.MethodPut); err != nil {
		writeError(w, http.StatusNotFound, "no such member or circle")
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

// ---- places ----

type placeRequest struct {
	CircleID string   `json:"circleId"`
	Name     *string  `json:"name"`
	Lat      *float64 `json:"lat"`
	Lon      *float64 `json:"lon"`
	Radius   *float64 `json:"radius"`
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
	p, err := s.store.CreatePlace(byMember, Place{CircleID: req.CircleID, Name: *req.Name, Lat: *req.Lat, Lon: *req.Lon, Radius: *req.Radius})
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
	p, err := s.store.UpdatePlace(byMember, r.PathValue("id"), req.Name, req.Lat, req.Lon, req.Radius)
	s.writePlaceResult(w, p, err)
}

func (s *Server) writePlaceResult(w http.ResponseWriter, p *Place, err error) {
	switch {
	case errors.Is(err, ErrBadPlace):
		writeError(w, http.StatusBadRequest, capitalize(err.Error()))
	case err != nil:
		writeError(w, http.StatusNotFound, "no such place or circle")
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
		writeError(w, http.StatusNotFound, "no such place")
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
		writeError(w, http.StatusNotFound, "no such place")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- invite landing page ----

var joinTmpl = template.Must(template.New("join").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{if .Reset}}Sign back in to{{else}}Join{{end}} {{.Family}} on Thereabouts</title><link rel="stylesheet" href="/style.css"></head>
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
<p>You've been invited to share your location with <strong>{{.Family}}</strong> using Thereabouts.</p>
{{end}}
<ol>
<li>Install the Thereabouts app on your Android phone.</li>
<li>Tap the button below to open it with everything filled in.</li>
</ol>
<p><a class="button" href="{{.AppLink}}">Open in Thereabouts</a></p>
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
		"AppLink": template.URL("thereabouts://join?" + q.Encode()),
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
