package main

// Rhythms: routines (checklists that come round again: Morning, After school,
// Bedtime) and which one is current for each person now. A routine is a note in
// Routines/ (type: routine), with when it starts and on which days:
//
//	---
//	type: routine
//	who: ["[[Sam]]", "[[Mia]]"]
//	starts: "19:30"
//	days: [mon, tue, wed, thu, fri]
//	---
//	# Bedtime
//
//	- [ ] Bath
//	- [ ] Brush teeth
//
// Rhythm notes in Rhythms/ (type: rhythm, "- 07:00 [[Morning]]" lines) can set
// the times too. When a routine comes round again, its ticks are cleared.
//
// Pantry: the week's meals (Meals/2026-W49.md: "## Mon 2026-11-30" then
// "- Dinner: Lasagne") and recipes (Meals/Recipes/, with "## Ingredients" as task
// lines), whose ingredients can go straight onto the shopping list.

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

var weekdayKeys = []string{"sun", "mon", "tue", "wed", "thu", "fri", "sat"}

// routineSlot: a routine coming round at a time on some days.
type routineSlot struct {
	routineID string
	at        int // minutes after midnight
	days      []string
	who       []string // member ids (empty: everyone)
}

var rhythmLineRe = regexp.MustCompile(`^\s*-\s*(\d{1,2}:\d{2})\s+\[\[([^\]|]+)`)

// slots: every time a routine comes round, from routines' own "starts" and from rhythm notes.
func (s *Server) routineSlots(routines []List, members []Member) []routineSlot {
	out := []routineSlot{}
	byTitle := map[string]List{}
	for _, r := range routines {
		byTitle[strings.ToLower(r.Title)] = r
		if r.Starts != "" {
			out = append(out, routineSlot{r.ID, clockMinutes(r.Starts), lower(r.Days), r.Who})
		}
	}
	dir := filepath.Join(s.vault.dir, "Rhythms")
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		if t, _ := n.field("type"); t != "rhythm" {
			continue
		}
		_, days := n.field("days")
		_, whoRaw := n.field("who")
		who := []string{}
		for _, w := range whoRaw {
			if m := memberByName(members, linkName(w)); m != nil {
				who = append(who, m.ID)
			}
		}
		for _, line := range strings.Split(n.body, "\n") {
			m := rhythmLineRe.FindStringSubmatch(line)
			if m == nil {
				continue
			}
			if r, ok := byTitle[strings.ToLower(strings.TrimSpace(m[2]))]; ok {
				w := who
				if len(w) == 0 {
					w = r.Who
				}
				out = append(out, routineSlot{r.ID, clockMinutes(m[1]), lower(days), w})
			}
		}
	}
	return out
}

func lower(xs []string) []string {
	out := make([]string, 0, len(xs))
	for _, x := range xs {
		out = append(out, strings.ToLower(strings.TrimSpace(x))[:min(3, len(strings.TrimSpace(x)))])
	}
	return out
}

// currentRoutines: for each person, the routine that's current now (the latest
// one to have started today), and when it started.
func (s *Server) currentRoutines(routines []List, members []Member, now time.Time) map[string]struct {
	ID    string
	Since time.Time
} {
	slots := s.routineSlots(routines, members)
	day := weekdayKeys[now.Weekday()]
	mins := now.Hour()*60 + now.Minute()
	out := map[string]struct {
		ID    string
		Since time.Time
	}{}
	best := map[string]int{}
	for _, m := range members {
		if m.role() == RoleDependent || m.Display {
			continue
		}
		best[m.ID] = -1
		for _, sl := range slots {
			if sl.at > mins || (len(sl.days) > 0 && !containsStr(sl.days, day)) || (len(sl.who) > 0 && !containsStr(sl.who, m.ID)) {
				continue
			}
			if sl.at > best[m.ID] {
				best[m.ID] = sl.at
				out[m.ID] = struct {
					ID    string
					Since time.Time
				}{sl.routineID, time.Date(now.Year(), now.Month(), now.Day(), sl.at/60, sl.at%60, 0, 0, now.Location())}
			}
		}
	}
	return out
}

// resetRoutines clears a routine's ticks when it comes round again.
func (s *Server) resetRoutines(routines []List, members []Member, now time.Time) {
	for _, cur := range s.currentRoutines(routines, members, now) {
		n, _, err := s.routines.find(cur.ID)
		if err != nil {
			continue
		}
		last, _ := n.field("famlogic_reset")
		stamp := cur.Since.Format(timeLayout)
		if last >= stamp {
			continue
		}
		lines := strings.Split(n.body, "\n")
		for i, l := range lines {
			if m := taskLineRe.FindStringSubmatch(l); m != nil && m[2] != " " {
				lines[i] = m[1] + "- [ ] " + strings.TrimSpace(doneMarkRe.ReplaceAllString(m[3], ""))
			}
		}
		n.body = strings.Join(lines, "\n")
		_ = noteWriteBody(n)
		_ = noteWriteFront(n, [][2]string{{"famlogic_reset", stamp}})
	}
}

// GET /api/routines: every routine, and which is current for whom.
func (s *Server) handleRoutines(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "routines", s.routines != nil) {
		return
	}
	members := s.store.Members()
	all := s.routines.all(members)
	s.resetRoutines(all, members, time.Now())
	all = s.routines.all(members)
	out := []List{}
	for _, l := range all {
		if canSeeList(me, l) {
			// Each item's picture, for children who don't read yet.
			items := make([]ListItem, len(l.Items))
			for i, it := range l.Items {
				it.Emoji, it.Text = routineEmoji(it.Text)
				items[i] = it
			}
			l.Items = items
			out = append(out, l)
		}
	}
	now := map[string]string{}
	for id, cur := range s.currentRoutines(all, members, time.Now()) {
		now[id] = cur.ID
	}
	writeJSON(w, http.StatusOK, map[string]any{"routines": out, "now": now, "canEdit": isAdult(me) || levelAtLeast(me.level("routines"), LevelFull)})
}

// POST /api/routines {title, who, starts, days, items}: a parent makes a routine.
func (s *Server) handleCreateRoutine(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "routines", s.routines != nil) {
		return
	}
	if !isAdult(me) && !levelAtLeast(me.level("routines"), LevelFull) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	var body struct {
		Title  string   `json:"title"`
		Who    []string `json:"who"`
		Starts string   `json:"starts"`
		Days   []string `json:"days"`
		Items  []string `json:"items"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	title := strings.TrimSpace(body.Title)
	if title == "" || utf8.RuneCountInString(title) > 60 {
		writeError(w, http.StatusBadRequest, "a routine needs a name")
		return
	}
	if body.Starts != "" && !clockOnly.MatchString(body.Starts) {
		writeError(w, http.StatusBadRequest, "starts is a time, HH:MM")
		return
	}
	members := s.store.Members()
	l, err := s.routines.create(title, "routine", body.Who, members)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	n, _, _ := s.routines.find(l.ID)
	fields := [][2]string{}
	if body.Starts != "" {
		fields = append(fields, [2]string{"starts", yamlString(body.Starts)})
	}
	if len(body.Days) > 0 {
		fields = append(fields, [2]string{"days", "[" + strings.Join(lower(body.Days), ", ") + "]"})
	}
	if len(fields) > 0 {
		noteWriteFront(n, fields)
	}
	for _, it := range body.Items {
		if t := strings.TrimSpace(it); t != "" {
			l, _ = s.routines.addItem(l.ID, t, "", "", "", members)
		}
	}
	l, _ = s.routines.get(l.ID, members)
	writeJSON(w, http.StatusOK, l)
}

// POST /api/routines/{id}/items/{item} {done}: anyone it's for ticks.
func (s *Server) handleTickRoutine(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "routines", s.routines != nil) {
		return
	}
	l, err := s.routines.get(r.PathValue("id"), s.store.Members())
	if err != nil || !canSeeList(me, l) {
		writeError(w, http.StatusNotFound, "no such routine")
		return
	}
	var body struct {
		Done bool `json:"done"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	got, err := s.routines.setDone(l.ID, r.PathValue("item"), body.Done, s.store.Members())
	if err != nil {
		writeError(w, http.StatusNotFound, "that step isn't there any more")
		return
	}
	writeJSON(w, http.StatusOK, got)
}

func (s *Server) handleArchiveRoutine(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "routines", s.routines != nil) {
		return
	}
	if !isAdult(me) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	if err := s.routines.archive(r.PathValue("id")); err != nil {
		writeError(w, http.StatusNotFound, "no such routine")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// moduleOn checks a vault module is available and on for this person.
func (s *Server) moduleOn(w http.ResponseWriter, me *Member, module string, ready bool) bool {
	if !ready {
		writeError(w, http.StatusServiceUnavailable, "this needs the notes vault, which isn't available on this server")
		return false
	}
	if !s.store.Modules().Enabled[module] || me.level(module) == LevelOff {
		writeError(w, http.StatusForbidden, "that isn't switched on for you")
		return false
	}
	return true
}

// ---- Pantry: meals and recipes

type MealDay struct {
	Date  string            `json:"date"`
	Meals map[string]string `json:"meals"` // Breakfast, Lunch, Dinner… → dish
}

type Recipe struct {
	ID          string   `json:"id"`
	Title       string   `json:"title"`
	Ingredients []string `json:"ingredients"`
	Method      string   `json:"method,omitempty"`
	Serves      string   `json:"serves,omitempty"`
}

var mealLineRe = regexp.MustCompile(`^\s*-\s*([^:]{1,40}):\s*(.*)$`)

func isoWeek(t time.Time) string {
	y, w := t.ISOWeek()
	return fmt.Sprintf("%d-W%02d", y, w)
}

// weekStart: the Monday of an ISO week like 2026-W49.
func weekStart(week string) (time.Time, bool) {
	var y, w int
	if _, err := fmt.Sscanf(week, "%d-W%d", &y, &w); err != nil || w < 1 || w > 53 {
		return time.Time{}, false
	}
	jan4 := time.Date(y, 1, 4, 0, 0, 0, 0, time.Local)
	monday := jan4.AddDate(0, 0, -((int(jan4.Weekday()) + 6) % 7))
	return monday.AddDate(0, 0, (w-1)*7), true
}

func (s *Server) mealsPath(week string) string {
	return filepath.Join(s.vault.dir, "Meals", week+".md")
}

func (s *Server) readWeek(week string) []MealDay {
	start, _ := weekStart(week)
	days := make([]MealDay, 7)
	for i := range days {
		days[i] = MealDay{Date: start.AddDate(0, 0, i).Format(dayLayout), Meals: map[string]string{}}
	}
	n, err := readNote(s.mealsPath(week))
	if err != nil {
		return days
	}
	cur := -1
	for _, line := range strings.Split(n.body, "\n") {
		if h, ok := strings.CutPrefix(line, "## "); ok {
			cur = -1
			for i, d := range days {
				if strings.Contains(h, d.Date) {
					cur = i
				}
			}
			continue
		}
		if m := mealLineRe.FindStringSubmatch(line); m != nil && cur >= 0 {
			days[cur].Meals[strings.TrimSpace(m[1])] = strings.TrimSpace(strings.NewReplacer("[[", "", "]]", "").Replace(m[2]))
		}
	}
	return days
}

func (s *Server) writeWeek(week string, days []MealDay) error {
	var b strings.Builder
	_, w, _ := strings.Cut(week, "-W")
	fmt.Fprintf(&b, "---\ntype: meals\nweek: %s\n---\n# Meals, week %s\n", week, strings.TrimLeft(w, "0"))
	order := []string{"Breakfast", "Lunch", "Dinner"}
	for _, d := range days {
		t, _ := time.Parse(dayLayout, d.Date)
		fmt.Fprintf(&b, "\n## %s %s\n", t.Format("Mon"), d.Date)
		keys := []string{}
		for k := range d.Meals {
			keys = append(keys, k)
		}
		sort.Slice(keys, func(i, j int) bool {
			ai, bi := indexOf(order, keys[i]), indexOf(order, keys[j])
			if ai != bi {
				return ai < bi
			}
			return keys[i] < keys[j]
		})
		for _, k := range keys {
			if d.Meals[k] != "" {
				fmt.Fprintf(&b, "- %s: %s\n", k, d.Meals[k])
			}
		}
	}
	if err := os.MkdirAll(filepath.Dir(s.mealsPath(week)), 0o755); err != nil {
		return err
	}
	return writeAtomic(s.mealsPath(week), []byte(b.String()))
}

func indexOf(xs []string, x string) int {
	for i, y := range xs {
		if y == x {
			return i
		}
	}
	return len(xs)
}

func (s *Server) recipes() []Recipe {
	dir := filepath.Join(s.vault.dir, "Meals", "Recipes")
	entries, _ := os.ReadDir(dir)
	out := []Recipe{}
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		n, err := readNote(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		if t, _ := n.field("type"); t != "recipe" {
			continue
		}
		id, _ := n.field("famlogic_id")
		if id == "" {
			id = "r" + randomHex(5)
			noteWriteFront(n, [][2]string{{"famlogic_id", id}})
		}
		rc := Recipe{ID: id, Title: strings.TrimSuffix(e.Name(), ".md"), Ingredients: []string{}}
		if t := noteTitle(n); t != "" {
			rc.Title = t
		}
		rc.Serves, _ = n.field("serves")
		section := ""
		var method []string
		for _, line := range strings.Split(n.body, "\n") {
			if h, ok := strings.CutPrefix(line, "## "); ok {
				section = strings.ToLower(strings.TrimSpace(h))
				continue
			}
			switch section {
			case "ingredients":
				if m := taskLineRe.FindStringSubmatch(line); m != nil {
					rc.Ingredients = append(rc.Ingredients, strings.TrimSpace(m[3]))
				} else if t := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "- ")); t != "" {
					rc.Ingredients = append(rc.Ingredients, t)
				}
			case "method":
				method = append(method, line)
			}
		}
		rc.Method = strings.TrimSpace(strings.Join(method, "\n"))
		out = append(out, rc)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Title) < strings.ToLower(out[j].Title) })
	return out
}

// GET /api/meals?week=2026-W49 (default: this week), with the recipes.
func (s *Server) handleMeals(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "meals", s.vault != nil) {
		return
	}
	week := r.URL.Query().Get("week")
	if _, ok := weekStart(week); !ok {
		week = isoWeek(time.Now())
	}
	writeJSON(w, http.StatusOK, map[string]any{"week": week, "days": s.readWeek(week), "recipes": s.recipes(),
		"canEdit": isAdult(me) || levelAtLeast(me.level("meals"), LevelAdd)})
}

// PUT /api/meals/{week} {date, meal, dish}: set (or with "" clear) one meal.
func (s *Server) handleSetMeal(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "meals", s.vault != nil) {
		return
	}
	if !isAdult(me) && !levelAtLeast(me.level("meals"), LevelAdd) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	week := r.PathValue("week")
	if _, ok := weekStart(week); !ok {
		writeError(w, http.StatusBadRequest, "a week looks like 2026-W49")
		return
	}
	var body struct {
		Date string `json:"date"`
		Meal string `json:"meal"`
		Dish string `json:"dish"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	meal := strings.TrimSpace(body.Meal)
	if meal == "" || strings.ContainsAny(meal, ":\n") || utf8.RuneCountInString(meal) > 30 || utf8.RuneCountInString(body.Dish) > 100 {
		writeError(w, http.StatusBadRequest, "a meal (like Dinner) and a dish of up to 100 characters")
		return
	}
	days := s.readWeek(week)
	found := false
	for i := range days {
		if days[i].Date == body.Date {
			days[i].Meals[meal] = strings.Join(strings.Fields(body.Dish), " ")
			found = true
		}
	}
	if !found {
		writeError(w, http.StatusBadRequest, "that day isn't in that week")
		return
	}
	if err := s.writeWeek(week, days); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"week": week, "days": days})
}

// POST /api/recipes {title, ingredients, method, serves}
func (s *Server) handleAddRecipe(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "meals", s.vault != nil) {
		return
	}
	if !isAdult(me) && !levelAtLeast(me.level("meals"), LevelFull) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	var body struct {
		Title       string   `json:"title"`
		Ingredients []string `json:"ingredients"`
		Method      string   `json:"method"`
		Serves      string   `json:"serves"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	title := strings.TrimSpace(body.Title)
	if title == "" || utf8.RuneCountInString(title) > 80 {
		writeError(w, http.StatusBadRequest, "a recipe needs a name")
		return
	}
	dir := filepath.Join(s.vault.dir, "Meals", "Recipes")
	os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, noteName(title)+".md")
	for i := 2; fileExists(path); i++ {
		path = filepath.Join(dir, fmt.Sprintf("%s (%d).md", noteName(title), i))
	}
	var b strings.Builder
	id := "r" + randomHex(5)
	fmt.Fprintf(&b, "---\ntype: recipe\nfamlogic_id: %s\n", id)
	if body.Serves != "" {
		fmt.Fprintf(&b, "serves: %s\n", yamlString(body.Serves))
	}
	fmt.Fprintf(&b, "---\n# %s\n\n## Ingredients\n", title)
	for _, it := range body.Ingredients {
		if t := strings.TrimSpace(it); t != "" {
			fmt.Fprintf(&b, "- [ ] %s\n", t)
		}
	}
	fmt.Fprintf(&b, "\n## Method\n%s\n", strings.TrimSpace(body.Method))
	if err := writeAtomic(path, []byte(b.String())); err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	for _, rc := range s.recipes() {
		if rc.ID == id {
			writeJSON(w, http.StatusOK, rc)
			return
		}
	}
	writeError(w, http.StatusInternalServerError, "couldn't read it back")
}

// POST /api/recipes/{id}/shop: its ingredients go on the shopping list (made if need be).
func (s *Server) handleShopRecipe(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "meals", s.vault != nil) || s.lists == nil {
		return
	}
	var rc *Recipe
	for _, x := range s.recipes() {
		if x.ID == r.PathValue("id") {
			x := x
			rc = &x
		}
	}
	if rc == nil {
		writeError(w, http.StatusNotFound, "no such recipe")
		return
	}
	members := s.store.Members()
	var shop *List
	for _, l := range s.lists.all(members) {
		if l.Kind == "list" && strings.EqualFold(l.Title, "Shopping") {
			l := l
			shop = &l
		}
	}
	if shop == nil {
		l, err := s.lists.create("Shopping", "list", nil, members)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't make the shopping list")
			return
		}
		shop = &l
	}
	have := map[string]bool{}
	for _, it := range shop.Items {
		if !it.Done {
			have[strings.ToLower(it.Text)] = true
		}
	}
	added := 0
	for _, ing := range rc.Ingredients {
		if !have[strings.ToLower(ing)] {
			s.lists.addItem(shop.ID, ing, "", "", "", members)
			added++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"added": added, "list": shop.ID})
}
