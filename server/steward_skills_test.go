package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// Steward's prompt stays small: the part sent with every request, and each skill.
// If this fails, something grew; trim it or move it into a skill.
func TestStewardPromptBudget(t *testing.T) {
	f := newChatFamily(t)
	s := f.e.app
	tools, _ := json.Marshal(skillTools())
	every := len(s.stewardCore(false)) + len(tools)
	if every > 3600 { // about 900 tokens
		t.Errorf("the prompt sent every time is %d characters (budget 3600)", every)
	}
	for _, sk := range stewardSkills {
		if n := len(skillText(&sk)); n > 2200 {
			t.Errorf("skill %s is %d characters (budget 2200)", sk.Name, n)
		}
		for _, a := range sk.Actions {
			if toolByName(a) == nil {
				t.Errorf("skill %s names an action that doesn't exist: %s", sk.Name, a)
			}
		}
	}
	// Every action is in some skill, so a model in skills mode can find it.
	for _, tool := range stewardTools {
		found := false
		for _, sk := range stewardSkills {
			for _, a := range sk.Actions {
				found = found || a == tool.name
			}
		}
		if !found {
			t.Errorf("action %s isn't in any skill", tool.name)
		}
	}
	// The system prompt doesn't change from one minute to the next (so it can be cached).
	if strings.Contains(s.stewardCore(false), ":") && strings.Contains(s.stewardCore(false), "2026") {
		t.Error("the system prompt shouldn't hold the time")
	}
}

func TestGuessSkills(t *testing.T) {
	cases := map[string]string{
		"add milk to the shopping list":        "lists",
		"I unloaded the dishwasher":            "chores",
		"Sam has the dentist tomorrow at 3pm":  "calendar",
		"what's for dinner tonight":            "meals",
		"put quiet time on the TV for an hour": "tv",
	}
	for say, want := range cases {
		got := guessSkills(say, "")
		if len(got) == 0 || got[0] != want {
			t.Errorf("%q: %v, want %s first", say, got, want)
		}
	}
	if g := guessSkills("hello", "chores"); len(g) != 1 || g[0] != "chores" {
		t.Errorf("the page's skill: %v", g)
	}
}

func TestCompactResult(t *testing.T) {
	out := compactResult(map[string]any{"chores": []any{
		map[string]any{"id": "c1", "title": "Bins", "color": "#fff", "notes": "", "due": float64(1795756732575), "who": []any{}},
	}})
	if strings.Contains(out, "color") || strings.Contains(out, "notes") || strings.Contains(out, "1795756732575") || !strings.Contains(out, "2026") {
		t.Errorf("compact: %s", out)
	}
}

// A model that skips act and calls an action by name still gets it done; act with
// the arguments next to the action (not inside args) works too.
func TestRunCallForgiving(t *testing.T) {
	f := newChatFamily(t)
	s := f.e.app
	mom := s.store.Members()[0]
	for _, m := range s.store.Members() {
		if m.Name == "Mom" {
			mom = m
		}
	}
	if out, _ := s.runCall(&mom, "open_skill", `{"name":"chores"}`); !strings.Contains(out.(string), "add_chore") {
		t.Fatalf("open_skill: %v", out)
	}
	if _, a := s.runCall(&mom, "act", `{"action":"list_chores","args":{}}`); a != "list_chores" {
		t.Fatalf("act: %s", a)
	}
	if _, a := s.runCall(&mom, "act", `{"action":"list_lists"}`); a != "list_lists" {
		t.Fatalf("act without args: %s", a)
	}
	if out, _ := s.runCall(&mom, "act", `{"action":"add_chore","args":{}}`); !strings.Contains(compactResult(out), "missing title") {
		t.Fatalf("missing: %v", out)
	}
	if out, _ := s.runCall(&mom, "nope", `{}`); !strings.Contains(compactResult(out), "no such action") {
		t.Fatalf("unknown: %v", out)
	}
}
