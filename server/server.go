package main

import (
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
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	adminCookie     = "libre360_admin"
	adminSessionTTL = 30 * 24 * time.Hour
	maxBody         = 64 << 10
)

type Server struct {
	cfg    Config
	store  *Store
	static fs.FS

	loginMu sync.Mutex // serialises failed logins so guessing is slow
}

func NewServer(cfg Config, store *Store, static fs.FS) *Server {
	return &Server{cfg: cfg, store: store, static: static}
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("GET /api/info", s.handleInfo)

	// Phone app API (authenticated with the device token).
	mux.HandleFunc("POST /api/join", s.handleJoin)
	mux.HandleFunc("GET /api/members", s.device(s.handleMembers))
	mux.HandleFunc("POST /api/location", s.device(s.handleLocation))
	mux.HandleFunc("PATCH /api/me", s.device(s.handlePatchMe))
	mux.HandleFunc("POST /api/me", s.device(s.handlePatchMe)) // for clients that can't send PATCH
	mux.HandleFunc("DELETE /api/me", s.device(s.handleLeave))

	// Web dashboard API (authenticated with the admin password).
	mux.HandleFunc("POST /api/admin/login", s.handleLogin)
	mux.HandleFunc("POST /api/admin/logout", s.handleLogout)
	mux.HandleFunc("GET /api/admin/members", s.admin(s.handleAdminMembers))
	mux.HandleFunc("PATCH /api/admin/members/{id}", s.admin(s.handleAdminPatchMember))
	mux.HandleFunc("DELETE /api/admin/members/{id}", s.admin(s.handleAdminDeleteMember))
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
}

func toPublic(ms []Member) []publicMember {
	out := make([]publicMember, len(ms))
	for i, m := range ms {
		out[i] = publicMember{ID: m.ID, Name: m.Name, Color: m.Color, Paused: m.Paused, Loc: m.Loc}
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
		me, ok := s.store.MemberByToken(strings.TrimSpace(token))
		if !ok {
			// 401 tells the app it was removed from the family.
			writeError(w, http.StatusUnauthorized, "unknown device; it may have been removed")
			return
		}
		h(w, r, me)
	}
}

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.serverInfo())
}

func (s *Server) handleJoin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code string `json:"code"`
		Name string `json:"name"`
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
	m, token, err := s.store.Join(req.Code, name)
	if errors.Is(err, ErrNotFound) {
		time.Sleep(500 * time.Millisecond) // slow down code guessing
		writeError(w, http.StatusNotFound, "that invite code is not valid or has expired")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	if m.Name == "" {
		// Neither the invite nor the phone gave a name.
		n := "Member"
		m, _ = s.store.UpdateMember(m.ID, &n, nil, nil)
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "member": toPublic([]Member{*m})[0], "server": s.serverInfo()})
}

func (s *Server) handleMembers(w http.ResponseWriter, r *http.Request, me *Member) {
	writeJSON(w, http.StatusOK, map[string]any{"me": me.ID, "members": toPublic(s.store.Members()), "server": s.serverInfo()})
}

func (s *Server) handleLocation(w http.ResponseWriter, r *http.Request, me *Member) {
	var l Location
	if !readJSON(w, r, &l) {
		return
	}
	if math.IsNaN(l.Lat) || math.IsNaN(l.Lon) || l.Lat < -90 || l.Lat > 90 || l.Lon < -180 || l.Lon > 180 {
		writeError(w, http.StatusBadRequest, "lat/lon out of range")
		return
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
	if me.Paused {
		// A fix that was in flight when the person paused: drop it.
		// The app un-pauses explicitly with PATCH /api/me.
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if err := s.store.UpdateLocation(me.ID, l); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handlePatchMe(w http.ResponseWriter, r *http.Request, me *Member) {
	var req struct {
		Name   *string `json:"name"`
		Paused *bool   `json:"paused"`
		Color  *string `json:"color"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if !s.validatePatch(w, req.Name, req.Color) {
		return
	}
	m, err := s.store.UpdateMember(me.ID, req.Name, req.Paused, req.Color)
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

func (s *Server) handleAdminMembers(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"members": toPublic(s.store.Members()), "server": s.serverInfo()})
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
	m, err := s.store.UpdateMember(r.PathValue("id"), req.Name, nil, req.Color)
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
		Name string `json:"name"`
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
	inv, err := s.store.CreateInvite(name, s.cfg.InviteTTL)
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

// ---- invite landing page ----

var joinTmpl = template.Must(template.New("join").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Join {{.Family}} on Libre360</title><link rel="stylesheet" href="/style.css"></head>
<body class="join"><main class="card">
<h1>Join {{.Family}}</h1>
<p>You've been invited to share your location with <strong>{{.Family}}</strong> using Libre360.</p>
<ol>
<li>Install the Libre360 app on your Android phone.</li>
<li>Tap the button below to open it with everything filled in.</li>
</ol>
<p><a class="button" href="{{.AppLink}}">Open in Libre360</a></p>
<p class="muted">Button not working? Open the app and enter these by hand:</p>
<dl><dt>Server</dt><dd><code>{{.Server}}</code></dd><dt>Invite code</dt><dd><code>{{.Code}}</code></dd></dl>
</main></body></html>`))

func (s *Server) handleJoinPage(w http.ResponseWriter, r *http.Request) {
	code := normalizeCode(r.PathValue("code"))
	base := s.baseURL(r)
	q := url.Values{"server": {base}, "code": {code}}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = joinTmpl.Execute(w, map[string]any{
		"Family":  s.cfg.FamilyName,
		"Server":  base,
		"Code":    code,
		"AppLink": template.URL("libre360://join?" + q.Encode()),
	})
}
