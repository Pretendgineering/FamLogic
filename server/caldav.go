package main

// CalDAV for Datebook: phones and computers sync the family calendar both ways.
// One shared calendar, the family's, at /dav/<person>/calendars/family/. Sign in with your
// FamLogic username and password (Basic auth, so use it over HTTPS). Setting up:
// DAVx5 on Android, Settings → Calendar → Accounts on an iPhone, or Thunderbird,
// with the server's address; they find the calendar themselves
// (/.well-known/caldav).

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/emersion/go-ical"
	"github.com/emersion/go-webdav"
	"github.com/emersion/go-webdav/caldav"
)

const davPrefix = "/dav"

type davUserKey struct{}

// davBackend serves the family calendar to the person signed in.
type davBackend struct{ s *Server }

func davUser(ctx context.Context) *Member {
	m, _ := ctx.Value(davUserKey{}).(*Member)
	return m
}

func (b *davBackend) CurrentUserPrincipal(ctx context.Context) (string, error) {
	return davPrefix + "/" + davUser(ctx).ID + "/", nil
}

// Paths: /dav/<person>/ (principal), /dav/<person>/calendars/ (home),
// /dav/<person>/calendars/family/ (the calendar) and its events below it.
func (b *davBackend) CalendarHomeSetPath(ctx context.Context) (string, error) {
	return davPrefix + "/" + davUser(ctx).ID + "/calendars/", nil
}

func (b *davBackend) calPath(ctx context.Context) string {
	return davPrefix + "/" + davUser(ctx).ID + "/calendars/family/"
}

func (b *davBackend) familyCalendar(ctx context.Context) caldav.Calendar {
	return caldav.Calendar{
		Path: b.calPath(ctx), Name: b.s.familyName(), Description: "The family calendar (FamLogic Datebook)",
		SupportedComponentSet: []string{ical.CompEvent}, Color: "#6F5AA8", MaxResourceSize: 1 << 20,
		ReadOnly: !b.s.canEditCalendar(davUser(ctx)),
	}
}

func (b *davBackend) CreateCalendar(ctx context.Context, cal *caldav.Calendar) error {
	return webdav.NewHTTPError(http.StatusForbidden, fmt.Errorf("FamLogic has one family calendar"))
}

func (b *davBackend) ListCalendars(ctx context.Context) ([]caldav.Calendar, error) {
	out := []caldav.Calendar{b.familyCalendar(ctx)}
	// Outside calendars (school, holidays...), read-only, each its own calendar.
	for _, f := range b.s.store.Feeds() {
		out = append(out, caldav.Calendar{Path: b.feedCalPath(ctx, f.ID), Name: f.Name, Description: "An outside calendar, added in FamLogic (read-only)",
			SupportedComponentSet: []string{ical.CompEvent}, Color: f.Color, MaxResourceSize: 1 << 20, ReadOnly: true})
	}
	return out, nil
}

func (b *davBackend) feedCalPath(ctx context.Context, id string) string {
	return davPrefix + "/" + davUser(ctx).ID + "/calendars/" + id + "/"
}

// feedOf says which outside calendar a path is in ("" for the family's).
func (b *davBackend) feedOf(ctx context.Context, p string) string {
	for _, f := range b.s.store.Feeds() {
		if strings.HasPrefix(p, b.feedCalPath(ctx, f.ID)) || strings.TrimSuffix(p, "/")+"/" == b.feedCalPath(ctx, f.ID) {
			return f.ID
		}
	}
	return ""
}

func (b *davBackend) GetCalendar(ctx context.Context, p string) (*caldav.Calendar, error) {
	all, _ := b.ListCalendars(ctx)
	for _, c := range all {
		if strings.TrimSuffix(p, "/") == strings.TrimSuffix(c.Path, "/") {
			return &c, nil
		}
	}
	return nil, webdav.NewHTTPError(http.StatusNotFound, fmt.Errorf("no such calendar"))
}

// feedObjects are an outside calendar's events, one object per UID, as the link sent them.
func (b *davBackend) feedObjects(ctx context.Context, id string) []caldav.CalendarObject {
	raw, err := os.ReadFile(b.s.feedPath(id))
	if err != nil {
		return nil
	}
	src, err := ical.NewDecoder(bytes.NewReader(raw)).Decode()
	if err != nil {
		return nil
	}
	var zones []*ical.Component
	byUID := map[string]*ical.Calendar{}
	var order []string
	for _, c := range src.Children {
		switch c.Name {
		case ical.CompTimezone:
			zones = append(zones, c)
		case ical.CompEvent:
			uid, _ := c.Props.Text(ical.PropUID)
			if uid == "" {
				continue
			}
			cal, ok := byUID[uid]
			if !ok {
				cal = ical.NewCalendar()
				cal.Props.SetText(ical.PropVersion, "2.0")
				cal.Props.SetText(ical.PropProductID, "-//FamLogic//Datebook//EN")
				byUID[uid] = cal
				order = append(order, uid)
			}
			cal.Children = append(cal.Children, c)
		}
	}
	mod := time.Now()
	if st, err := os.Stat(b.s.feedPath(id)); err == nil {
		mod = st.ModTime()
	}
	out := []caldav.CalendarObject{}
	for _, uid := range order {
		cal := byUID[uid]
		cal.Children = append(append([]*ical.Component{}, zones...), cal.Children...)
		data := encodeICS(cal)
		out = append(out, caldav.CalendarObject{Path: b.feedCalPath(ctx, id) + strings.NewReplacer("/", "_", "?", "_", "#", "_").Replace(uid) + ".ics",
			ModTime: mod, ContentLength: int64(len(data)), ETag: etagOf(data), Data: cal})
	}
	return out
}

func (b *davBackend) objectPath(ctx context.Context, uid string) string {
	return b.calPath(ctx) + strings.NewReplacer("/", "_", "?", "_", "#", "_").Replace(uid) + ".ics"
}

func (b *davBackend) object(ctx context.Context, ev CalEvent, members []Member) caldav.CalendarObject {
	cal := b.s.cal.toICS(ev, members)
	data := encodeICS(cal)
	return caldav.CalendarObject{Path: b.objectPath(ctx, ev.UID), ModTime: ev.mod, ContentLength: int64(len(data)), ETag: etagOf(data), Data: cal}
}

func (b *davBackend) ListCalendarObjects(ctx context.Context, p string, req *caldav.CalendarCompRequest) ([]caldav.CalendarObject, error) {
	if id := b.feedOf(ctx, p); id != "" {
		return b.feedObjects(ctx, id), nil
	}
	members := b.s.store.Members()
	out := []caldav.CalendarObject{}
	for _, ev := range b.s.cal.all(members) {
		out = append(out, b.object(ctx, ev, members))
	}
	return out, nil
}

func (b *davBackend) GetCalendarObject(ctx context.Context, p string, req *caldav.CalendarCompRequest) (*caldav.CalendarObject, error) {
	if id := b.feedOf(ctx, p); id != "" {
		for _, o := range b.feedObjects(ctx, id) {
			if o.Path == p {
				return &o, nil
			}
		}
		return nil, webdav.NewHTTPError(http.StatusNotFound, fmt.Errorf("no such event"))
	}
	members := b.s.store.Members()
	for _, ev := range b.s.cal.all(members) {
		if b.objectPath(ctx, ev.UID) == p {
			o := b.object(ctx, ev, members)
			return &o, nil
		}
	}
	return nil, webdav.NewHTTPError(http.StatusNotFound, fmt.Errorf("no such event"))
}

func (b *davBackend) QueryCalendarObjects(ctx context.Context, p string, query *caldav.CalendarQuery) ([]caldav.CalendarObject, error) {
	all, err := b.ListCalendarObjects(ctx, p, &query.CompRequest)
	if err != nil {
		return nil, err
	}
	return caldav.Filter(query, all)
}

func (b *davBackend) PutCalendarObject(ctx context.Context, p string, cal *ical.Calendar, opts *caldav.PutCalendarObjectOptions) (*caldav.CalendarObject, error) {
	me := davUser(ctx)
	if !b.s.canEditCalendar(me) || b.feedOf(ctx, p) != "" {
		return nil, webdav.NewHTTPError(http.StatusForbidden, fmt.Errorf("you can see the calendar, but not change it"))
	}
	members := b.s.store.Members()
	ev, err := b.s.cal.fromICS(cal, members)
	if err != nil {
		return nil, webdav.NewHTTPError(http.StatusBadRequest, err)
	}
	o := b.object(ctx, ev, members)
	// The app chose the path; answer at the one it used.
	if p != "" {
		o.Path = p
	}
	return &o, nil
}

func (b *davBackend) DeleteCalendarObject(ctx context.Context, p string) error {
	me := davUser(ctx)
	if !b.s.canEditCalendar(me) || b.feedOf(ctx, p) != "" {
		return webdav.NewHTTPError(http.StatusForbidden, fmt.Errorf("you can see the calendar, but not change it"))
	}
	members := b.s.store.Members()
	for _, ev := range b.s.cal.all(members) {
		if b.objectPath(ctx, ev.UID) == p {
			return b.s.cal.archive(ev.ID)
		}
	}
	return webdav.NewHTTPError(http.StatusNotFound, fmt.Errorf("no such event"))
}

// ---- signing in (Basic auth with a FamLogic username and password)

// davLogins remembers good sign-ins for a few minutes (passwords are slow to
// check on purpose, and calendar apps ask often).
type davLogins struct {
	mu   sync.Mutex
	good map[string]davLogin
}

type davLogin struct {
	id string
	at time.Time
}

func (s *Server) davAuth(r *http.Request) *Member {
	user, pass, ok := r.BasicAuth()
	if !ok || user == "" || pass == "" {
		return nil
	}
	h := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(user)) + "\x00" + pass))
	key := hex.EncodeToString(h[:])
	s.dav.mu.Lock()
	if l, ok := s.dav.good[key]; ok && time.Since(l.at) < 10*time.Minute {
		s.dav.mu.Unlock()
		for _, m := range s.store.Members() {
			if m.ID == l.id {
				return &m
			}
		}
		return nil
	}
	s.dav.mu.Unlock()
	username := strings.ToLower(strings.TrimSpace(user))
	if s.limiter.Locked(username) > 0 {
		return nil
	}
	m, ok := s.store.MemberByUsername(username)
	hash := ""
	if ok {
		hash = m.PasswordHash
	}
	if !VerifyPassword(pass, hash) || !ok || hash == "" {
		return nil
	}
	s.dav.mu.Lock()
	if s.dav.good == nil {
		s.dav.good = map[string]davLogin{}
	}
	s.dav.good[key] = davLogin{m.ID, time.Now()}
	s.dav.mu.Unlock()
	return m
}

// handleDAV: CalDAV, for people signed in with their username and password.
func (s *Server) handleDAV(w http.ResponseWriter, r *http.Request) {
	if s.cal == nil || !s.store.Modules().Enabled["calendar"] {
		http.NotFound(w, r)
		return
	}
	if r.URL.Path == "/.well-known/caldav" {
		http.Redirect(w, r, davPrefix+"/", http.StatusMovedPermanently)
		return
	}
	me := s.davAuth(r)
	if me == nil || s.calendarLevel(me) == LevelOff {
		w.Header().Set("WWW-Authenticate", `Basic realm="FamLogic calendar", charset="UTF-8"`)
		http.Error(w, "sign in with your FamLogic username and password", http.StatusUnauthorized)
		return
	}
	ctx := context.WithValue(r.Context(), davUserKey{}, me)
	(&caldav.Handler{Backend: &davBackend{s}, Prefix: davPrefix}).ServeHTTP(w, r.WithContext(ctx))
}
