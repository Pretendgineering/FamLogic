package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The test set's plumbing, with a scripted "model" that knows one trick: it looks up
// the lists, then adds milk to Shopping, using the skills-mode tools (act).
func TestEvalHarness(t *testing.T) {
	var prompts []string
	llm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Messages []llmMsg         `json:"messages"`
			Tools    []map[string]any `json:"tools"`
		}
		json.NewDecoder(r.Body).Decode(&req)
		prompts = append(prompts, req.Messages[0].Content)
		last := req.Messages[len(req.Messages)-1]
		call := func(args string) {
			fmt.Fprintf(w, `{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"1","type":"function","function":{"name":"act","arguments":%q}}]}}],"usage":{"prompt_tokens":700,"completion_tokens":20}}`, args)
		}
		switch {
		case last.Role == "user":
			if !strings.HasPrefix(last.Content, "[") || !strings.Contains(last.Content, "Talking: ") {
				t.Errorf("the note is missing: %q", last.Content)
			}
			call(`{"action":"list_lists"}`)
		case last.Role == "tool" && strings.Contains(last.Content, "Shopping") && !strings.Contains(last.Content, "Milk"):
			var got struct{ Lists []struct{ ID, Title string } }
			json.Unmarshal([]byte(last.Content), &got)
			id := ""
			for _, l := range got.Lists {
				if l.Title == "Shopping" {
					id = l.ID
				}
			}
			call(fmt.Sprintf(`{"action":"add_to_list","args":{"list_id":%q,"text":"Milk"}}`, id))
		default:
			fmt.Fprint(w, `{"choices":[{"message":{"role":"assistant","content":"<think>easy</think>Added milk."}}],"usage":{"prompt_tokens":800,"completion_tokens":5}}`)
		}
	}))
	defer llm.Close()
	var progress int
	rep := runEval(context.Background(), StewardEndpoint{URL: llm.URL, Model: "fake", Source: "server"}, "skills", []string{"quick"}, func(evalReport) { progress++ })
	byID := map[string]evalResult{}
	for _, r := range rep.Results {
		byID[r.ID] = r
	}
	if r := byID["shopping-add"]; !r.Pass || r.Reply != "Added milk." {
		t.Fatalf("shopping-add: %+v", r)
	}
	if progress != rep.Total || rep.Total < 5 || !rep.Done {
		t.Fatalf("report: %d of %d, progress %d", rep.Passed, rep.Total, progress)
	}
	// The system prompt was the same every time (so a model server can cache it).
	for _, p := range prompts {
		if p != prompts[0] {
			t.Fatal("the system prompt changed between requests")
		}
	}
	// The same model handed nothing on, and the stats add up.
	if tr := byID["shopping-add"].Turns[0]; tr.Rounds != 3 || tr.Prompt != 2200 || len(tr.Actions) != 2 {
		t.Fatalf("stats: %+v", tr)
	}
}

// The main model is down: the fallback answers. The main one hands a request on: same.
func TestStewardFallback(t *testing.T) {
	answer := func(text string) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprintf(w, `{"choices":[{"message":{"role":"assistant","content":%q}}]}`, text)
		}))
	}
	cloud := answer("From the cloud.")
	defer cloud.Close()
	handsOn := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"1","type":"function","function":{"name":"hand_off","arguments":"{\"reason\":\"hard\"}"}}]}}]}`)
	}))
	defer handsOn.Close()
	f := newChatFamily(t)
	s := f.e.app
	var mom Member
	for _, m := range s.store.Members() {
		if m.Name == "Mom" {
			mom = m
		}
	}
	say := []StewardMsg{{Role: "user", Text: "hello"}}
	s.store.SetSteward(StewardConfig{Enabled: true, URL: "http://127.0.0.1:1/v1", Model: "local", Source: "builtin",
		Fallback: &StewardEndpoint{URL: cloud.URL, Model: "big"}})
	reply, st, err := s.stewardAnswer(context.Background(), &mom, say, "")
	if err != nil || reply != "From the cloud." || !st.Fallback {
		t.Fatalf("down: %q %+v %v", reply, st, err)
	}
	s.store.SetSteward(StewardConfig{Enabled: true, URL: handsOn.URL, Model: "local", Source: "builtin", HandOff: true,
		Fallback: &StewardEndpoint{URL: cloud.URL, Model: "big"}})
	reply, st, err = s.stewardAnswer(context.Background(), &mom, say, "")
	if err != nil || reply != "From the cloud." || st.Why != "handed on" {
		t.Fatalf("handed on: %q %+v %v", reply, st, err)
	}
	// No fallback: the error comes back.
	s.store.SetSteward(StewardConfig{Enabled: true, URL: "http://127.0.0.1:1/v1", Model: "local"})
	if _, _, err := s.stewardAnswer(context.Background(), &mom, say, ""); err == nil {
		t.Fatal("no fallback: should fail")
	}
}

// An agent over MCP: tools/list, open_skill, then act within its key's scopes.
func TestMCP(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.cookie = f.owner
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chat": true, "chores": true, "todos": true, "agents": true}, "setupDone": true})
	_, k := e.status("POST", "/api/admin/keys", "", map[string]any{"name": "Hermes", "scopes": []string{"lists"}})
	key := k["key"].(string)
	call := func(body string) map[string]any {
		req, _ := http.NewRequest("POST", e.srv.URL+"/mcp", strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+key)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		out := map[string]any{}
		json.NewDecoder(res.Body).Decode(&out)
		return out
	}
	init := call(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}`)
	if ins := init["result"].(map[string]any)["instructions"].(string); !strings.Contains(ins, "lists:") || strings.Contains(ins, "chores:") {
		t.Fatalf("the skill index should show only what the key may use: %s", ins)
	}
	tl := call(`{"jsonrpc":"2.0","id":2,"method":"tools/list"}`)
	if n := len(tl["result"].(map[string]any)["tools"].([]any)); n != 3 {
		t.Fatalf("tools: %d", n)
	}
	text := func(out map[string]any) (string, bool) {
		r := out["result"].(map[string]any)
		return r["content"].([]any)[0].(map[string]any)["text"].(string), r["isError"].(bool)
	}
	if got, isErr := text(call(`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"act","arguments":{"action":"make_list","args":{"title":"Shopping"}}}}`)); isErr || !strings.Contains(got, "Shopping") {
		t.Fatalf("make_list: %s", got)
	}
	if got, isErr := text(call(`{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"act","arguments":{"action":"add_chore","args":{"title":"x"}}}}`)); !isErr || !strings.Contains(got, "chores") {
		t.Fatalf("out of scope: %s", got)
	}
}
