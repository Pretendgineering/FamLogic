package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLists(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	vault := filepath.Join(filepath.Dir(e.dataFile()), "vault")
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chat": true, "todos": true}, "setupDone": true})

	st, l := e.status("POST", "/api/lists", f.mom, map[string]any{"title": "Shopping"})
	if st != 200 {
		t.Fatalf("create: %d %v", st, l)
	}
	id := l["id"].(string)
	if st, _ := e.status("POST", "/api/lists", f.kid, map[string]any{"title": "Mine"}); st != 403 {
		t.Fatalf("a younger child can't make shared lists: %d", st)
	}
	st, l = e.status("POST", "/api/lists/"+id+"/items", f.mom, map[string]any{"text": "Milk", "who": f.dadID})
	if st != 200 {
		t.Fatalf("add: %d %v", st, l)
	}
	e.status("POST", "/api/lists/"+id+"/items", f.mom, map[string]any{"text": "Bread", "section": "Bakery"})
	b, _ := os.ReadFile(filepath.Join(vault, "Lists", "Shopping.md"))
	if !strings.Contains(string(b), "- [ ] Milk @Dad") || !strings.Contains(string(b), "## Bakery\n- [ ] Bread") {
		t.Fatalf("note:\n%s", b)
	}
	// Someone edits it in Obsidian; ids still line up.
	os.WriteFile(filepath.Join(vault, "Lists", "Shopping.md"), []byte(strings.Replace(string(b), "- [ ] Milk @Dad", "- [ ] Eggs\n- [ ] Milk @Dad", 1)), 0o644)
	_, got := e.status("GET", "/api/lists", f.kid, nil)
	items := got["lists"].([]any)[0].(map[string]any)["items"].([]any)
	var milk string
	for _, x := range items {
		if x.(map[string]any)["text"] == "Milk" {
			milk = x.(map[string]any)["id"].(string)
			if x.(map[string]any)["who"] != f.dadID {
				t.Fatalf("who: %v", x)
			}
		}
	}
	// A younger child (view) can tick.
	if st, out := e.status("POST", "/api/lists/"+id+"/items/"+milk, f.kid, map[string]any{"done": true}); st != 200 {
		t.Fatalf("tick: %d %v", st, out)
	}
	b, _ = os.ReadFile(filepath.Join(vault, "Lists", "Shopping.md"))
	if !strings.Contains(string(b), "- [x] Milk @Dad ✅ ") {
		t.Fatalf("ticked:\n%s", b)
	}
	if st, _ := e.status("POST", "/api/lists/"+id+"/clear", f.mom, nil); st != 200 {
		t.Fatal("clear")
	}
	b, _ = os.ReadFile(filepath.Join(vault, "Lists", "Shopping.md"))
	if strings.Contains(string(b), "Milk") || !strings.Contains(string(b), "Eggs") {
		t.Fatalf("cleared:\n%s", b)
	}
	// Someone's to-dos are theirs (and the grown-ups').
	_, td := e.status("POST", "/api/lists", f.mom, map[string]any{"title": "Teen's jobs", "kind": "todos", "who": []string{f.teenID}})
	_, seen := e.status("GET", "/api/lists", f.kid, nil)
	for _, x := range seen["lists"].([]any) {
		if x.(map[string]any)["id"] == td["id"] {
			t.Fatal("the kid shouldn't see the teen's to-dos")
		}
	}
}
