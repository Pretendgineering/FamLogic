package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRoutinesAndMeals(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	vault := filepath.Join(filepath.Dir(e.dataFile()), "vault")
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chat": true, "todos": true, "routines": true, "meals": true}, "setupDone": true})

	// A routine that started a minute ago is current for the kid.
	start := time.Now().Add(-time.Minute).Format("15:04")
	if time.Now().Hour() == 0 && time.Now().Minute() == 0 {
		t.Skip("midnight")
	}
	st, r := e.status("POST", "/api/routines", f.mom, map[string]any{"title": "Bedtime", "who": []string{f.kidID}, "starts": start, "items": []string{"Bath", "Brush teeth"}})
	if st != 200 {
		t.Fatalf("routine: %d %v", st, r)
	}
	_, got := e.status("GET", "/api/routines", f.kid, nil)
	if got["now"].(map[string]any)[f.kidID] != r["id"] {
		t.Fatalf("current: %v", got["now"])
	}
	item := r["items"].([]any)[0].(map[string]any)["id"].(string)
	if st, _ := e.status("POST", "/api/routines/"+r["id"].(string)+"/items/"+item, f.kid, map[string]any{"done": true}); st != 200 {
		t.Fatalf("tick: %d", st)
	}
	// Meals and recipes; a recipe's ingredients go on the shopping list.
	week := isoWeek(time.Now())
	start2, _ := weekStart(week)
	if st, _ := e.status("PUT", "/api/meals/"+week, f.mom, map[string]any{"date": start2.Format("2006-01-02"), "meal": "Dinner", "dish": "Lasagne"}); st != 200 {
		t.Fatalf("meal: %d", st)
	}
	b, _ := os.ReadFile(filepath.Join(vault, "Meals", week+".md"))
	if !strings.Contains(string(b), "- Dinner: Lasagne") {
		t.Fatalf("week:\n%s", b)
	}
	_, rc := e.status("POST", "/api/recipes", f.mom, map[string]any{"title": "Lasagne", "ingredients": []string{"Pasta sheets", "Mince"}, "method": "Layer and bake."})
	if st, out := e.status("POST", "/api/recipes/"+rc["id"].(string)+"/shop", f.mom, nil); st != 200 || out["added"] != float64(2) {
		t.Fatalf("shop: %d %v", st, out)
	}
	b, _ = os.ReadFile(filepath.Join(vault, "Lists", "Shopping.md"))
	if !strings.Contains(string(b), "- [ ] Pasta sheets") {
		t.Fatalf("shopping:\n%s", b)
	}
}
