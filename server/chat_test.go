package main

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"mime/multipart"
	"net/http"
	"strconv"
	"testing"
)

type chatFamily struct {
	e                           *testEnv
	owner                       *http.Cookie
	mom, dad, kid, teen         string // tokens
	momID, dadID, kidID, teenID string
}

func newChatFamily(t *testing.T) *chatFamily {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	f := &chatFamily{e: e, owner: e.cookie}
	join := func(name, access string) (string, string) {
		e.cookie = f.owner
		_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": name, "access": access})
		e.cookie = nil
		_, out := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string), "name": name})
		return out["token"].(string), out["member"].(map[string]any)["id"].(string)
	}
	f.mom, f.momID = join("Mom", "")
	f.dad, f.dadID = join("Dad", "")
	f.kid, f.kidID = join("Kid", "younger_child")
	f.teen, f.teenID = join("Teen", "older_child")
	return f
}

func convIDs(out map[string]any) map[string]map[string]any {
	ids := map[string]map[string]any{}
	for _, c := range out["conversations"].([]any) {
		cv := c.(map[string]any)
		ids[cv["id"].(string)] = cv
	}
	return ids
}

func TestChat(t *testing.T) {
	f := newChatFamily(t)
	e := f.e

	// Everyone has the family chat.
	st, list := e.status("GET", "/api/chat", f.kid, nil)
	if st != 200 || convIDs(list)["family"] == nil {
		t.Fatalf("family chat: %d %v", st, list)
	}
	// Mom posts; everyone else gets an alert, Mom doesn't.
	_, before := e.do("GET", "/api/members?since=0", f.kid, nil)
	cursor := int64(before["cursor"].(float64))
	if st, _ := e.status("POST", "/api/chat/family/messages", f.mom, map[string]string{"text": "Dinner at 6"}); st != 200 {
		t.Fatalf("post: %d", st)
	}
	_, after := e.do("GET", "/api/members?since="+itoa(cursor), f.kid, nil)
	got := false
	for _, ev := range after["events"].([]any) {
		m := ev.(map[string]any)
		if m["type"] == "chat" && m["conv"] == "family" && m["note"] == "Dinner at 6" && m["name"] == "Mom" {
			got = true
		}
	}
	if !got {
		t.Fatalf("kid got no chat alert: %v", after["events"])
	}
	// Unread for the kid, not for Mom; reading clears it and shows as a receipt.
	_, list = e.do("GET", "/api/chat", f.kid, nil)
	if convIDs(list)["family"]["unread"].(float64) != 1 {
		t.Fatalf("unread: %v", convIDs(list)["family"])
	}
	_, msgs := e.do("GET", "/api/chat/family/messages", f.kid, nil)
	last := msgs["messages"].([]any)[0].(map[string]any)["id"].(float64)
	e.do("POST", "/api/chat/family/read", f.kid, map[string]any{"upTo": last})
	_, list = e.do("GET", "/api/chat", f.kid, nil)
	if convIDs(list)["family"]["unread"].(float64) != 0 {
		t.Fatal("still unread after reading")
	}
	_, msgs = e.do("GET", "/api/chat/family/messages", f.mom, nil)
	if msgs["reads"].(map[string]any)[f.kidID].(float64) != last {
		t.Fatalf("receipt: %v", msgs["reads"])
	}

	// One-to-one: the kid and the teen. Dad (a parent) can read it but not post; Mom's own chats with Dad stay private.
	st, dm := e.status("POST", "/api/chat/direct", f.kid, map[string]string{"to": f.teenID})
	if st != 200 || dm["kind"] != "direct" || dm["title"] != "Teen" {
		t.Fatalf("direct: %d %v", st, dm)
	}
	dmID := dm["id"].(string)
	if _, again := e.do("POST", "/api/chat/direct", f.teen, map[string]string{"to": f.kidID}); again["id"] != dmID {
		t.Fatal("the same pair should get the same chat")
	}
	e.do("POST", "/api/chat/"+dmID+"/messages", f.kid, map[string]string{"text": "hi"})
	_, list = e.do("GET", "/api/chat", f.dad, nil)
	if c := convIDs(list)[dmID]; c == nil || c["oversight"] != true {
		t.Fatalf("parent oversight: %v", c)
	}
	if st, _ := e.status("POST", "/api/chat/"+dmID+"/messages", f.dad, map[string]string{"text": "hello kids"}); st != 403 {
		t.Fatalf("parent posted in a child's chat: %d", st)
	}
	_, parents := e.do("POST", "/api/chat/direct", f.mom, map[string]string{"to": f.dadID})
	_, list = e.do("GET", "/api/chat", f.teen, nil)
	if convIDs(list)[parents["id"].(string)] != nil {
		t.Fatal("a child can see the parents' chat")
	}
	if st, _ := e.status("GET", "/api/chat/"+parents["id"].(string)+"/messages", f.teen, nil); st != 404 {
		t.Fatalf("child read parents' messages: %d", st)
	}

	// Groups: the creator is in it; others can't remove people, but can leave.
	st, g := e.status("POST", "/api/chat/groups", f.teen, map[string]any{"name": "Movie night", "members": []string{f.kidID, f.momID}})
	if st != 200 || g["title"] != "Movie night" || len(g["members"].([]any)) != 3 {
		t.Fatalf("group: %d %v", st, g)
	}
	gID := g["id"].(string)
	// A child's group waits for a parent: nobody can post, and parents are asked.
	if g["status"] != "pending" {
		t.Fatalf("a child's group should wait for a parent: %v", g)
	}
	if st, _ := e.status("POST", "/api/chat/"+gID+"/messages", f.teen, map[string]string{"text": "popcorn?"}); st != 403 {
		t.Fatalf("posted in a pending group: %d", st)
	}
	if st, _ := e.status("POST", "/api/chat/"+gID+"/moderate", f.teen, map[string]string{"action": "approve"}); st != 403 {
		t.Fatalf("a child approved: %d", st)
	}
	if st, ok := e.status("POST", "/api/chat/"+gID+"/moderate", f.dad, map[string]string{"action": "approve"}); st != 200 || ok["status"] != nil {
		t.Fatalf("approve: %d %v", st, ok)
	}
	if st, _ := e.status("POST", "/api/chat/"+gID+"/messages", f.teen, map[string]string{"text": "popcorn?"}); st != 200 {
		t.Fatalf("post after approval: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/chat/"+gID, f.kid, map[string]any{"remove": []string{f.momID}}); st != 403 {
		t.Fatalf("kid removed someone: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/chat/"+gID, f.kid, map[string]any{"remove": []string{f.kidID}}); st != 204 {
		t.Fatalf("kid left: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/chat/"+gID, f.teen, map[string]any{"name": "Film club", "add": []string{f.dadID}}); st != 200 {
		t.Fatalf("rename/add: %d", st)
	}
	// A child adding people sends it back to the parents; Mom says yes.
	e.do("POST", "/api/chat/"+gID+"/moderate", f.mom, map[string]string{"action": "approve"})
	// Icons: parents choose the family chat's; people in a group choose its.
	if st, _ := e.status("PATCH", "/api/chat/family", f.teen, map[string]string{"emoji": "🏡"}); st != 403 {
		t.Fatalf("a child set the family icon: %d", st)
	}
	if st, fam := e.status("PATCH", "/api/chat/family", f.mom, map[string]string{"emoji": "🏡", "color": "#3cb44b"}); st != 200 || fam["emoji"] != "🏡" || fam["color"] != "#3cb44b" {
		t.Fatalf("family icon: %d %v", st, fam)
	}
	if st, gi := e.status("PATCH", "/api/chat/"+gID, f.teen, map[string]string{"emoji": "🎬"}); st != 200 || gi["emoji"] != "🎬" {
		t.Fatalf("group icon: %d %v", st, gi)
	}
	// A parents-only group starts right away.
	if _, pg := e.do("POST", "/api/chat/groups", f.mom, map[string]any{"name": "Gifts", "members": []string{f.dadID}}); pg["status"] != nil {
		t.Fatalf("parents' group pending: %v", pg)
	}

	// Photos: shrunk, re-encoded, and only for people who can read the chat.
	img := image.NewRGBA(image.Rect(0, 0, 2400, 1200))
	for x := 0; x < 2400; x++ {
		img.Set(x, x%1200, color.RGBA{200, 50, 50, 255})
	}
	var pngBuf bytes.Buffer
	png.Encode(&pngBuf, img)
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	mw.WriteField("text", "look")
	fw, _ := mw.CreateFormFile("photo", "big.png")
	fw.Write(pngBuf.Bytes())
	mw.Close()
	req, _ := http.NewRequest("POST", e.srv.URL+"/api/chat/"+dmID+"/messages", &body)
	req.Header.Set("Authorization", "Bearer "+f.kid)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("photo post: %v %v", err, resp.StatusCode)
	}
	_, msgs = e.do("GET", "/api/chat/"+dmID+"/messages", f.teen, nil)
	all := msgs["messages"].([]any)
	photo := all[len(all)-1].(map[string]any)["photo"].(string)
	get := func(token string) (int, []byte) {
		req, _ := http.NewRequest("GET", e.srv.URL+"/api/chat-media/"+photo, nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, _ := http.DefaultClient.Do(req)
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		return resp.StatusCode, b
	}
	st2, data := get(f.teen)
	if st2 != 200 {
		t.Fatalf("photo for the other person: %d", st2)
	}
	if cfg, err := jpeg.DecodeConfig(bytes.NewReader(data)); err != nil || cfg.Width != 1600 || cfg.Height != 800 {
		t.Fatalf("photo not resized to 1600 wide: %+v %v", cfg, err)
	}
	if st2, _ := get(f.dad); st2 != 200 {
		t.Fatalf("parent can't see the child's photo: %d", st2)
	}
	_, mm := e.do("POST", "/api/chat/direct", f.mom, map[string]string{"to": f.dadID})
	_ = mm
	if st2, _ := get(""); st2 != 401 {
		t.Fatalf("photo without signing in: %d", st2)
	}

	// The main admin sees every conversation, read only.
	e.cookie = f.owner
	if st, all := e.status("GET", "/api/admin/chat", "", nil); st != 200 || len(all["conversations"].([]any)) < 4 {
		t.Fatalf("admin chat list: %d %v", st, all)
	}

	// Switched off: chat answers "off".
	e.do("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true}, "setupDone": true})
	e.cookie = nil
	if st, _ := e.status("GET", "/api/chat", f.mom, nil); st != 404 {
		t.Fatalf("chat while off: %d", st)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
