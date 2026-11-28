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
	"regexp"
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

// HomeProfile is a property's profile: the family's home in Home/Home.md (id "home"),
// and any others (a grandparent's flat, a cabin, a barn) in Home/<Name>.md.
type HomeProfile struct {
	ID             string            `json:"id"`
	Name           string            `json:"name"`
	Kind           string            `json:"kind"`           // house, apartment, cabin, outbuilding, other
	Custom         []homeJob         `json:"custom"`         // the family's own jobs for it
	Every          map[string]string `json:"every"`          // changed frequencies, by job key
	Tenure         string            `json:"tenure"`         // own, rent or ""
	Features       []string          `json:"features"`       // keys from homeFeatures
	Bins           string            `json:"bins"`           // weekday the bins go out (mon...sun), "" if none
	Recycling      string            `json:"recycling"`      // weekday for recycling, "" if none
	RecyclingEvery string            `json:"recyclingEvery"` // week or 2 weeks
}

const mainHome = "home"

var homeKinds = []string{"house", "apartment", "cabin", "outbuilding", "other"}

// The section in a property's note listing the family's own jobs: "- Title (every 3 months)".
const customJobsSection = "Our own jobs"

var customJobLine = regexp.MustCompile(`^[-*]\s+(.+?)\s+\(every ([^)]+)\)\s*$`)

func homeSlug(t string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(t) {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9':
			b.WriteRune(r)
		case b.Len() > 0 && !strings.HasSuffix(b.String(), "-"):
			b.WriteRune('-')
		}
	}
	return strings.Trim(b.String(), "-")
}

// homePaths finds every property's note, by id.
func (s *Server) homePaths() map[string]string {
	out := map[string]string{mainHome: filepath.Join(s.vault.dir, "Home", "Home.md")}
	entries, _ := os.ReadDir(filepath.Join(s.vault.dir, "Home"))
	for _, e := range entries {
		if e.IsDir() || e.Name() == "Home.md" || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		path := filepath.Join(s.vault.dir, "Home", e.Name())
		b, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		if id := frontValue(string(b), "home_id"); id != "" && frontValue(string(b), "type") == "home" {
			out[id] = path
		}
	}
	return out
}

func (s *Server) homePath() string { return s.homePaths()[mainHome] }

// homeProperties: each property's id, name and kind, the family's home first.
func (s *Server) homeProperties() []map[string]string {
	out := []map[string]string{}
	for id := range s.homePaths() {
		p := s.readHomeID(id)
		out = append(out, map[string]string{"id": id, "name": p.Name, "kind": p.Kind})
	}
	slices.SortFunc(out, func(a, b map[string]string) int {
		if a["id"] == mainHome {
			return -1
		}
		if b["id"] == mainHome {
			return 1
		}
		return strings.Compare(a["name"], b["name"])
	})
	return out
}

func (s *Server) readHome() HomeProfile { return s.readHomeID(mainHome) }

func (s *Server) readHomeID(id string) HomeProfile {
	p := HomeProfile{ID: id, Name: "Home", Kind: "house", Features: []string{}, RecyclingEvery: "week", Custom: []homeJob{}, Every: map[string]string{}}
	path, ok := s.homePaths()[id]
	if !ok {
		return p
	}
	if id != mainHome {
		p.Name = strings.TrimSuffix(filepath.Base(path), ".md")
	}
	n, err := readNote(path)
	if err != nil {
		return p
	}
	p.Tenure, _ = n.field("tenure")
	if k, _ := n.field("kind"); slices.Contains(homeKinds, k) {
		p.Kind = k
	}
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
	for _, l := range n.front {
		if k, v, ok := strings.Cut(l, ":"); ok && strings.HasPrefix(k, "every_") {
			if v = strings.TrimSpace(v); validEvery(v) && v != "" {
				p.Every[strings.TrimPrefix(k, "every_")] = v
			}
		}
	}
	for _, line := range strings.Split(readSections(n.body, []string{customJobsSection})[customJobsSection], "\n") {
		if m := customJobLine.FindStringSubmatch(strings.TrimSpace(line)); m != nil && validEvery(m[2]) {
			p.Custom = append(p.Custom, homeJob{Key: "custom-" + homeSlug(m[1]), Title: m[1], Every: m[2]})
		}
	}
	return p
}

func (s *Server) writeHome(p HomeProfile) error {
	if p.ID == "" {
		p.ID = mainHome
	}
	path, ok := s.homePaths()[p.ID]
	if !ok {
		path = filepath.Join(s.vault.dir, "Home", p.Name+".md")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	text := "# " + p.Name + "\n\nWhat this home has, and which days the bins go out. FamLogic keeps the upkeep chores for it in Upkeep.\n"
	if p.ID == mainHome {
		text = "# Home\n\nThe family's home: what it has, and which days the bins go out. FamLogic keeps the upkeep chores for it in Upkeep.\n"
	}
	if b, err := os.ReadFile(path); err == nil {
		text = string(b)
	}
	fields := [][2]string{{"type", "home"}, {"kind", p.Kind}, {"tenure", p.Tenure}, {"features", "[" + strings.Join(p.Features, ", ") + "]"},
		{"bins", p.Bins}, {"recycling", p.Recycling}, {"recycling_every", p.RecyclingEvery}}
	if p.ID != mainHome {
		fields = append(fields, [2]string{"home_id", p.ID})
	}
	// Changed frequencies: every_<job>: 6 months (and none left over from before).
	lines, body, _ := splitFront(text)
	var keep []string
	for _, l := range lines {
		if !strings.HasPrefix(l, "every_") {
			keep = append(keep, l)
		}
	}
	text = "---\n" + strings.Join(keep, "\n") + "\n---\n" + body
	keys := make([]string, 0, len(p.Every))
	for k := range p.Every {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	for _, k := range keys {
		fields = append(fields, [2]string{"every_" + k, p.Every[k]})
	}
	text = setFront(text, fields)
	// The family's own jobs, as a list in the note.
	lines2, body2, _ := splitFront(text)
	var custom []string
	for _, j := range p.Custom {
		custom = append(custom, "- "+j.Title+" (every "+j.Every+")")
	}
	body2 = writeSections(body2, map[string]string{customJobsSection: strings.Join(custom, "\n")}, []string{customJobsSection})
	return writeAtomic(path, []byte("---\n"+strings.Join(lines2, "\n")+"\n---\n"+body2))
}

// homeSource is the prefix of everything a property makes: "home:" for the family's
// home (as before), "home/<id>:" for the others.
func homeSource(id string) string {
	if id == "" || id == mainHome {
		return "home:"
	}
	return "home/" + id + ":"
}

// homeWants is every chore (and bin-day event) the profile calls for, by source.
type homeWant struct {
	Source, Title, Every, Day string // Day: the weekday bins and recycling go out
	Event                     bool   // also on the calendar
}

func homeWants(p HomeProfile) []homeWant {
	var out []homeWant
	pre := homeSource(p.ID)
	every := func(j homeJob) string {
		if e := p.Every[j.Key]; e != "" {
			return e
		}
		return j.Every
	}
	for _, f := range homeFeatures {
		if !slices.Contains(p.Features, f.Key) {
			continue
		}
		for _, j := range f.Jobs {
			out = append(out, homeWant{Source: pre + j.Key, Title: j.Title, Every: every(j)})
		}
	}
	for _, j := range p.Custom {
		out = append(out, homeWant{Source: pre + j.Key, Title: j.Title, Every: j.Every})
	}
	if slices.Contains(weekdayNames, p.Bins) {
		out = append(out, homeWant{Source: pre + "bins", Title: "Bins out", Every: p.Bins, Day: p.Bins, Event: true})
	}
	if slices.Contains(weekdayNames, p.Recycling) {
		every := p.Recycling
		if p.RecyclingEvery == "2 weeks" {
			every = "2 weeks"
		}
		out = append(out, homeWant{Source: pre + "recycling", Title: "Recycling out", Every: every, Day: p.Recycling, Event: true})
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
			if strings.HasPrefix(c.Source, homeSource(p.ID)) {
				have[c.Source] = c
			}
		}
	}
	forName := p.Name
	if p.ID == "" || p.ID == mainHome {
		forName = "Home"
	}
	wanted := map[string]bool{}
	for _, w := range homeWants(p) {
		wanted[w.Source] = true
		due := nextDue(w.Every, today())
		if w.Day != "" {
			due = nextWeekday(w.Day).Format(dayLayout)
		} else if d := lastDone[strings.TrimPrefix(w.Source, homeSource(p.ID))]; dateRe.MatchString(d) {
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
				title, every, src, forName := w.Title, w.Every, w.Source, forName
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
	return s.syncHomeEvents(p, members, forName)
}

// syncHomeEvents keeps bin days on the calendar (re-made when the day changes).
func (s *Server) syncHomeEvents(p HomeProfile, members []Member, forName string) error {
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
		if !strings.HasPrefix(ev.Source, homeSource(p.ID)) {
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
		rr, src := homeRRule(w), w.Source
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

// The property a request is about: ?property=<id> (the family's home if none).
func (s *Server) homeFor(r *http.Request) (string, bool) {
	id := r.URL.Query().Get("property")
	if id == "" {
		return mainHome, true
	}
	_, ok := s.homePaths()[id]
	return id, ok
}

// Frequencies offered when changing how often a job comes round (any "N days/weeks/months" works).
var homeEveryChoices = []string{"week", "2 weeks", "month", "2 months", "3 months", "6 months", "12 months", "24 months", "36 months"}

// GET /api/home?property=<id>: a property's profile, the family's properties, what a
// home can have, and the chores it keeps.
func (s *Server) handleHome(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.homeSetup(w, me) {
		return
	}
	id, ok := s.homeFor(r)
	if !ok {
		writeError(w, http.StatusNotFound, "no such property")
		return
	}
	jobs := []Chore{}
	if s.chores != nil {
		all, _ := s.chores.list(s.store.Members(), false)
		for _, c := range all {
			if strings.HasPrefix(c.Source, homeSource(id)) {
				jobs = append(jobs, c)
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"profile": s.readHomeID(id), "properties": s.homeProperties(), "catalog": homeFeatures,
		"jobs": jobs, "canEdit": canEditHome(me), "kinds": homeKinds, "everyChoices": homeEveryChoices})
}

// PUT /api/home?property=<id> {tenure, features, bins, recycling, recyclingEvery, kind,
// name, custom, every, lastDone?}: save the profile, then bring the chores and
// calendar in line.
func (s *Server) handleSetHome(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.homeSetup(w, me) {
		return
	}
	if !canEditHome(me) {
		writeError(w, http.StatusForbidden, "parents set up the home")
		return
	}
	id, ok := s.homeFor(r)
	if !ok {
		writeError(w, http.StatusNotFound, "no such property")
		return
	}
	var body struct {
		HomeProfile
		LastDone map[string]string `json:"lastDone"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	old := s.readHomeID(id)
	p := body.HomeProfile
	p.ID = id
	p.Name = old.Name // renaming is its own thing (the note's file name)
	if !slices.Contains(homeKinds, p.Kind) {
		p.Kind = old.Kind
	}
	if p.Every == nil && body.Every == nil {
		p.Every = old.Every
	}
	if p.Custom == nil {
		p.Custom = old.Custom
	}
	// The family's own jobs: a title and a valid frequency, each once.
	custom := []homeJob{}
	for _, j := range p.Custom {
		j.Title = strings.TrimSpace(strings.NewReplacer("\n", " ", "(", "", ")", "").Replace(j.Title))
		if j.Title == "" || len([]rune(j.Title)) > 80 {
			continue
		}
		if !validEvery(j.Every) || j.Every == "" {
			writeError(w, http.StatusBadRequest, "how often: a week, 3 months, 10 days…")
			return
		}
		j.Key = "custom-" + homeSlug(j.Title)
		if !slices.ContainsFunc(custom, func(x homeJob) bool { return x.Key == j.Key }) {
			custom = append(custom, j)
		}
	}
	p.Custom = custom
	for _, v := range p.Every {
		if !validEvery(v) || v == "" {
			writeError(w, http.StatusBadRequest, "how often: a week, 3 months, 10 days…")
			return
		}
	}
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

// POST /api/home/properties {name, kind}: another property to look after (a
// grandparent's flat, a cabin, a barn).
func (s *Server) handleAddProperty(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.homeSetup(w, me) {
		return
	}
	if !canEditHome(me) {
		writeError(w, http.StatusForbidden, "parents set up the home")
		return
	}
	var body struct {
		Name string `json:"name"`
		Kind string `json:"kind"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	name := cleanCardName(body.Name)
	if name == "" || strings.EqualFold(name, "Home") {
		writeError(w, http.StatusBadRequest, "give it a name (not just Home), like Gran's flat or The cabin")
		return
	}
	if _, err := os.Stat(filepath.Join(s.vault.dir, "Home", name+".md")); err == nil {
		writeError(w, http.StatusConflict, "there's already one called "+name)
		return
	}
	if !slices.Contains(homeKinds, body.Kind) {
		body.Kind = "house"
	}
	p := HomeProfile{ID: plainID(), Name: name, Kind: body.Kind, Features: []string{}, RecyclingEvery: "week"}
	if err := s.writeHome(p); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	writeJSON(w, http.StatusOK, s.readHomeID(p.ID))
}

// DELETE /api/home/properties/{id}: stop looking after a property; its chores and bin
// days retire. (The family's home stays.)
func (s *Server) handleDeleteProperty(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.homeSetup(w, me) {
		return
	}
	if !canEditHome(me) {
		writeError(w, http.StatusForbidden, "parents set up the home")
		return
	}
	id := r.PathValue("id")
	path, ok := s.homePaths()[id]
	if !ok || id == mainHome {
		writeError(w, http.StatusBadRequest, "the family's home stays; remove another property")
		return
	}
	p := s.readHomeID(id)
	empty := HomeProfile{ID: id, Name: p.Name, Features: []string{}}
	if err := s.syncHome(empty, nil); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't retire its chores: "+err.Error())
		return
	}
	journalBefore(path)
	if err := os.Remove(path); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't remove it")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
