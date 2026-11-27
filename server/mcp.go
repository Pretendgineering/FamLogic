package main

// FamLogic for outside AI agents: the same skills and actions Steward uses, over
// the Model Context Protocol (MCP), so an agent like Hermes, Claude Desktop or any
// MCP client can look after the family's chores, calendar and lists with an API
// key (Settings → AI). It's small on purpose: the agent sees three tools (the skill
// index, open_skill and act) and opens only the skills it needs, so its prompt
// doesn't fill up with FamLogic.
//
// POST /mcp takes JSON-RPC 2.0 (the "streamable HTTP" transport, answering with
// plain JSON). The key is "Authorization: Bearer fl_..."; each action needs the
// key's scope for it (chores, calendar, lists...), as the HTTP API does.
//
// GET /api/v1/agent-skill is a Hermes/agentskills.io skill (SKILL.md) that teaches
// an agent FamLogic once, instead of with every request.

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"slices"
	"strings"
)

// actionScope: the key scope each action needs.
var actionScope = map[string]string{
	"list_people": "people", "read_feed": "feed",
	"list_chores": "chores", "add_chore": "chores", "complete_chore": "chores", "list_rewards": "chores",
	"list_events": "calendar", "add_event": "calendar",
	"list_lists": "lists", "add_to_list": "lists", "make_list": "lists", "tick_list_item": "lists",
	"list_meals": "meals", "plan_meal": "meals", "add_recipe": "meals", "shop_recipe": "meals",
	"list_profiles": "profiles", "set_profile": "profiles",
	"get_home": "home", "set_home": "home",
	"tv_quiet": "tv", "tv_message": "tv",
	"list_meetings": "meetings", "create_meeting": "meetings", "update_meeting": "meetings",
	"read_vault_guide": "notes", "list_notes": "notes", "read_note": "notes", "write_note": "notes",
}

type rpcReq struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

func rpcResult(id json.RawMessage, result any) map[string]any {
	return map[string]any{"jsonrpc": "2.0", "id": id, "result": result}
}

func rpcError(id json.RawMessage, code int, msg string) map[string]any {
	return map[string]any{"jsonrpc": "2.0", "id": id, "error": map[string]any{"code": code, "message": msg}}
}

// skillIndex: the skills a key may use, one line each.
func skillIndex(scopes []string) string {
	var b strings.Builder
	for _, sk := range stewardSkills {
		ok := false
		for _, a := range sk.Actions {
			ok = ok || slices.Contains(scopes, actionScope[a])
		}
		if ok {
			b.WriteString("- " + sk.Name + ": " + sk.Summary + "\n")
		}
	}
	return strings.TrimRight(b.String(), "\n")
}

const mcpInstructions = `FamLogic is this family's self-hosted app: chores (Upkeep), the calendar (Datebook), lists and to-dos (Docket), meals and recipes (Pantry), profiles, the home, the TV and family meetings. Open a skill with open_skill to get its rules and actions, then use act. Everything you do is logged under your key's name and goes through the family's permissions. Keep what you tell the family short.`

func (s *Server) handleMCP(w http.ResponseWriter, r *http.Request) {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || !strings.HasPrefix(strings.TrimSpace(token), keyPrefix) {
		w.Header().Set("WWW-Authenticate", `Bearer realm="FamLogic"`)
		writeError(w, http.StatusUnauthorized, "send a FamLogic API key (Settings → AI) as Authorization: Bearer fl_...")
		return
	}
	if !s.store.Modules().Enabled["agents"] {
		writeError(w, http.StatusForbidden, "AI & API access (Steward) is switched off for this family")
		return
	}
	k := s.store.useKey(strings.TrimSpace(token), r)
	if k == nil {
		writeError(w, http.StatusUnauthorized, "unknown, expired or revoked API key")
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeError(w, http.StatusBadRequest, "couldn't read the request")
		return
	}
	// A single request, or a batch.
	var reqs []rpcReq
	batch := strings.HasPrefix(strings.TrimSpace(string(raw)), "[")
	if batch {
		err = json.Unmarshal(raw, &reqs)
	} else {
		var one rpcReq
		err = json.Unmarshal(raw, &one)
		reqs = []rpcReq{one}
	}
	if err != nil {
		writeJSON(w, http.StatusBadRequest, rpcError(nil, -32700, "that isn't JSON-RPC"))
		return
	}
	out := []map[string]any{}
	for _, req := range reqs {
		if resp := s.mcpOne(k, req); resp != nil {
			out = append(out, resp)
		}
	}
	if len(out) == 0 {
		w.WriteHeader(http.StatusAccepted) // only notifications
		return
	}
	if batch {
		writeJSON(w, http.StatusOK, out)
	} else {
		writeJSON(w, http.StatusOK, out[0])
	}
}

func (s *Server) mcpOne(k *APIKey, req rpcReq) map[string]any {
	if len(req.ID) == 0 { // a notification (initialized, cancelled): nothing to say back
		return nil
	}
	switch req.Method {
	case "initialize":
		var p struct {
			ProtocolVersion string `json:"protocolVersion"`
		}
		_ = json.Unmarshal(req.Params, &p)
		pv := p.ProtocolVersion
		if pv == "" {
			pv = "2025-06-18"
		}
		return rpcResult(req.ID, map[string]any{
			"protocolVersion": pv,
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": "famlogic", "title": "FamLogic", "version": version},
			"instructions":    mcpInstructions + "\nSkills:\n" + skillIndex(k.Scopes),
		})
	case "ping":
		return rpcResult(req.ID, map[string]any{})
	case "tools/list":
		tools := []map[string]any{}
		for _, t := range skillTools() {
			fn := t["function"].(map[string]any)
			tools = append(tools, map[string]any{"name": fn["name"], "description": fn["description"], "inputSchema": fn["parameters"]})
		}
		tools = append(tools, map[string]any{"name": "list_skills", "description": "FamLogic's skills (what each covers). Open one with open_skill.",
			"inputSchema": obj(map[string]any{})})
		return rpcResult(req.ID, map[string]any{"tools": tools})
	case "tools/call":
		var p struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		}
		if json.Unmarshal(req.Params, &p) != nil {
			return rpcError(req.ID, -32602, "bad params")
		}
		text, isErr := s.mcpCall(k, p.Name, p.Arguments)
		return rpcResult(req.ID, map[string]any{"content": []map[string]any{{"type": "text", "text": text}}, "isError": isErr})
	}
	return rpcError(req.ID, -32601, "unknown method "+req.Method)
}

// mcpCall runs one tool for an agent, within its key's scopes.
func (s *Server) mcpCall(k *APIKey, name string, args map[string]any) (string, bool) {
	if name == "list_skills" {
		return "Skills:\n" + skillIndex(k.Scopes), false
	}
	action := name
	if name == "act" {
		action = argStr(args, "action")
	}
	if name != "open_skill" {
		if sc := actionScope[action]; sc == "" || !slices.Contains(k.Scopes, sc) {
			return fmt.Sprintf("this key may not do %s (it needs the %q scope; Settings → AI → keys)", action, actionScope[action]), true
		}
	}
	b, _ := json.Marshal(args)
	result, _ := s.runCall(agentMember(k), name, string(b))
	out := compactResult(result)
	m, _ := result.(map[string]any)
	return out, m != nil && m["error"] != nil
}

// GET /api/v1/agent-skill: a skill folder's SKILL.md for Hermes (agentskills.io format).
func (s *Server) handleAgentSkill(w http.ResponseWriter, r *http.Request) {
	base := s.baseURL(r)
	var b strings.Builder
	fmt.Fprintf(&b, `---
name: famlogic
description: Look after the %s's FamLogic (self-hosted family app) - chores, calendar, shopping lists and to-dos, meals and recipes, profiles, the home, the family TV and family meetings. Use when the family asks to add, change, tick off or check any of these.
---

# FamLogic

FamLogic is the family's own app, running at %s. You reach it through its MCP
server, which is the easiest way: add it once to your MCP settings.

    mcp_servers:
      famlogic:
        url: %s/mcp
        headers:
          Authorization: "Bearer ${FAMLOGIC_KEY}"

Set FAMLOGIC_KEY to an API key from FamLogic's console (Settings, AI, Keys for
agents). The key decides what you may do (chores, calendar, lists...), and
everything you do is logged under its name.

## How to work

1. Call list_skills (or read the server's instructions) to see FamLogic's skills.
2. open_skill the one you need: it gives you that skill's rules and its actions.
3. act with an action's name and its arguments. Errors say what was missing.

Only open skills you need; each is a few hundred tokens.

## The family's rules (always)

- Keep messages to the family short and plain. Children may be reading.
- Never say something was done unless an action said so.
- Only tick off a chore for the person who did it, or when a parent says so.
- Only give stars (points) when a parent asks.
- If you're unsure who someone means, ask.

## Without MCP

The same things are in the HTTP API: %s/api/v1/openapi.json (Bearer key).
`, s.familyName()+func() string {
		if strings.HasSuffix(strings.ToLower(s.familyName()), "family") {
			return ""
		}
		return " family"
	}(), base, base, base)
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="SKILL.md"`)
	io.WriteString(w, b.String())
}
