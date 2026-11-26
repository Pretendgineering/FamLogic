package main

// Homestead: a simple profile of the family's home. Tick what the home has (a
// furnace, smoke alarms, gutters, a lawn...) and say which days the bins go out;
// Homestead keeps the matching repeating chores in Upkeep (and bin days on the
// calendar) by itself. Untick something and its chores retire. There's no
// separate log: each chore's own history (who did it, when) is the log.
//
// Everything it makes is marked with where it came from (source: home:<job>) and
// what it's for ([[Home]]), so it can be found, changed and retired reliably, and
// other modules (and Steward) can see what belongs to the home.

import (
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"
)

type homeJob struct {
	Key   string `json:"key"`
	Title string `json:"title"`
	Every string `json:"every"`
}

type homeFeature struct {
	Key  string    `json:"key"`
	Name string    `json:"name"`
	Jobs []homeJob `json:"jobs"`
}

// homeFeatures: what a home can have, and the upkeep each needs. Keys are stored: keep them stable.
var homeFeatures = []homeFeature{
	{"hvac", "Furnace or air conditioning", []homeJob{{"furnace-filter", "Change the furnace filter", "3 months"}, {"hvac-service", "Get the heating and cooling serviced", "12 months"}}},
	{"smoke", "Smoke and CO alarms", []homeJob{{"smoke-test", "Test the smoke and CO alarms", "month"}, {"smoke-batteries", "Change the alarm batteries", "12 months"}}},
	{"extinguisher", "Fire extinguishers", []homeJob{{"extinguisher-check", "Check the fire extinguishers", "12 months"}}},
	{"fridge", "Fridge with a water filter", []homeJob{{"fridge-filter", "Change the fridge water filter", "6 months"}, {"fridge-coils", "Clean the fridge coils", "12 months"}}},
	{"dishwasher", "Dishwasher", []homeJob{{"dishwasher-filter", "Clean the dishwasher filter", "month"}}},
	{"rangehood", "Range hood", []homeJob{{"rangehood-filter", "Clean the range hood filter", "3 months"}}},
	{"dryer", "Clothes dryer", []homeJob{{"dryer-vent", "Clean the dryer vent", "12 months"}}},
	{"waterheater", "Water heater", []homeJob{{"waterheater-flush", "Flush the water heater", "12 months"}}},
	{"softener", "Water softener", []homeJob{{"softener-salt", "Top up the water softener salt", "month"}}},
	{"sump", "Sump pump", []homeJob{{"sump-test", "Test the sump pump", "3 months"}}},
	{"septic", "Septic tank", []homeJob{{"septic-pump", "Have the septic tank pumped", "36 months"}}},
	{"chimney", "Fireplace or wood stove", []homeJob{{"chimney-sweep", "Have the chimney swept", "12 months"}}},
	{"gutters", "Gutters", []homeJob{{"gutters-clean", "Clean the gutters", "6 months"}}},
	{"lawn", "A lawn", []homeJob{{"lawn-mow", "Mow the lawn", "week"}}},
	{"garagedoor", "Garage door", []homeJob{{"garagedoor-lube", "Oil the garage door", "12 months"}}},
}

// HomeProfile is the home's profile, kept in Home/Home.md.
type HomeProfile struct {
	Tenure         string   `json:"tenure"`         // own, rent or ""
	Features       []string `json:"features"`       // keys from homeFeatures
	Bins           string   `json:"bins"`           // weekday the bins go out (mon...sun), "" if none
	Recycling      string   `json:"recycling"`      // weekday for recycling, "" if none
	RecyclingEvery string   `json:"recyclingEvery"` // week or 2 weeks
}

func (s *Server) homePath() string { return filepath.Join(s.vault.dir, "Home", "Home.md") }

func (s *Server) readHome() HomeProfile {
	p := HomeProfile{Features: []string{}, RecyclingEvery: "week"}
	n, err := readNote(s.homePath())
	if err != nil {
		return p
	}
	p.Tenure, _ = n.field("tenure")
	one, list := n.field("features")
	if list == nil && one != "" {
		list = []string{one}
	}
	for _, f := range list {
		if f = strings.TrimSpace(f); f != "" {
			p.Features = append(p.Features, f)
		}
	}
	p.Bins, _ = n.field("bins")
	p.Recycling, _ = n.field("recycling")
	if e, _ := n.field("recycling_every"); e != "" {
		p.RecyclingEvery = e
	}
	return p
}

func (s *Server) writeHome(p HomeProfile) error {
	path := s.homePath()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	text := "# Home\n\nThe family's home: what it has, and which days the bins go out. FamLogic keeps the upkeep chores for it in Upkeep.\n"
	if b, err := os.ReadFile(path); err == nil {
		text = string(b)
	}
	fields := [][2]string{{"type", "home"}, {"tenure", p.Tenure}, {"features", "[" + strings.Join(p.Features, ", ") + "]"},
		{"bins", p.Bins}, {"recycling", p.Recycling}, {"recycling_every", p.RecyclingEvery}}
	return writeAtomic(path, []byte(setFront(text, fields)))
}

// homeWants is every chore (and bin-day event) the profile calls for, by source.
type homeWant struct {
	Source, Title, Every, Day string // Day: the weekday bins and recycling go out
	Event                     bool   // also on the calendar
}

func homeWants(p HomeProfile) []homeWant {
	var out []homeWant
	for _, f := range homeFeatures {
		if !slices.Contains(p.Features, f.Key) {
			continue
		}
		for _, j := range f.Jobs {
			out = append(out, homeWant{Source: "home:" + j.Key, Title: j.Title, Every: j.Every})
		}
	}
	if slices.Contains(weekdayNames, p.Bins) {
		out = append(out, homeWant{Source: "home:bins", Title: "Bins out", Every: p.Bins, Day: p.Bins, Event: true})
	}
	if slices.Contains(weekdayNames, p.Recycling) {
		every := p.Recycling
		if p.RecyclingEvery == "2 weeks" {
			every = "2 weeks"
		}
		out = append(out, homeWant{Source: "home:recycling", Title: "Recycling out", Every: every, Day: p.Recycling, Event: true})
	}
	return out
}

// nextWeekday is the next date (today or later) falling on day (mon...sun).
func nextWeekday(day string) time.Time {
	want := time.Weekday(slices.Index(weekdayNames, day))
	t := time.Now()
	for t.Weekday() != want {
		t = t.AddDate(0, 0, 1)
	}
	return t
}

// syncHome brings Upkeep and Datebook in line with the profile: makes what's
// missing, updates what changed, retires what's no longer wanted. lastDone
// (optional, by job key) sets when a new job is next due; otherwise it's due one
// interval from today, so a new home doesn't flood today's chores.
func (s *Server) syncHome(p HomeProfile, lastDone map[string]string) error {
	members := s.store.Members()
	have := map[string]Chore{}
	if s.chores != nil {
		all, err := s.chores.list(members, false)
		if err != nil {
			return err
		}
		for _, c := range all {
			if strings.HasPrefix(c.Source, "home:") {
				have[c.Source] = c
			}
		}
	}
	wanted := map[string]bool{}
	for _, w := range homeWants(p) {
		wanted[w.Source] = true
		due := nextDue(w.Every, today())
		if w.Day != "" {
			due = nextWeekday(w.Day).Format(dayLayout)
		} else if d := lastDone[strings.TrimPrefix(w.Source, "home:")]; dateRe.MatchString(d) {
			due = nextDue(w.Every, d)
		}
		if s.chores != nil {
			if c, ok := have[w.Source]; ok {
				if c.Every != w.Every { // bin day moved, or recycling went fortnightly
					every := w.Every
					if _, err := s.chores.update(c.ID, ChoreInput{Every: &every, Due: &due}, members); err != nil {
						return err
					}
				}
			} else {
				title, every, src, forName := w.Title, w.Every, w.Source, "Home"
				if _, err := s.chores.create(ChoreInput{Title: &title, Every: &every, Due: &due, Source: &src, For: &forName, Who: &[]string{}}, members); err != nil {
					return err
				}
			}
		}
	}
	for src, c := range have {
		if !wanted[src] {
			if err := s.chores.archive(c.ID); err != nil {
				return err
			}
		}
	}
	return s.syncHomeEvents(p, members)
}

// syncHomeEvents keeps bin days on the calendar (re-made when the day changes).
func (s *Server) syncHomeEvents(p HomeProfile, members []Member) error {
	if s.cal == nil {
		return nil
	}
	want := map[string]homeWant{}
	for _, w := range homeWants(p) {
		if w.Event {
			want[w.Source] = w
		}
	}
	for _, ev := range s.cal.all(members) {
		if !strings.HasPrefix(ev.Source, "home:") {
			continue
		}
		w, ok := want[ev.Source]
		if ok && ev.RRule == homeRRule(w) {
			delete(want, ev.Source) // already right
			continue
		}
		if err := s.cal.archive(ev.ID); err != nil {
			return err
		}
	}
	for _, w := range want {
		title := strings.TrimSuffix(w.Title, " out") + " day"
		start := nextWeekday(w.Day).Format(dayLayout)
		rr, src, forName := homeRRule(w), w.Source, "Home"
		if _, err := s.cal.save("", EventInput{Title: &title, Start: &start, RRule: &rr, Source: &src, For: &forName, Who: &[]string{}}, members); err != nil {
			return err
		}
	}
	return nil
}

func homeRRule(w homeWant) string {
	r := "FREQ=WEEKLY;BYDAY=" + strings.ToUpper(w.Day[:2])
	if w.Every == "2 weeks" {
		r = "FREQ=WEEKLY;INTERVAL=2;BYDAY=" + strings.ToUpper(w.Day[:2])
	}
	return r
}

// ---- HTTP

func (s *Server) homeSetup(w http.ResponseWriter, me *Member) bool {
	if s.vault == nil {
		writeError(w, http.StatusServiceUnavailable, "Homestead needs the notes vault, which isn't available on this server")
		return false
	}
	if !s.store.Modules().Enabled["homestead"] || me.level("homestead") == LevelOff {
		writeError(w, http.StatusForbidden, "Homestead isn't switched on for you")
		return false
	}
	return true
}

func canEditHome(me *Member) bool { return me.ID == "admin" || me.role() == RoleParent }

// GET /api/home: the profile, what a home can have, and the chores it keeps.
func (s *Server) handleHome(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.homeSetup(w, me) {
		return
	}
	jobs := []Chore{}
	if s.chores != nil {
		all, _ := s.chores.list(s.store.Members(), false)
		for _, c := range all {
			if strings.HasPrefix(c.Source, "home:") {
				jobs = append(jobs, c)
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"profile": s.readHome(), "catalog": homeFeatures, "jobs": jobs, "canEdit": canEditHome(me)})
}

// PUT /api/home {tenure, features, bins, recycling, recyclingEvery, lastDone?}: save the
// profile, then bring the chores and calendar in line.
func (s *Server) handleSetHome(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.homeSetup(w, me) {
		return
	}
	if !canEditHome(me) {
		writeError(w, http.StatusForbidden, "parents set up the home")
		return
	}
	var body struct {
		HomeProfile
		LastDone map[string]string `json:"lastDone"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	p := body.HomeProfile
	keep := []string{}
	for _, k := range p.Features {
		if slices.ContainsFunc(homeFeatures, func(f homeFeature) bool { return f.Key == k }) && !slices.Contains(keep, k) {
			keep = append(keep, k)
		}
	}
	p.Features = keep
	p.Bins, p.Recycling = strings.ToLower(strings.TrimSpace(p.Bins)), strings.ToLower(strings.TrimSpace(p.Recycling))
	if (p.Bins != "" && !slices.Contains(weekdayNames, p.Bins)) || (p.Recycling != "" && !slices.Contains(weekdayNames, p.Recycling)) {
		writeError(w, http.StatusBadRequest, "bin days are a day of the week (mon...sun)")
		return
	}
	if p.RecyclingEvery != "2 weeks" {
		p.RecyclingEvery = "week"
	}
	if p.Tenure != "own" && p.Tenure != "rent" {
		p.Tenure = ""
	}
	if err := s.writeHome(p); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save the home")
		return
	}
	if err := s.syncHome(p, body.LastDone); err != nil {
		writeError(w, http.StatusInternalServerError, "saved, but couldn't update the chores: "+err.Error())
		return
	}
	s.handleHome(w, r, me)
}
