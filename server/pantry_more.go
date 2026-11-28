package main

// Pantry, more: which meal slots the week shows, and the recipe book's changes
// (tags, who may change a recipe, removing one).

import (
	"net/http"
	"os"
	"slices"
	"strings"
	"unicode/utf8"
)

func (s *Server) shownSlots() []string {
	s.store.mu.RLock()
	defer s.store.mu.RUnlock()
	if len(s.store.d.MealSlots) == 0 {
		return []string{"Breakfast", "Lunch", "Dinner"}
	}
	return append([]string(nil), s.store.d.MealSlots...)
}

// PUT /api/meals/slots {slots}: which of the meal slots the week shows (grown-ups).
func (s *Server) handleMealSlots(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "meals", s.vault != nil) {
		return
	}
	if !isAdult(me) {
		writeError(w, http.StatusForbidden, "ask a grown-up")
		return
	}
	var body struct {
		Slots []string `json:"slots"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	keep := []string{}
	for _, sl := range mealSlots { // in the usual order
		if slices.Contains(body.Slots, sl) {
			keep = append(keep, sl)
		}
	}
	if len(keep) == 0 {
		writeError(w, http.StatusBadRequest, "keep at least one meal")
		return
	}
	s.store.mu.Lock()
	s.store.d.MealSlots = keep
	err := s.store.saveLocked()
	s.store.mu.Unlock()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"shownSlots": keep})
}

func canEditRecipe(me *Member, rc Recipe) bool {
	if me.Display || me.role() == RoleDependent {
		return false
	}
	if isAdult(me) {
		return !rc.Locked || me.role() == RoleParent || rc.By == me.ID
	}
	if rc.Locked {
		return rc.By == me.ID
	}
	return levelAtLeast(me.level("meals"), LevelFull) || (levelAtLeast(me.level("meals"), LevelAdd) && rc.By == me.ID)
}

func cleanTags(tags []string) []string {
	out := []string{}
	for _, t := range tags {
		t = strings.TrimSpace(strings.Trim(strings.NewReplacer(",", " ", "[", "", "]", "", "\n", " ").Replace(t), "#"))
		if t != "" && utf8.RuneCountInString(t) <= 30 && !slices.ContainsFunc(out, func(x string) bool { return strings.EqualFold(x, t) }) {
			out = append(out, t)
		}
		if len(out) == 12 {
			break
		}
	}
	return out
}

// PUT /api/recipes/{id} {title?, ingredients?, method?, serves?, tags?, locked?}
// DELETE /api/recipes/{id}
func (s *Server) handleRecipe(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.moduleOn(w, me, "meals", s.vault != nil) {
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
	if !canEditRecipe(me, *rc) {
		writeError(w, http.StatusForbidden, "only whoever added it, or a parent, can change this recipe")
		return
	}
	if r.Method == http.MethodDelete {
		journalBefore(rc.path)
		if err := os.Remove(rc.path); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't remove it")
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	var body struct {
		Title       *string   `json:"title"`
		Ingredients *[]string `json:"ingredients"`
		Method      *string   `json:"method"`
		Serves      *string   `json:"serves"`
		Tags        *[]string `json:"tags"`
		Locked      *bool     `json:"locked"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	n, err := readNote(rc.path)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "couldn't read it")
		return
	}
	fields := [][2]string{}
	if body.Serves != nil {
		fields = append(fields, [2]string{"serves", yamlString(strings.TrimSpace(*body.Serves))})
	}
	if body.Tags != nil {
		fields = append(fields, [2]string{"tags", "[" + strings.Join(cleanTags(*body.Tags), ", ") + "]"})
	}
	if body.Locked != nil && (me.role() == RoleParent || rc.By == me.ID) {
		v := "false"
		if *body.Locked {
			v = "true"
		}
		fields = append(fields, [2]string{"locked", v})
	}
	if len(fields) > 0 {
		if err := noteWriteFront(n, fields); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't save")
			return
		}
	}
	if body.Title != nil || body.Ingredients != nil || body.Method != nil {
		title := rc.Title
		if body.Title != nil {
			if t := strings.TrimSpace(*body.Title); t != "" && utf8.RuneCountInString(t) <= 80 {
				title = t
			}
		}
		ings := rc.Ingredients
		if body.Ingredients != nil {
			ings = *body.Ingredients
		}
		method := rc.Method
		if body.Method != nil {
			method = strings.ReplaceAll(*body.Method, "\r", "")
		}
		var b strings.Builder
		b.WriteString("# " + title + "\n\n## Ingredients\n")
		for _, it := range ings {
			if t := strings.TrimSpace(it); t != "" {
				b.WriteString("- [ ] " + t + "\n")
			}
		}
		b.WriteString("\n## Method\n" + strings.TrimSpace(method) + "\n")
		n.body = b.String()
		if err := noteWriteBody(n); err != nil {
			writeError(w, http.StatusInternalServerError, "couldn't save")
			return
		}
	}
	for _, x := range s.recipes() {
		if x.ID == rc.ID {
			writeJSON(w, http.StatusOK, x)
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
