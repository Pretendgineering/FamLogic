package main

// Steward's skills: what keeps its prompt small enough for a small model on a small
// computer (and cheaper and quicker for big ones).
//
// The old way sent every action, fully typed, with every request (about 4,500 tokens
// before anyone said anything). Now, like Hermes Agent's skills, the model gets:
//
//   - a short system prompt that never changes (so the model server can keep it
//     cached between requests: a local model then only reads what's new),
//   - a one-line index of skills (chores, calendar, lists...),
//   - two tools: open_skill (which gives back that skill's rules and its actions,
//     written as short signatures) and act (which runs one action).
//
// What does change (the time, who's talking, the page they're on) rides along with
// the newest message instead of the system prompt. The skill the page or the
// message obviously needs is opened up front, which saves a round trip.
//
// "Full" mode (every action as its own typed tool) is still there for big cloud
// models that prefer it; it uses the same cached-prompt layout.

import (
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"slices"
	"sort"
	"strings"
	"time"
)

// stewardSkill groups actions with the rules for using them.
type stewardSkill struct {
	Name    string   // open_skill's name for it
	Summary string   // one line for the index
	Rules   string   // what to know when using it (only sent when it's opened)
	Actions []string // the actions it unlocks (names in stewardTools)
	Words   []string // words in a request that mean it's probably needed
}

var stewardSkills = []stewardSkill{
	{"chores", "Upkeep: household chores, who does them, ticking them off, stars and rewards",
		`- Only tick off a chore for the person who says they did it, or when a parent says so. A child can't tick off a sibling's chore.
- Keep chore names short ("Unload the dishwasher"); put details in notes.
- Only give stars when a parent asks. Never retire chores or change who they're for unless a parent asks.
- Upkeep for the home, cars and pets is ordinary chores. When someone says they did one ("changed the furnace filter"), tick that chore; don't make a new one.
- Find a chore's id with list_chores before ticking it.`,
		[]string{"list_chores", "add_chore", "complete_chore", "list_rewards"},
		[]string{"chore", "upkeep", "dishwasher", "dishes", "bins", "trash", "rubbish", "laundry", "vacuum", "hoover", "sweep", "tidy", "clean", "feed the", "walk the", "star", "reward", "i did", "i've done", "i finished", "done with"}},
	{"calendar", "Datebook: the family calendar (appointments, activities, anything at a set day or time)",
		`- Times are local. Use YYYY-MM-DD for all-day things, YYYY-MM-DDTHH:MM otherwise.
- Put whose it is in "who" (names are fine).`,
		[]string{"list_events", "add_event"},
		[]string{"calendar", "datebook", "appointment", "dentist", "doctor", "tomorrow", "tonight", "next week", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "o'clock", " at ", "pm", "am", "practice", "lesson", "party", "meeting at", "what's on", "whats on", "schedule"}},
	{"lists", "Docket: shared lists (Shopping, Packing) and each person's to-dos (one-off jobs, errands, projects)",
		`- Things to buy go on the Shopping list. One-off jobs and errands go on that person's to-dos.
- Find a list's id with list_lists; make_list if the right one isn't there.`,
		[]string{"list_lists", "add_to_list", "make_list", "tick_list_item"},
		[]string{"list", "shopping", "buy", "get some", "we need", "we're out", "out of", "milk", "eggs", "bread", "to-do", "todo", "to do", "docket", "errand", "packing", "remind me to"}},
	{"meals", "Pantry: the week's meal plan, the recipe book, and putting a recipe's ingredients on the shopping list",
		`- Recipes: when someone reads or talks through a recipe (maybe over several messages), tidy it into a clean one: a short title, one ingredient per entry with amounts, and the method as short numbered steps. Keep the cook's tips. Don't invent amounts or steps; ask if something important is missing. Then save it with add_recipe and say its name and how many ingredients and steps it has.`,
		[]string{"list_meals", "plan_meal", "add_recipe", "shop_recipe"},
		[]string{"meal", "dinner", "lunch", "breakfast", "recipe", "cook", "pantry", "tacos", "pasta", "menu", "what's for", "whats for"}},
	{"family", "The family: who's who, and the feed of what happened today (arrivals, check-ins, chores done)",
		`- A child asking for something they can't do with a tap: ask_a_parent, then tell them you've asked.`,
		[]string{"list_people", "read_feed", "ask_a_parent"},
		[]string{"who's home", "whos home", "where is", "happened", "today so far", "arrived", "feed"}},
	{"profiles", "Dossier: each person's profile (birthday, allergies, medicines, sleep, likes, sizes, doctor, emergency contacts)",
		`- Read a profile before changing it, and keep what's there unless asked to remove it.`,
		[]string{"list_profiles", "set_profile"},
		[]string{"allerg", "medicine", "medication", "birthday", "shoe size", "size", "doctor", "emergency contact", "profile", "dossier", "likes", "dislikes"}},
	{"home", "Homestead: the home's profile (what it has, bin days) and the upkeep it keeps",
		`- Read it with get_home first, and send the whole profile back with your change.
- When someone did a home job, tick its chore (open the chores skill) instead.`,
		[]string{"get_home", "set_home"},
		[]string{"homestead", "furnace", "filter", "gutter", "smoke alarm", "bin day", "recycling", "the house", "our home"}},
	{"vehicles", "Driveway: the family's vehicles, fill-ups and services",
		`- Find the vehicle with list_vehicles. Units are the family's (miles and gallons, or km and litres).`,
		[]string{"list_vehicles", "log_fuel", "log_service"},
		[]string{"filled up", "fill up", "gas", "petrol", "diesel", "fuel", "gallons", "litres", "liters", "oil change", "tyres", "tires", "mileage", "odometer", "car", "truck", "van", "driveway"}},
	{"tv", "Corkboard: quiet time on the TV and family displays, and messages shown big on them",
		`- Parents and grandparents only.`,
		[]string{"tv_quiet", "tv_message"},
		[]string{"tv", "television", "corkboard", "quiet time", "nap", "display", "on the screen"}},
	{"meetings", "Huddle: family meetings (slides of topics, jobs to hand out, notes)",
		`- Parents only. Read a meeting with list_meetings before changing it, and send it all back.`,
		[]string{"list_meetings", "create_meeting", "update_meeting"},
		[]string{"meeting", "huddle", "agenda", "family meeting"}},
	{"notes", "The notes vault: anything else (pets, cars, the house, budgets, logs), read and written as Markdown notes",
		`- Use the specific skills when one fits. For anything else, read the vault guide (read_vault_guide) first, then list_notes, read_note and write_note, keeping formats exactly.
- Re-read a note before changing it, keep what you don't mean to change, and never delete what someone else wrote unless asked.
- Things still coming to FamLogic keep their notes in a folder named after them (Pets/Biscuit.md, Cars/Sienna.md), front matter first; logs are dated lines ("- 2026-11-25: 15 gal, $56.00, 84,210 mi").
- Grown-ups only.`,
		[]string{"read_vault_guide", "list_notes", "read_note", "write_note"},
		[]string{"note", "vault", "pet", "dog", "cat", "car", "vehicle", "fuel", "gas", "filled up", "mileage", "vet", "budget", "bill"}},
	{"sorting", "Sorting out a ramble: someone lists everything they need to do, and each thing goes where it belongs",
		`- Sort each thing without asking about each one: a regular household job that comes round again (dishes, bins) is a chore (add_chore, with how often); anything at a set day or time is an event (add_event); a one-off job, errand or project goes on that person's to-dos (list_lists, then add_to_list, or make_list if they have none); things to buy go on the Shopping list.
- Then reply with a short summary of where each thing went.`,
		[]string{"list_lists", "add_to_list", "make_list", "add_chore", "add_event"},
		[]string{"i need to", "i have to", "need to do", "today i", "this week i", "and then", "also need"}},
}

// pageSkill: the skill a page of the app is about (asking from Upkeep opens chores).
var pageSkill = map[string]string{
	"chores": "chores", "calendar": "calendar", "todos": "lists", "meals": "meals", "profiles": "profiles",
	"homestead": "home", "display": "tv", "meetings": "meetings", "routines": "lists",
}

func skillByName(name string) *stewardSkill {
	name = strings.ToLower(strings.TrimSpace(name))
	for i := range stewardSkills {
		if stewardSkills[i].Name == name {
			return &stewardSkills[i]
		}
	}
	return nil
}

func toolByName(name string) *stewardTool {
	for i := range stewardTools {
		if stewardTools[i].name == name {
			return &stewardTools[i]
		}
	}
	return nil
}

// guessSkills: the skills a request (and the page it came from) most likely needs, at most two.
func guessSkills(text, page string) []string {
	out := []string{}
	if sk, ok := pageSkill[strings.ToLower(page)]; ok {
		out = append(out, sk)
	}
	t := " " + strings.ToLower(text) + " "
	type hit struct {
		name string
		n    int
	}
	hits := []hit{}
	for _, sk := range stewardSkills {
		n := 0
		for _, w := range sk.Words {
			if strings.Contains(t, w) {
				n++
			}
		}
		if n > 0 {
			hits = append(hits, hit{sk.Name, n})
		}
	}
	sort.SliceStable(hits, func(i, j int) bool { return hits[i].n > hits[j].n })
	for _, h := range hits {
		if len(out) >= 2 {
			break
		}
		if !slices.Contains(out, h.name) {
			out = append(out, h.name)
		}
	}
	return out
}

// signature writes an action as one short block: its name, what it does, its arguments.
func signature(t *stewardTool) string {
	var b strings.Builder
	b.WriteString(t.name + ": " + t.desc)
	props, _ := t.params["properties"].(map[string]any)
	req, _ := t.params["required"].([]string)
	keys := make([]string, 0, len(props))
	for k := range props {
		keys = append(keys, k)
	}
	// Required arguments first, then the rest, each in a stable order.
	sort.SliceStable(keys, func(i, j int) bool {
		ri, rj := slices.Contains(req, keys[i]), slices.Contains(req, keys[j])
		if ri != rj {
			return ri
		}
		return keys[i] < keys[j]
	})
	for _, k := range keys {
		p, _ := props[k].(map[string]any)
		kind, _ := p["type"].(string)
		desc, _ := p["description"].(string)
		tag := ""
		switch kind {
		case "array":
			tag = "list"
		case "integer", "number":
			tag = "number"
		case "boolean":
			tag = "true/false"
		case "object":
			tag = "object"
		}
		if slices.Contains(req, k) {
			if tag == "" {
				tag = "required"
			} else {
				tag += ", required"
			}
		}
		b.WriteString("\n  " + k)
		if tag != "" {
			b.WriteString(" (" + tag + ")")
		}
		if desc != "" {
			b.WriteString(": " + desc)
		}
	}
	return b.String()
}

// skillText is what open_skill gives back: the rules, then the actions.
func skillText(sk *stewardSkill) string {
	var b strings.Builder
	b.WriteString("Skill " + sk.Name + ": " + sk.Summary + ".\n")
	if sk.Rules != "" {
		b.WriteString(sk.Rules + "\n")
	}
	b.WriteString("Actions (use them with act):\n")
	for _, a := range sk.Actions {
		if t := toolByName(a); t != nil {
			b.WriteString(signature(t) + "\n")
		}
	}
	return strings.TrimRight(b.String(), "\n")
}

// stewardCore is the system prompt: the same for every request in a family (until
// someone joins or the family is renamed), so a model server can keep it cached.
func (s *Server) stewardCore(full bool) string {
	people := []string{}
	for _, m := range s.store.Members() {
		if m.Display {
			continue // TVs aren't people
		}
		people = append(people, fmt.Sprintf("%s (%s)", m.Name, strings.ReplaceAll(m.access(), "_", " ")))
	}
	var b strings.Builder
	fam := s.familyName()
	if !strings.HasSuffix(strings.ToLower(fam), "family") {
		fam += " family"
	}
	fmt.Fprintf(&b, `You are Steward, the helper inside FamLogic, the %s's self-hosted app.
Family: %s.

Keep replies short and plain: one or two sentences, no emoji, no markdown. Children may be listening.
Use your actions to see and change things. Never invent things or say something was done unless an action said so.
If you're unsure who someone means, ask instead of guessing. If an action returns an error, say plainly what went wrong.
Don't assume children go to school; families homeschool or keep their own hours.
Each message starts with a note in square brackets: the time, who's talking and where they are in the app.
`, fam, strings.Join(people, ", "))
	if full {
		b.WriteString("\nRules for each part of the app:\n")
		for _, sk := range stewardSkills {
			if sk.Rules != "" {
				b.WriteString(sk.Name + ":\n" + sk.Rules + "\n")
			}
		}
		return strings.TrimRight(b.String(), "\n")
	}
	b.WriteString(`
How to work: open the skill you need with open_skill (it gives you its rules and actions), then use act with an action's name and its arguments. Open more skills when you need them. A skill shown in the note is already open.
Skills:
`)
	for _, sk := range stewardSkills {
		b.WriteString("- " + sk.Name + ": " + sk.Summary + "\n")
	}
	return strings.TrimRight(b.String(), "\n")
}

// stewardNote is the note on the newest message: what changes from one request to the next.
func (s *Server) stewardNote(me *Member, page string, open []string) string {
	where := ""
	if desc, ok := stewardPages[strings.ToLower(strings.TrimSpace(page))]; ok {
		where = "; they're on " + desc + ` ("this" means something there)`
	}
	who := me.Name + " (" + strings.ReplaceAll(me.access(), "_", " ") + ")"
	if me.Display {
		who = me.Name + " (a family TV)"
	}
	note := fmt.Sprintf("[%s. Talking: %s%s]", time.Now().Format("Monday 2 January 2006, 15:04"), who, where)
	for _, name := range open {
		if sk := skillByName(name); sk != nil {
			note += "\n[" + skillText(sk) + "]"
		}
	}
	return note
}

// The two tools in skills mode. The names are listed, so a model's grammar can't make one up.
func skillTools() []map[string]any {
	skills, actions := []string{}, []string{}
	for _, sk := range stewardSkills {
		skills = append(skills, sk.Name)
	}
	for _, t := range stewardTools {
		actions = append(actions, t.name)
	}
	sort.Strings(actions)
	return []map[string]any{
		{"type": "function", "function": map[string]any{
			"name": "open_skill", "description": "Open a skill: get its rules and its actions.",
			"parameters": obj(map[string]any{"name": map[string]any{"type": "string", "enum": skills}}, "name")}},
		{"type": "function", "function": map[string]any{
			"name": "act", "description": "Do one action from an open skill.",
			"parameters": obj(map[string]any{
				"action": map[string]any{"type": "string", "enum": actions},
				"args":   map[string]any{"type": "object", "description": "The action's arguments"},
			}, "action")}},
	}
}

// fullTools: every action as its own typed tool (the old way, for big models).
func fullTools() []map[string]any {
	tools := []map[string]any{}
	for _, t := range stewardTools {
		tools = append(tools, map[string]any{"type": "function", "function": map[string]any{"name": t.name, "description": t.desc, "parameters": t.params}})
	}
	return tools
}

// isWrite: actions that change something (everything but list_, get_ and read_).
func isWrite(action string) bool {
	return !(strings.HasPrefix(action, "list_") || strings.HasPrefix(action, "get_") || strings.HasPrefix(action, "read_"))
}

// runCall carries out one tool call from the model, in either mode (and forgiving:
// a model that calls an action by name, skipping act, still gets it done).
func (s *Server) runCall(me *Member, name, rawArgs string) (result any, action string) {
	args := map[string]any{}
	if rawArgs != "" {
		if err := json.Unmarshal([]byte(rawArgs), &args); err != nil {
			return map[string]any{"error": "the arguments weren't valid JSON"}, ""
		}
	}
	switch name {
	case "open_skill":
		sk := skillByName(argStr(args, "name"))
		if sk == nil {
			return map[string]any{"error": "no such skill; the skills are listed in your instructions"}, ""
		}
		return skillText(sk), ""
	case "act":
		action = argStr(args, "action")
		inner, _ := args["args"].(map[string]any)
		if inner == nil {
			// Some models put the arguments next to the action instead of inside args.
			inner = map[string]any{}
			for k, v := range args {
				if k != "action" {
					inner[k] = v
				}
			}
		}
		args = inner
	default:
		action = name
	}
	t := toolByName(action)
	if t == nil {
		return map[string]any{"error": "no such action: " + action}, ""
	}
	if missing := missingArgs(t, args); len(missing) > 0 {
		return map[string]any{"error": "missing " + strings.Join(missing, ", "), "usage": signature(t)}, ""
	}
	journalLive(true)
	out := t.run(s, me, args)
	journalLive(false)
	if m, ok := out.(map[string]any); ok && m["error"] != nil {
		m["usage"] = signature(t) // so it can try again, right
	}
	return out, action
}

func missingArgs(t *stewardTool, args map[string]any) []string {
	req, _ := t.params["required"].([]string)
	out := []string{}
	for _, k := range req {
		v, ok := args[k]
		if !ok || v == nil || v == "" {
			out = append(out, k)
		}
	}
	return out
}

// ---- compact results: fewer tokens for the model to read

var noisyKeys = map[string]bool{"color": true, "emoji": true, "created": true, "createdAt": true, "updated": true,
	"updatedAt": true, "voice": true, "avatar": true, "etag": true, "href": true, "uid": true, "file": true, "path": true}

// compactResult turns an action's answer into short JSON: no empty fields, no
// colours or bookkeeping, and times as words ("Fri 27 Nov 14:05") instead of
// milliseconds (which small models are bad at working out).
func compactResult(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	b, _ := json.Marshal(compactValue(v, ""))
	if len(b) > 9000 {
		b = append(b[:9000], []byte("…(cut short: ask for less)")...)
	}
	return string(b)
}

var timeKey = regexp.MustCompile(`(?i)(time|at|start|end|due|since|until|when|last|next|done|from|to)$`)

func compactValue(v any, key string) any {
	switch x := v.(type) {
	case map[string]any:
		out := map[string]any{}
		for k, val := range x {
			if noisyKeys[k] {
				continue
			}
			c := compactValue(val, k)
			if isEmptyValue(c) {
				continue
			}
			out[k] = c
		}
		return out
	case []any:
		out := []any{}
		for _, e := range x {
			if c := compactValue(e, key); !isEmptyValue(c) {
				out = append(out, c)
			}
		}
		if len(out) > 60 {
			out = append(out[:60], fmt.Sprintf("…and %d more", len(out)-60))
		}
		return out
	case float64:
		// Unix milliseconds between 2017 and 2049 in a time-ish field: say it as words.
		if x > 1.5e12 && x < 2.5e12 && x == math.Trunc(x) && timeKey.MatchString(key) {
			t := time.UnixMilli(int64(x))
			if t.Hour() == 0 && t.Minute() == 0 {
				return t.Format("Mon 2 Jan 2006")
			}
			return t.Format("Mon 2 Jan 2006 15:04")
		}
		return x
	}
	return v
}

func isEmptyValue(v any) bool {
	switch x := v.(type) {
	case nil:
		return true
	case string:
		return x == ""
	case []any:
		return len(x) == 0
	case map[string]any:
		return len(x) == 0
	}
	return false
}
