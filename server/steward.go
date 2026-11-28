package main

// Steward, the assistant: the family talks to its own AI model from the app ("tell
// Sam to unload the dishwasher", "I finished the dishwasher"). An admin points
// FamLogic at any OpenAI-compatible endpoint (Ollama, a Hermes server, a cloud
// provider) in the console; FamLogic runs the conversation and gives the model a
// few tools: who's in the family, the chores, the rewards and the feed. Every
// tool call runs as the person talking, through the same handlers (and the same
// permission checks) as the app, so a child can't do through Steward anything they
// couldn't do with a tap. Each person's conversation is kept (the last 40
// messages) in DATA_DIR/steward/<id>.json.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// StewardConfig is how Steward thinks: a main model (URL/Model/Key, which may be the
// one built into FamLogic) and, if set, a fallback it hands over to when the main one
// can't answer (it's down, too slow, or says a request is beyond it).
type StewardConfig struct {
	Enabled  bool   `json:"enabled"`
	URL      string `json:"url"`   // OpenAI-compatible base, e.g. http://ollama:11434/v1
	Model    string `json:"model"` // e.g. qwen3.5:4b
	Key      string `json:"key,omitempty"`
	Everyone bool   `json:"everyone"` // children too (otherwise grown-ups only)
	// Source of the main model: "builtin" (FamLogic runs it), "server" (a model server
	// on the network: Ollama, llama.cpp, LM Studio) or "cloud" (a provider).
	Source string `json:"source,omitempty"`
	// Mode: "skills" (small prompt, skills opened as needed; the default) or "full"
	// (every action as its own typed tool, for big cloud models that like that).
	Mode string `json:"mode,omitempty"`
	// Timeout for the main model, in seconds, before the fallback takes over (0: 90).
	Timeout  int              `json:"timeout,omitempty"`
	Fallback *StewardEndpoint `json:"fallback,omitempty"`
	// HandOff: the main model may pass a request it finds too hard to the fallback.
	HandOff bool `json:"handOff,omitempty"`
}

// StewardEndpoint is one model to talk to.
type StewardEndpoint struct {
	URL    string `json:"url"`
	Model  string `json:"model"`
	Key    string `json:"key,omitempty"`
	Source string `json:"source,omitempty"`
}

func (c StewardConfig) main() StewardEndpoint {
	return StewardEndpoint{URL: c.URL, Model: c.Model, Key: c.Key, Source: c.Source}
}

func (c StewardConfig) hasFallback() bool {
	return c.Fallback != nil && c.Fallback.URL != "" && c.Fallback.Model != ""
}

func (s *Store) Steward() StewardConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.d.Steward == nil {
		return StewardConfig{}
	}
	return *s.d.Steward
}

func (s *Store) SetSteward(c StewardConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.d.Steward = &c
	return s.saveLocked()
}

// stewardReady: the module is on and a model is set.
func (s *Server) stewardReady() bool {
	c := s.store.Steward()
	return s.store.Modules().Enabled["agents"] && c.Enabled && c.URL != "" && c.Model != ""
}

func (s *Server) mayTalkToSteward(me *Member) bool {
	// The family's TV can ask too (with the remote's microphone), with a display's permissions.
	return s.stewardReady() && me.role() != RoleDependent && (isAdult(me) || me.Display || (s.store.Steward().Everyone && me.can("steward")))
}

// ---- conversation history

type StewardMsg struct {
	Role string `json:"role"` // user, assistant
	Text string `json:"text"`
	Time int64  `json:"time"`
}

var stewardFiles sync.Mutex

func (s *Server) stewardPath(id string) string {
	return filepath.Join(filepath.Dir(s.store.path), "steward", id+".json")
}

func (s *Server) stewardHistory(id string) []StewardMsg {
	stewardFiles.Lock()
	defer stewardFiles.Unlock()
	out := []StewardMsg{}
	if b, err := os.ReadFile(s.stewardPath(id)); err == nil {
		_ = json.Unmarshal(b, &out)
	}
	return out
}

func (s *Server) saveStewardHistory(id string, msgs []StewardMsg) {
	if len(msgs) > 40 {
		msgs = msgs[len(msgs)-40:]
	}
	stewardFiles.Lock()
	defer stewardFiles.Unlock()
	p := s.stewardPath(id)
	_ = os.MkdirAll(filepath.Dir(p), 0o700)
	b, _ := json.Marshal(msgs)
	_ = writeAtomic(p, b)
}

// ---- tools: each runs through the app's own handler, as the person talking

type stewardTool struct {
	name, desc string
	params     map[string]any
	run        func(s *Server, me *Member, args map[string]any) any
}

func obj(props map[string]any, required ...string) map[string]any {
	return map[string]any{"type": "object", "properties": props, "required": required}
}

func str(desc string) map[string]any { return map[string]any{"type": "string", "description": desc} }

// callAs runs one of the app's handlers as [me] and returns its JSON answer.
func (s *Server) callAs(me *Member, h deviceHandler, method, target string, pathVals map[string]string, body any) any {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	r := httptest.NewRequest(method, target, rd)
	r.Header.Set("Content-Type", "application/json")
	for k, v := range pathVals {
		r.SetPathValue(k, v)
	}
	w := httptest.NewRecorder()
	h(w, r, me)
	var out any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		out = strings.TrimSpace(w.Body.String())
	}
	if w.Code >= 300 {
		return map[string]any{"error": out, "status": w.Code}
	}
	if out == nil || out == "" {
		return map[string]any{"ok": true}
	}
	return out
}

// personID finds someone by id or (case-insensitively) by name.
func (s *Server) personID(who string) string {
	who = strings.TrimSpace(strings.Trim(who, "[]@"))
	for _, m := range s.store.Members() {
		if m.ID == who || strings.EqualFold(m.Name, who) {
			return m.ID
		}
	}
	return who
}

// peopleIDs turns a list of names or ids from the model into member ids.
func (s *Server) peopleIDs(v any) []string {
	who := []string{}
	if list, ok := v.([]any); ok {
		for _, w := range list {
			if ws, ok := w.(string); ok {
				who = append(who, s.personID(ws))
			}
		}
	}
	return who
}

func argStr(a map[string]any, k string) string {
	v, _ := a[k].(string)
	return strings.TrimSpace(v)
}

var stewardTools = []stewardTool{
	{"ask_a_parent", "Send the parents a short request from a child, for something the child can't do themselves (more screen time, a new reward, changing someone else's thing).", obj(map[string]any{
		"text": str("The request, in the child's words, e.g. Can I have a friend over on Saturday?"),
	}, "text"),
		func(s *Server, me *Member, a map[string]any) any {
			text := argStr(a, "text")
			if text == "" {
				return map[string]any{"error": "say what to ask"}
			}
			if len([]rune(text)) > 300 {
				text = string([]rune(text)[:300])
			}
			var to []string
			for _, m := range s.store.Members() {
				if m.role() == RoleParent {
					to = append(to, m.ID)
				}
			}
			s.store.ChoreEvents(to, Event{Type: EventAskParent, MemberID: me.ID, Name: me.Name, Title: text})
			return map[string]any{"ok": true, "sentTo": len(to)}
		}},
	{"list_vehicles", "The family's vehicles: id, name, odometer, average economy, recent fill-ups and services.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleVehicles, "GET", "/api/vehicles", nil, nil)
		}},
	{"log_fuel", "Log a fill-up.", obj(map[string]any{
		"vehicle":  str("The vehicle's id"),
		"amount":   map[string]any{"type": "number", "description": "Gallons or litres"},
		"cost":     map[string]any{"type": "number", "description": "What it cost"},
		"odometer": map[string]any{"type": "number", "description": "The odometer reading, if said"},
		"date":     str("YYYY-MM-DD if not today"),
	}, "vehicle", "amount"),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleAddFuel, "POST", "/api/vehicles/{id}/fuel", map[string]string{"id": argStr(a, "vehicle")}, a)
		}},
	{"log_service", "Log a service or repair (oil change, tyres); every makes it a reminder.", obj(map[string]any{
		"vehicle":  str("The vehicle's id"),
		"what":     str("e.g. Oil change"),
		"cost":     map[string]any{"type": "number"},
		"odometer": map[string]any{"type": "number"},
		"every":    str("How often it comes round, e.g. 6 months; empty if it doesn't"),
		"date":     str("YYYY-MM-DD if not today"),
	}, "vehicle", "what"),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleAddService, "POST", "/api/vehicles/{id}/service", map[string]string{"id": argStr(a, "vehicle")}, a)
		}},
	{"list_people", "Everyone in the family: id, name, role, stars.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handlePeople, "GET", "/api/v1/people", nil, nil)
		}},
	{"list_chores", "All chores with id, title, who (ids), repeat, due date, stars, and when each was done.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleChores, "GET", "/api/chores", nil, nil)
		}},
	{"add_chore", "Add a chore for someone.", obj(map[string]any{
		"title": str("Short and plain, e.g. Unload the dishwasher"),
		"who":   map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "description": "Names or ids of who it's for"},
		"every": str("Empty for once; day, weekdays, week, 2 weeks, month, or days like mon,thu"),
		"due":   str("YYYY-MM-DD, empty for today"),
		"when":  str("Optional time of day: morning, afternoon, after school, evening, bedtime or HH:MM"),
		"stars": map[string]any{"type": "integer", "description": "Optional stars (only if a parent asked)"},
		"notes": str("Optional details"),
	}, "title"),
		func(s *Server, me *Member, a map[string]any) any {
			body := map[string]any{"title": argStr(a, "title"), "every": argStr(a, "every"), "when": argStr(a, "when"), "notes": argStr(a, "notes")}
			if d := argStr(a, "due"); d != "" {
				body["due"] = d
			}
			if n, ok := a["stars"].(float64); ok {
				body["stars"] = int(n)
			}
			body["who"] = s.peopleIDs(a["who"])
			return s.callAs(me, s.handleCreateChore, "POST", "/api/chores", nil, body)
		}},
	{"complete_chore", "Tick a chore off as done. Only for the person who did it, or when a parent says so.", obj(map[string]any{
		"chore_id": str("The chore's id (from list_chores)"),
		"by":       str("Name or id of who did it (empty: the person talking)"),
	}, "chore_id"),
		func(s *Server, me *Member, a map[string]any) any {
			body := map[string]any{}
			if by := argStr(a, "by"); by != "" {
				body["by"] = s.personID(by)
			}
			id := argStr(a, "chore_id")
			return s.callAs(me, s.handleChoreDone, "POST", "/api/chores/"+url.PathEscape(id)+"/done", map[string]string{"id": id}, body)
		}},
	{"list_events", "The family calendar for the next days (repeats expanded): id, title, start and end (unix ms), who, where.", obj(map[string]any{
		"days": map[string]any{"type": "integer", "description": "How many days ahead (default 7, at most 60)"},
	}),
		func(s *Server, me *Member, a map[string]any) any {
			d := 7
			if n, ok := a["days"].(float64); ok && n > 0 {
				d = min(int(n), 60)
			}
			now := time.Now()
			from := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.Local)
			return s.callAs(me, s.handleEvents, "GET", fmt.Sprintf("/api/events?from=%d&to=%d", from.UnixMilli(), from.AddDate(0, 0, d).UnixMilli()), nil, nil)
		}},
	{"add_event", "Add an event to the family calendar.", obj(map[string]any{
		"title": str("Short, e.g. Dentist (Sam)"),
		"start": str("YYYY-MM-DDTHH:MM (local time), or YYYY-MM-DD for all day"),
		"end":   str("Optional, same format"),
		"where": str("Optional"),
		"who":   map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "description": "Names or ids"},
		"every": str("Optional: day, weekdays, week, 2 weeks, month, year"),
	}, "title", "start"),
		func(s *Server, me *Member, a map[string]any) any {
			body := map[string]any{"title": argStr(a, "title"), "start": argStr(a, "start"), "end": argStr(a, "end"), "where": argStr(a, "where")}
			if e := argStr(a, "every"); e != "" {
				body["every"] = e
			}
			body["who"] = s.peopleIDs(a["who"])
			return s.callAs(me, s.handleSaveEvent, "POST", "/api/events", nil, body)
		}},
	{"list_lists", "Shared lists (shopping, packing) and to-dos, with their items (id, text, done).", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleLists, "GET", "/api/lists", nil, nil)
		}},
	{"add_to_list", "Add an item to a list (e.g. milk to Shopping).", obj(map[string]any{
		"list_id": str("The list's id (from list_lists)"),
		"text":    str("The item, short"),
		"who":     str("Optional: name or id of who it's for"),
	}, "list_id", "text"),
		func(s *Server, me *Member, a map[string]any) any {
			id := argStr(a, "list_id")
			body := map[string]any{"text": argStr(a, "text")}
			if w := argStr(a, "who"); w != "" {
				body["who"] = s.personID(w)
			}
			return s.callAs(me, s.handleAddItem, "POST", "/api/lists/"+url.PathEscape(id)+"/items", map[string]string{"id": id}, body)
		}},
	{"make_list", "Make a new list on the Docket: a shared list (Shopping, Packing) or someone's to-dos (one-off jobs, errands, projects).", obj(map[string]any{
		"title": str("Short, e.g. Shopping, or the person's name for their to-dos"),
		"kind":  str("list (shared) or todos"),
		"who":   str("For to-dos: name or id of whose they are (empty: the family's)"),
	}, "title"),
		func(s *Server, me *Member, a map[string]any) any {
			body := map[string]any{"title": argStr(a, "title"), "kind": argStr(a, "kind")}
			if body["kind"] == "" {
				body["kind"] = "list"
			}
			if w := argStr(a, "who"); w != "" {
				body["who"] = []string{s.personID(w)}
			}
			return s.callAs(me, s.handleCreateList, "POST", "/api/lists", nil, body)
		}},
	{"tick_list_item", "Tick an item off a list (or untick it).", obj(map[string]any{
		"list_id": str("The list's id"),
		"item_id": str("The item's id"),
		"done":    map[string]any{"type": "boolean"},
	}, "list_id", "item_id"),
		func(s *Server, me *Member, a map[string]any) any {
			id, item := argStr(a, "list_id"), argStr(a, "item_id")
			done, ok := a["done"].(bool)
			if !ok {
				done = true
			}
			return s.callAs(me, s.handleTickItem, "POST", "/api/lists/"+url.PathEscape(id)+"/items/"+url.PathEscape(item), map[string]string{"id": id, "item": item}, map[string]any{"done": done})
		}},
	{"list_rewards", "Rewards stars can buy, and open asks.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleRewards, "GET", "/api/rewards", nil, nil)
		}},
	{"list_meals", "This week's meal plan (days with Breakfast, Lunch, Dinner) and the family's recipes (id, title, ingredients, method, serves).", obj(map[string]any{
		"week": str("Optional ISO week like 2026-W49; empty for this week"),
	}),
		func(s *Server, me *Member, a map[string]any) any {
			q := "/api/meals"
			if wk := argStr(a, "week"); wk != "" {
				q += "?week=" + url.QueryEscape(wk)
			}
			return s.callAs(me, s.handleMeals, "GET", q, nil, nil)
		}},
	{"plan_meal", "Put a dish on the meal plan for one day (or clear it with an empty dish).", obj(map[string]any{
		"date": str("YYYY-MM-DD"),
		"meal": str("Breakfast, Lunch or Dinner"),
		"dish": str("Short, e.g. Tacos"),
	}, "date", "meal", "dish"),
		func(s *Server, me *Member, a map[string]any) any {
			d, err := time.ParseInLocation(dayLayout, argStr(a, "date"), time.Local)
			if err != nil {
				return map[string]any{"error": "date must be YYYY-MM-DD"}
			}
			wk := isoWeek(d)
			return s.callAs(me, s.handleSetMeal, "PUT", "/api/meals/"+wk, map[string]string{"week": wk},
				map[string]any{"date": argStr(a, "date"), "meal": argStr(a, "meal"), "dish": argStr(a, "dish")})
		}},
	{"add_recipe", "Save a recipe to the family's recipe book (a note in the vault). Tidy what was said into a clean recipe first: a short title, one ingredient per entry with amounts (\"2 cups flour\"), and the method as short numbered steps, one per line. Keep the cook's own tips. Don't invent amounts or steps that weren't said; ask if something important is missing.", obj(map[string]any{
		"title":       str("Short, e.g. Grandma's banana bread"),
		"ingredients": map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "description": "One per entry, with amounts"},
		"method":      str("Steps, one per line, numbered (1. ..., 2. ...)"),
		"serves":      str("Optional, e.g. 4 or 1 loaf"),
	}, "title", "ingredients"),
		func(s *Server, me *Member, a map[string]any) any {
			ing := []string{}
			if list, ok := a["ingredients"].([]any); ok {
				for _, x := range list {
					if xs, ok := x.(string); ok && strings.TrimSpace(xs) != "" {
						ing = append(ing, strings.TrimSpace(xs))
					}
				}
			}
			return s.callAs(me, s.handleAddRecipe, "POST", "/api/recipes", nil,
				map[string]any{"title": argStr(a, "title"), "ingredients": ing, "method": argStr(a, "method"), "serves": argStr(a, "serves")})
		}},
	{"shop_recipe", "Put a recipe's ingredients on the shopping list.", obj(map[string]any{
		"recipe_id": str("The recipe's id (from list_meals)"),
	}, "recipe_id"),
		func(s *Server, me *Member, a map[string]any) any {
			id := argStr(a, "recipe_id")
			return s.callAs(me, s.handleShopRecipe, "POST", "/api/recipes/"+url.PathEscape(id)+"/shop", map[string]string{"id": id}, nil)
		}},
	{"list_profiles", "Everyone's profile (Dossier): birthday, allergies, medicines, sleep, likes, dislikes, sizes, doctor, emergency contacts, notes.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleProfiles, "GET", "/api/profiles", nil, nil)
		}},
	{"set_profile", "Change sections of someone's profile. Send only the sections to change, each as its whole new text (one item per line, like \"- Peanuts\"); read it first and keep what's there unless asked to remove it.", obj(map[string]any{
		"person":   str("Name or id"),
		"sections": map[string]any{"type": "object", "description": "Section name to its full new text. Sections: Birthday (YYYY-MM-DD), Allergies, Medicines, Sleep, Likes, Dislikes, Sizes, Doctor, Emergency contacts, Notes", "additionalProperties": map[string]any{"type": "string"}},
	}, "person", "sections"),
		func(s *Server, me *Member, a map[string]any) any {
			id := s.personID(argStr(a, "person"))
			secs, _ := a["sections"].(map[string]any)
			prof := map[string]any{}
			for k, v := range secs {
				if vs, ok := v.(string); ok {
					prof[k] = vs
				}
			}
			return s.callAs(me, s.handleSetProfile, "PUT", "/api/profiles/"+url.PathEscape(id), map[string]string{"id": id}, map[string]any{"profile": prof})
		}},
	{"get_home", "The home's profile (Homestead): what it has (features), bin and recycling days, and the upkeep chores it keeps (with when each was last done and is next due). The features a home can have are listed too.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleHome, "GET", "/api/home", nil, nil)
		}},
	{"set_home", "Change the home's profile; FamLogic then keeps the matching chores (and bin days on the calendar) by itself. Read it first with get_home and send the whole profile back with your change. To say when something was last done (\"I changed the furnace filter in October\"), tick its chore with complete_chore instead, or give lastDone when adding a feature.", obj(map[string]any{
		"tenure":         str("own or rent"),
		"features":       map[string]any{"type": "array", "items": map[string]any{"type": "string"}, "description": "Feature keys from get_home's catalog"},
		"bins":           str("Weekday the bins go out: mon, tue, wed, thu, fri, sat, sun; empty for none"),
		"recycling":      str("Weekday for recycling; empty for none"),
		"recyclingEvery": str("week or 2 weeks"),
		"lastDone":       map[string]any{"type": "object", "description": "Optional: job key to YYYY-MM-DD it was last done, for newly added features", "additionalProperties": map[string]any{"type": "string"}},
	}, "features"),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleSetHome, "PUT", "/api/home", nil, a)
		}},
	{"tv_quiet", "Quiet time on the TV and family displays (Corkboard): they show a calm quiet-time screen, e.g. for a nap. Parents and grandparents only.", obj(map[string]any{
		"minutes": map[string]any{"type": "integer", "description": "How long (at most 720); 0 = until someone ends it"},
		"end":     map[string]any{"type": "boolean", "description": "true to end quiet time now"},
	}),
		func(s *Server, me *Member, a map[string]any) any {
			if end, _ := a["end"].(bool); end {
				return s.callAs(me, s.handleQuietEnd, "DELETE", "/api/display/quiet", nil, nil)
			}
			m, _ := a["minutes"].(float64)
			return s.callAs(me, s.handleQuietStart, "POST", "/api/display/quiet", nil, map[string]any{"minutes": int(m)})
		}},
	{"tv_message", "Show a short message big on the TV and family displays until someone presses OK (\"Inside voices please, the baby's asleep\"). Parents and grandparents only.", obj(map[string]any{
		"text": str("The message, 200 characters at most"),
	}, "text"),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleDisplayMessage, "POST", "/api/display/messages", nil, map[string]any{"text": argStr(a, "text")})
		}},
	{"list_meetings", "Family meetings (Huddle), newest first, with their slides.", obj(map[string]any{}),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleMeetings, "GET", "/api/meetings", nil, nil)
		}},
	{"create_meeting", "Make a family meeting to click through together on the TV. Each slide is a topic: a short title, a line or two of text, and optionally jobs to hand out (who: a name, or empty to ask for a volunteer). Parents only.", obj(map[string]any{
		"title": str("e.g. Sunday family meeting"),
		"date":  str("YYYY-MM-DD; empty for today"),
		"slides": map[string]any{"type": "array", "items": map[string]any{"type": "object", "properties": map[string]any{
			"title": str("The topic, short"),
			"text":  str("A line or two about it"),
			"jobs":  map[string]any{"type": "array", "items": map[string]any{"type": "object", "properties": map[string]any{"text": str("The job"), "who": str("A name, or empty for a volunteer")}}},
		}, "required": []string{"title"}}},
	}, "title", "slides"),
		func(s *Server, me *Member, a map[string]any) any {
			return s.callAs(me, s.handleCreateMeeting, "POST", "/api/meetings", nil, a)
		}},
	{"update_meeting", "Change a family meeting: send the whole meeting back (read it first with list_meetings) with your changes. Parents only.", obj(map[string]any{
		"id":     str("The meeting's id"),
		"title":  str("Its title"),
		"date":   str("YYYY-MM-DD"),
		"slides": map[string]any{"type": "array", "items": map[string]any{"type": "object"}, "description": "Every slide: {title, text, jobs: [{text, who}], notes: [text]}"},
	}, "id", "title", "slides"),
		func(s *Server, me *Member, a map[string]any) any {
			id := argStr(a, "id")
			return s.callAs(me, s.handleSaveMeeting, "PUT", "/api/meetings/"+url.PathEscape(id), map[string]string{"id": id}, a)
		}},
	{"read_feed", "What happened recently: arrivals, check-ins, trips, chores done.", obj(map[string]any{
		"hours": map[string]any{"type": "integer", "description": "How far back (default 12, at most 48)"},
	}),
		func(s *Server, me *Member, a map[string]any) any {
			h := 12
			if n, ok := a["hours"].(float64); ok && n > 0 {
				h = min(int(n), 48)
			}
			now := time.Now().UnixMilli()
			q := fmt.Sprintf("/api/feed?from=%d&to=%d", now-int64(h)*3_600_000, now)
			return s.callAs(me, s.handleFeed, "GET", q, nil, nil)
		}},
}

// ---- the conversation with the model

type llmMsg struct {
	Role       string        `json:"role"`
	Content    string        `json:"content"`
	ToolCalls  []llmToolCall `json:"tool_calls,omitempty"`
	ToolCallID string        `json:"tool_call_id,omitempty"`
}

type llmToolCall struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

// turnStats is how one answer went, for the console's AI page (speed, tokens, which model).
type turnStats struct {
	Time       int64    `json:"time"`
	Model      string   `json:"model"`
	Fallback   bool     `json:"fallback,omitempty"`
	Why        string   `json:"why,omitempty"` // why the fallback answered
	MS         int64    `json:"ms"`
	Rounds     int      `json:"rounds"`
	Prompt     int      `json:"prompt"`           // tokens the model read
	Cached     int      `json:"cached,omitempty"` // ...of which it had kept from before
	Completion int      `json:"completion"`       // tokens it wrote
	Actions    []string `json:"actions,omitempty"`
	OK         bool     `json:"ok"`
	Error      string   `json:"error,omitempty"`
}

var (
	recentTurns   []turnStats
	recentTurnsMu sync.Mutex
)

func noteTurn(t turnStats) {
	recentTurnsMu.Lock()
	recentTurns = append(recentTurns, t)
	if len(recentTurns) > 30 {
		recentTurns = recentTurns[len(recentTurns)-30:]
	}
	recentTurnsMu.Unlock()
}

var stewardClient = &http.Client{Timeout: 5 * time.Minute}

// errHandOff: the main model passed the request on.
var errHandOff = errors.New("the main model handed this one on")

var thinkRe = regexp.MustCompile(`(?s)<think>.*?</think>`)
var toolCallRe = regexp.MustCompile(`(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>`)

// stewardReply answers: the main model first; if it can't (and nothing has been
// changed yet, so nothing gets done twice), the fallback.
func (s *Server) stewardReply(ctx context.Context, me *Member, history []StewardMsg, page string) (string, error) {
	reply, _, err := s.stewardAnswer(ctx, me, history, page)
	return reply, err
}

func (s *Server) stewardAnswer(ctx context.Context, me *Member, history []StewardMsg, page string) (string, turnStats, error) {
	c := s.store.Steward()
	timeout := time.Duration(c.Timeout) * time.Second
	if timeout <= 0 {
		timeout = 90 * time.Second
	}
	mainCtx := ctx
	cancel := func() {}
	if c.hasFallback() {
		mainCtx, cancel = context.WithTimeout(ctx, timeout)
	}
	reply, st, wrote, err := s.converse(mainCtx, c.main(), c.Mode, me, history, page, c.hasFallback() && c.HandOff)
	cancel()
	if err != nil && c.hasFallback() && !wrote && ctx.Err() == nil {
		why := err.Error()
		if errors.Is(err, errHandOff) {
			why = "handed on"
		} else if errors.Is(err, context.DeadlineExceeded) {
			why = "too slow"
		}
		reply, st, _, err = s.converse(ctx, *c.Fallback, c.Mode, me, history, page, false)
		st.Fallback, st.Why = true, why
	}
	st.OK = err == nil
	if err != nil {
		st.Error = err.Error()
	}
	noteTurn(st)
	return reply, st, err
}

// converse runs one conversation with one model: it calls actions until it answers.
func (s *Server) converse(ctx context.Context, ep StewardEndpoint, mode string, me *Member, history []StewardMsg, page string, handOff bool) (reply string, st turnStats, wrote bool, err error) {
	start := time.Now()
	st = turnStats{Time: start.UnixMilli(), Model: ep.Model}
	defer func() { st.MS = time.Since(start).Milliseconds() }()
	full := mode == "full"
	msgs := []llmMsg{{Role: "system", Content: s.stewardCore(full)}}
	for i, h := range history {
		text := h.Text
		if i == len(history)-1 && h.Role == "user" {
			open := []string{}
			if !full {
				open = guessSkills(text, page)
			}
			text = s.stewardNote(me, page, open) + "\n" + text
		}
		msgs = append(msgs, llmMsg{Role: h.Role, Content: text})
	}
	tools := skillTools()
	if full {
		tools = fullTools()
	}
	if handOff {
		tools = append(tools, map[string]any{"type": "function", "function": map[string]any{
			"name": "hand_off", "description": "Pass this request to a bigger model if it's too complicated for you. Only before you've changed anything.",
			"parameters": obj(map[string]any{"reason": str("Why, in a few words")})}})
	}
	for round := 0; round < 8; round++ {
		st.Rounds = round + 1
		req := map[string]any{"model": ep.Model, "messages": msgs, "tools": tools, "temperature": 0.2}
		if ep.Source == "builtin" || ep.Source == "server" {
			// Local model servers: no long "thinking" before each answer (llama.cpp, Ollama).
			req["chat_template_kwargs"] = map[string]any{"enable_thinking": false}
			req["think"] = false
			req["cache_prompt"] = true
		}
		var m llmMsg
		m, err = s.callModel(ctx, ep, req, &st)
		if err != nil {
			return "", st, wrote, err
		}
		if len(m.ToolCalls) == 0 {
			return strings.TrimSpace(m.Content), st, wrote, nil
		}
		msgs = append(msgs, llmMsg{Role: "assistant", Content: m.Content, ToolCalls: m.ToolCalls})
		for _, call := range m.ToolCalls {
			if call.Function.Name == "hand_off" && handOff && !wrote {
				return "", st, false, errHandOff
			}
			result, action := s.runCall(me, call.Function.Name, call.Function.Arguments)
			if action != "" {
				st.Actions = append(st.Actions, action)
				if isWrite(action) {
					wrote = true
				}
			}
			msgs = append(msgs, llmMsg{Role: "tool", ToolCallID: call.ID, Content: compactResult(result)})
		}
	}
	return "", st, wrote, fmt.Errorf("the model kept calling tools without answering")
}

// callModel sends one request and reads the answer (and how many tokens it took).
func (s *Server) callModel(ctx context.Context, ep StewardEndpoint, req map[string]any, st *turnStats) (llmMsg, error) {
	body, _ := json.Marshal(req)
	hr, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(ep.URL, "/")+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return llmMsg{}, err
	}
	hr.Header.Set("Content-Type", "application/json")
	if ep.Key != "" {
		hr.Header.Set("Authorization", "Bearer "+ep.Key)
	}
	res, err := stewardClient.Do(hr)
	if err != nil {
		if ctx.Err() != nil {
			return llmMsg{}, ctx.Err()
		}
		return llmMsg{}, err
	}
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return llmMsg{}, fmt.Errorf("the model answered %d: %.200s", res.StatusCode, raw)
	}
	var out struct {
		Choices []struct {
			Message llmMsg `json:"message"`
		} `json:"choices"`
		Usage struct {
			Prompt     int `json:"prompt_tokens"`
			Completion int `json:"completion_tokens"`
			Details    struct {
				Cached int `json:"cached_tokens"`
			} `json:"prompt_tokens_details"`
		} `json:"usage"`
		Timings struct { // llama.cpp
			CacheN int `json:"cache_n"`
		} `json:"timings"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || len(out.Choices) == 0 {
		return llmMsg{}, fmt.Errorf("the model's answer didn't make sense")
	}
	st.Prompt += out.Usage.Prompt
	st.Completion += out.Usage.Completion
	st.Cached += max(out.Usage.Details.Cached, out.Timings.CacheN)
	m := out.Choices[0].Message
	m.Content = strings.TrimSpace(thinkRe.ReplaceAllString(m.Content, ""))
	// Some servers leave Hermes-style tool calls in the text: pick them out.
	if len(m.ToolCalls) == 0 {
		for i, g := range toolCallRe.FindAllStringSubmatch(m.Content, -1) {
			var tc struct {
				Name      string `json:"name"`
				Arguments any    `json:"arguments"`
			}
			if json.Unmarshal([]byte(g[1]), &tc) != nil || tc.Name == "" {
				continue
			}
			args, _ := json.Marshal(tc.Arguments)
			call := llmToolCall{ID: fmt.Sprintf("tc%d", i), Type: "function"}
			call.Function.Name, call.Function.Arguments = tc.Name, string(args)
			m.ToolCalls = append(m.ToolCalls, call)
		}
		if len(m.ToolCalls) > 0 {
			m.Content = strings.TrimSpace(toolCallRe.ReplaceAllString(m.Content, ""))
		}
	}
	for i := range m.ToolCalls {
		if m.ToolCalls[i].ID == "" {
			m.ToolCalls[i].ID = fmt.Sprintf("call%d", i)
		}
		if m.ToolCalls[i].Type == "" {
			m.ToolCalls[i].Type = "function"
		}
	}
	return m, nil
}

// ---- HTTP

// GET /api/steward: your conversation.
func (s *Server) handleStewardGet(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.mayTalkToSteward(me) {
		writeError(w, http.StatusForbidden, "Steward isn't set up for you")
		return
	}
	undo := s.loadStewardUndo(me.ID)
	writeJSON(w, http.StatusOK, map[string]any{"messages": s.stewardHistory(me.ID), "changed": changedNames(undo), "canUndo": undo != nil})
}

// POST /api/steward {text}: say something; the answer comes back with the conversation.
func (s *Server) handleStewardSay(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.mayTalkToSteward(me) {
		writeError(w, http.StatusForbidden, "Steward isn't set up for you")
		return
	}
	var body struct {
		Text string `json:"text"`
		Page string `json:"page"` // where they asked from (meals, chores...), so "this" makes sense
	}
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.TrimSpace(body.Text)
	if text == "" || utf8.RuneCountInString(text) > 1000 {
		writeError(w, http.StatusBadRequest, "say something (up to 1000 characters)")
		return
	}
	hist := append(s.stewardHistory(me.ID), StewardMsg{Role: "user", Text: text, Time: time.Now().UnixMilli()})
	// A small model on a small computer can take a while: give it time (and keep the
	// connection open for it, past the server's usual 30 seconds).
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(6 * time.Minute))
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	recent := hist
	if len(recent) > 16 {
		recent = recent[len(recent)-16:]
	}
	// One answer at a time, with every file it changes remembered, so it can be undone.
	stewardTurns.Lock()
	journalStart(filepath.Dir(s.stewardPath(me.ID)))
	reply, err := s.stewardReply(ctx, me, recent, body.Page)
	undo := s.saveStewardUndo(me.ID, journalStop())
	stewardTurns.Unlock()
	if err != nil {
		reply = "I can't reach my brain right now (" + err.Error() + "). Try again in a bit."
	} else if reply == "" {
		reply = "Done."
	}
	hist = append(hist, StewardMsg{Role: "assistant", Text: reply, Time: time.Now().UnixMilli()})
	s.saveStewardHistory(me.ID, hist)
	writeJSON(w, http.StatusOK, map[string]any{"messages": s.stewardHistory(me.ID), "reply": reply,
		"changed": changedNames(undo), "canUndo": undo != nil})
}

// DELETE /api/steward: start over.
func (s *Server) handleStewardClear(w http.ResponseWriter, r *http.Request, me *Member) {
	s.saveStewardHistory(me.ID, []StewardMsg{})
	os.Remove(s.stewardUndoPath(me.ID)) // starting over: the last answer stays as it is
	w.WriteHeader(http.StatusNoContent)
}

// GET/PUT /api/admin/steward: the model settings (the key is never sent back).
func (s *Server) handleAdminSteward(w http.ResponseWriter, r *http.Request) {
	c := s.store.Steward()
	hasKey := c.Key != ""
	c.Key = ""
	hasFallbackKey := false
	if c.Fallback != nil {
		fb := *c.Fallback
		hasFallbackKey = fb.Key != ""
		fb.Key = ""
		c.Fallback = &fb
	}
	out := s.brainStatus()
	out["config"], out["hasKey"], out["hasFallbackKey"] = c, hasKey, hasFallbackKey
	out["mcpURL"] = s.baseURL(r) + "/mcp"
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleAdminSetSteward(w http.ResponseWriter, r *http.Request) {
	var body struct {
		StewardConfig
		ClearKey         bool `json:"clearKey"`
		ClearFallbackKey bool `json:"clearFallbackKey"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	c := body.StewardConfig
	c.URL = strings.TrimSpace(c.URL)
	if c.URL != "" {
		u, err := url.Parse(c.URL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			writeError(w, http.StatusBadRequest, "the address starts with http:// or https://, e.g. http://ollama:11434/v1")
			return
		}
	}
	if len(c.Model) > 100 || len(c.Key) > 500 {
		writeError(w, http.StatusBadRequest, "that's too long")
		return
	}
	old := s.store.Steward()
	if c.Key == "" && !body.ClearKey {
		c.Key = old.Key // keep the saved one
	}
	if c.Mode != "" && c.Mode != "skills" && c.Mode != "full" {
		writeError(w, http.StatusBadRequest, "mode is skills or full")
		return
	}
	if c.Timeout < 0 || c.Timeout > 600 {
		writeError(w, http.StatusBadRequest, "the timeout is 0 to 600 seconds")
		return
	}
	if fb := c.Fallback; fb != nil {
		fb.URL = strings.TrimSpace(fb.URL)
		if fb.URL == "" {
			c.Fallback = nil
		} else if u, err := url.Parse(fb.URL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			writeError(w, http.StatusBadRequest, "the fallback's address starts with http:// or https://")
			return
		} else if fb.Key == "" && !body.ClearFallbackKey && old.Fallback != nil {
			fb.Key = old.Fallback.Key
		}
	}
	// Leaving the built-in model: it stops (and frees its memory).
	if old.Source == "builtin" && c.Source != "builtin" {
		s.stopBrain()
	}
	if err := s.store.SetSteward(c); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	s.handleAdminSteward(w, r)
}

// POST /api/admin/steward/test: one short exchange, to check the settings work.
func (s *Server) handleAdminTestSteward(w http.ResponseWriter, r *http.Request) {
	if s.store.Steward().URL == "" {
		writeError(w, http.StatusBadRequest, "set the address and model first")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	start := time.Now()
	reply, err := s.stewardReply(ctx, &Member{ID: "admin", Name: "Admin", Role: RoleParent}, []StewardMsg{{Role: "user", Text: "Say hello in five words or fewer."}}, "")
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"reply": reply, "ms": strconv.FormatInt(time.Since(start).Milliseconds(), 10)})
}
