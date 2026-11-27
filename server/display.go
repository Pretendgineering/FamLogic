package main

// Corkboard: the family display (the TV app, and display tablets). One request
// gives the display everything it shows (the board); parents can start quiet
// time (nap time) and push a message to every display from their phone. The
// display's look follows the sun at home: light in the morning, darker towards
// evening, dark at night.

import (
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// DisplayState is what parents have set for the displays.
type DisplayState struct {
	QuietSince int64            `json:"quietSince,omitempty"` // unix ms; 0 = not quiet
	QuietUntil int64            `json:"quietUntil,omitempty"` // unix ms; 0 with QuietSince set = until someone ends it
	QuietBy    string           `json:"quietBy,omitempty"`
	Messages   []DisplayMessage `json:"messages,omitempty"` // newest last, the last 20
	Apps       []DisplayApp     `json:"apps,omitempty"`     // the apps on the TVs' launchers, for pictures from the console
	Photos     []DisplayPhoto   `json:"photos,omitempty"`   // the screen saver's album
	Stream     string           `json:"stream,omitempty"`   // the screen saver's photo link
}

// DisplayApp is an app on a TV's launcher. Image is when a picture was uploaded
// for it (unix ms; 0 = none, the TV uses the app's own banner).
type DisplayApp struct {
	Pkg   string `json:"pkg"`
	Label string `json:"label"`
	Image int64  `json:"image,omitempty"`
}

// DisplayMessage is a message pushed to the family's displays.
type DisplayMessage struct {
	ID   string `json:"id"`
	From string `json:"from"` // the sender's name
	Text string `json:"text"`
	Time int64  `json:"time"`
}

const messageShowFor = 30 * time.Minute

func (s *Store) Display() DisplayState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.d.Display == nil {
		return DisplayState{}
	}
	d := *s.d.Display
	d.Messages = append([]DisplayMessage{}, d.Messages...)
	d.Apps = append([]DisplayApp{}, d.Apps...)
	d.Photos = append([]DisplayPhoto{}, d.Photos...)
	return d
}

func (s *Store) updateDisplay(f func(d *DisplayState)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.d.Display == nil {
		s.d.Display = &DisplayState{}
	}
	f(s.d.Display)
	if n := len(s.d.Display.Messages); n > 20 {
		s.d.Display.Messages = s.d.Display.Messages[n-20:]
	}
	return s.saveLocked()
}

// quietNow says whether quiet time is on (a timer that has run out is off).
func (d DisplayState) quietNow(now time.Time) bool {
	return d.QuietSince > 0 && (d.QuietUntil == 0 || now.UnixMilli() < d.QuietUntil)
}

// ---- the sun at home

// homeSpot is where the family lives, for sunrise and sunset: the fence called
// Home, else the first fence. ok is false if there are none.
func (s *Server) homeSpot() (lat, lon float64, ok bool) {
	places := s.store.AllPlaces()
	for _, p := range places {
		if strings.EqualFold(strings.TrimSpace(p.Name), "home") {
			return p.Lat, p.Lon, true
		}
	}
	if len(places) > 0 {
		return places[0].Lat, places[0].Lon, true
	}
	return 0, 0, false
}

// sunTimes gives the day's sunrise and sunset at lat/lon (the standard NOAA
// approximation; good to a minute or two). ok is false where the sun doesn't
// rise or set that day (far north or south).
func sunTimes(day time.Time, lat, lon float64) (rise, set time.Time, ok bool) {
	rad := math.Pi / 180
	y, m, d := day.Date()
	n := float64(time.Date(y, m, d, 12, 0, 0, 0, time.UTC).YearDay())
	gamma := 2 * math.Pi / 365 * (n - 1)
	eqtime := 229.18 * (0.000075 + 0.001868*math.Cos(gamma) - 0.032077*math.Sin(gamma) - 0.014615*math.Cos(2*gamma) - 0.040849*math.Sin(2*gamma))
	decl := 0.006918 - 0.399912*math.Cos(gamma) + 0.070257*math.Sin(gamma) - 0.006758*math.Cos(2*gamma) +
		0.000907*math.Sin(2*gamma) - 0.002697*math.Cos(3*gamma) + 0.00148*math.Sin(3*gamma)
	cosHA := math.Cos(90.833*rad)/(math.Cos(lat*rad)*math.Cos(decl)) - math.Tan(lat*rad)*math.Tan(decl)
	if cosHA < -1 || cosHA > 1 {
		return time.Time{}, time.Time{}, false
	}
	ha := math.Acos(cosHA) / rad
	midnight := time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
	riseMin := 720 - 4*(lon+ha) - eqtime
	setMin := 720 - 4*(lon-ha) - eqtime
	return midnight.Add(time.Duration(riseMin * float64(time.Minute))), midnight.Add(time.Duration(setMin * float64(time.Minute))), true
}

// ---- HTTP

func mayControlDisplays(me *Member) bool {
	return me.ID == "admin" || me.role() == RoleParent || me.role() == RoleGrandparent
}

// GET /api/display/board: everything a display shows, in one go.
func (s *Server) handleDisplayBoard(w http.ResponseWriter, r *http.Request, me *Member) {
	now := time.Now()
	midnight := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.Local)
	st := s.store.Display()
	var msgs []DisplayMessage
	for i := len(st.Messages) - 1; i >= 0; i-- {
		if now.Sub(time.UnixMilli(st.Messages[i].Time)) < messageShowFor {
			msgs = append(msgs, st.Messages[i])
		}
	}
	sun := map[string]any{}
	if lat, lon, ok := s.homeSpot(); ok {
		if rise, set, ok := sunTimes(now, lat, lon); ok {
			sun["sunrise"], sun["sunset"] = rise.UnixMilli(), set.UnixMilli()
		}
	}
	quiet := map[string]any{"on": st.quietNow(now)}
	if st.quietNow(now) {
		quiet["until"], quiet["by"], quiet["since"] = st.QuietUntil, st.QuietBy, st.QuietSince
	}
	on := s.store.Modules().Enabled
	board := map[string]any{
		"family": s.familyName(), "now": now.UnixMilli(), "sun": sun, "quiet": quiet, "messages": msgs,
		"canControl": mayControlDisplays(me),
		"steward":    s.mayTalkToSteward(me),
		"modules":    enabledIDs(on),
		"apps":       st.Apps,
		"photos":     st.Photos,
		"stream":     st.Stream != "",
		"members":    s.callAs(me, s.handleMembers, "GET", "/api/members?since="+strconv.FormatInt(now.UnixMilli()*2, 10), nil, nil),
		"feed":       s.callAs(me, s.handleFeed, "GET", fmt.Sprintf("/api/feed?from=%d&to=%d", midnight.UnixMilli(), now.UnixMilli()+60000), nil, nil),
	}
	if on["calendar"] {
		board["events"] = s.callAs(me, s.handleEvents, "GET", fmt.Sprintf("/api/events?from=%d&to=%d", midnight.UnixMilli(), midnight.AddDate(0, 0, 4).UnixMilli()), nil, nil)
	}
	if on["routines"] {
		board["routines"] = s.callAs(me, s.handleRoutines, "GET", "/api/routines", nil, nil)
		board["routinesLater"] = s.routinesLater(now)
	}
	if on["todos"] {
		board["lists"] = s.callAs(me, s.handleLists, "GET", "/api/lists", nil, nil)
	}
	if on["meals"] {
		board["meals"] = s.callAs(me, s.handleMeals, "GET", "/api/meals", nil, nil)
	}
	if on["chores"] {
		board["chores"] = s.callAs(me, s.handleChores, "GET", "/api/chores", nil, nil)
	}
	writeJSON(w, http.StatusOK, board)
}

// POST /api/display/quiet {minutes}: start quiet time on every display (0 = until ended).
func (s *Server) handleQuietStart(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) {
		writeError(w, http.StatusForbidden, "parents start quiet time")
		return
	}
	var body struct {
		Minutes int `json:"minutes"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Minutes < 0 || body.Minutes > 12*60 {
		writeError(w, http.StatusBadRequest, "quiet time is up to 12 hours")
		return
	}
	now := time.Now()
	_ = s.store.updateDisplay(func(d *DisplayState) {
		d.QuietSince, d.QuietBy, d.QuietUntil = now.UnixMilli(), me.Name, 0
		if body.Minutes > 0 {
			d.QuietUntil = now.Add(time.Duration(body.Minutes) * time.Minute).UnixMilli()
		}
	})
	writeJSON(w, http.StatusOK, s.store.Display())
}

// DELETE /api/display/quiet: end quiet time (parents, or the display itself, from its remote).
func (s *Server) handleQuietEnd(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) && !me.Display {
		writeError(w, http.StatusForbidden, "parents end quiet time")
		return
	}
	_ = s.store.updateDisplay(func(d *DisplayState) { d.QuietSince, d.QuietUntil, d.QuietBy = 0, 0, "" })
	writeJSON(w, http.StatusOK, s.store.Display())
}

// POST /api/display/messages {text}: show a message on every display.
func (s *Server) handleDisplayMessage(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) {
		writeError(w, http.StatusForbidden, "parents send messages to the TV")
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.TrimSpace(body.Text)
	if text == "" || utf8.RuneCountInString(text) > 200 {
		writeError(w, http.StatusBadRequest, "a short message (200 characters at most)")
		return
	}
	m := DisplayMessage{ID: "m" + randomHex(5), From: me.Name, Text: text, Time: time.Now().UnixMilli()}
	_ = s.store.updateDisplay(func(d *DisplayState) { d.Messages = append(d.Messages, m) })
	writeJSON(w, http.StatusOK, m)
}

// DELETE /api/display/messages/{id}: take a message down (it's been seen).
func (s *Server) handleDisplayMessageDone(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) && !me.Display {
		writeError(w, http.StatusForbidden, "not allowed")
		return
	}
	id := r.PathValue("id")
	_ = s.store.updateDisplay(func(d *DisplayState) {
		out := d.Messages[:0]
		for _, m := range d.Messages {
			if m.ID != id {
				out = append(out, m)
			}
		}
		d.Messages = out
	})
	w.WriteHeader(http.StatusNoContent)
}

func enabledIDs(on map[string]bool) []string {
	out := []string{}
	for _, m := range allModules {
		if on[m.ID] {
			out = append(out, m.ID)
		}
	}
	return out
}

// routinesLater: the routines still to come today, in order, each with who it's for
// (empty: everyone), for the TV's "Later" line.
func (s *Server) routinesLater(now time.Time) []map[string]any {
	if s.routines == nil {
		return nil
	}
	members := s.store.Members()
	all := s.routines.all(members)
	titles := map[string]string{}
	for _, l := range all {
		titles[l.ID] = l.Title
	}
	day := weekdayKeys[now.Weekday()]
	mins := now.Hour()*60 + now.Minute()
	out := []map[string]any{}
	seen := map[string]int{}
	slots := s.routineSlots(all, members)
	slices.SortStableFunc(slots, func(a, b routineSlot) int { return a.at - b.at })
	for _, sl := range slots {
		if sl.at <= mins || (len(sl.days) > 0 && !containsStr(sl.days, day)) || titles[sl.routineID] == "" {
			continue
		}
		key := fmt.Sprintf("%s@%d", sl.routineID, sl.at)
		if i, ok := seen[key]; ok {
			who := out[i]["who"].([]string)
			for _, w := range sl.who {
				if !containsStr(who, w) {
					who = append(who, w)
				}
			}
			out[i]["who"] = who
			continue
		}
		seen[key] = len(out)
		out = append(out, map[string]any{"id": sl.routineID, "title": titles[sl.routineID], "at": fmt.Sprintf("%02d:%02d", sl.at/60, sl.at%60), "who": append([]string{}, sl.who...)})
	}
	return out
}

// ---- the TVs' launcher apps, and pictures for them from the console

var pkgRe = regexp.MustCompile(`^[A-Za-z0-9_.]{1,120}$`)

func (s *Server) displayImagePath(pkg string) string {
	return filepath.Join(filepath.Dir(s.store.path), "display", pkg+".img")
}

// PUT /api/display/apps {apps: [{pkg, label}]}: a TV says which apps are on its
// launcher, so the console can offer a picture for each. Apps are only added.
func (s *Server) handleDisplayApps(w http.ResponseWriter, r *http.Request, me *Member) {
	if !me.Display && !mayControlDisplays(me) {
		writeError(w, http.StatusForbidden, "not allowed")
		return
	}
	var body struct {
		Apps []DisplayApp `json:"apps"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	_ = s.store.updateDisplay(func(d *DisplayState) {
		for _, a := range body.Apps {
			a.Label = strings.TrimSpace(a.Label)
			if !pkgRe.MatchString(a.Pkg) || a.Label == "" || utf8.RuneCountInString(a.Label) > 60 {
				continue
			}
			i := slices.IndexFunc(d.Apps, func(x DisplayApp) bool { return x.Pkg == a.Pkg })
			if i >= 0 {
				d.Apps[i].Label = a.Label
			} else if len(d.Apps) < 60 {
				d.Apps = append(d.Apps, DisplayApp{Pkg: a.Pkg, Label: a.Label})
			}
		}
	})
	writeJSON(w, http.StatusOK, map[string]any{"apps": s.store.Display().Apps})
}

// PUT /api/display/apps/{pkg}/image (the picture's bytes): parents give an app's
// tile its own picture. DELETE puts the app's own banner back.
func (s *Server) handleDisplayAppImage(w http.ResponseWriter, r *http.Request, me *Member) {
	pkg := r.PathValue("pkg")
	// Parents, or the TV itself (choosing a picture from its own files).
	if !mayControlDisplays(me) && !me.Display {
		writeError(w, http.StatusForbidden, "parents choose the TV's pictures")
		return
	}
	if !pkgRe.MatchString(pkg) || !slices.ContainsFunc(s.store.Display().Apps, func(a DisplayApp) bool { return a.Pkg == pkg }) {
		writeError(w, http.StatusNotFound, "that app isn't on a TV")
		return
	}
	path := s.displayImagePath(pkg)
	var stamp int64
	if r.Method == http.MethodDelete {
		_ = os.Remove(path)
	} else {
		data, err := io.ReadAll(io.LimitReader(r.Body, 3<<20+1))
		if err != nil || len(data) == 0 || len(data) > 3<<20 {
			writeError(w, http.StatusBadRequest, "a picture of 3 MB at most")
			return
		}
		if t := http.DetectContentType(data); t != "image/png" && t != "image/jpeg" && t != "image/webp" && t != "image/gif" {
			writeError(w, http.StatusBadRequest, "a PNG, JPEG, WebP or GIF picture")
			return
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't save it")
			return
		}
		if err := os.WriteFile(path, data, 0o644); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't save it")
			return
		}
		stamp = time.Now().UnixMilli()
	}
	_ = s.store.updateDisplay(func(d *DisplayState) {
		for i := range d.Apps {
			if d.Apps[i].Pkg == pkg {
				d.Apps[i].Image = stamp
			}
		}
	})
	writeJSON(w, http.StatusOK, map[string]any{"apps": s.store.Display().Apps})
}

// GET /api/display/apps/{pkg}/image: the picture for an app's tile.
func (s *Server) handleDisplayAppImageGet(w http.ResponseWriter, r *http.Request, me *Member) {
	pkg := r.PathValue("pkg")
	if !pkgRe.MatchString(pkg) {
		http.NotFound(w, r)
		return
	}
	data, err := os.ReadFile(s.displayImagePath(pkg))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", http.DetectContentType(data))
	w.Header().Set("Cache-Control", "private, max-age=60")
	_, _ = w.Write(data)
}
