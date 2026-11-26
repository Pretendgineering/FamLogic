package main

import (
	"bytes"
	"io"
	"net/http"
	"testing"
	"time"
)

func TestDisplay(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chores": true, "display": true}, "setupDone": true})
	if st, _ := e.status("POST", "/api/display/quiet", f.kid, map[string]any{"minutes": 30}); st != 403 {
		t.Fatalf("children don't start quiet time: %d", st)
	}
	if st, _ := e.status("POST", "/api/display/quiet", f.mom, map[string]any{"minutes": 60}); st != 200 {
		t.Fatalf("quiet: %d", st)
	}
	if st, _ := e.status("POST", "/api/display/messages", f.mom, map[string]any{"text": "Inside voices please"}); st != 200 {
		t.Fatalf("message: %d", st)
	}
	_, b := e.status("GET", "/api/display/board", f.kid, nil)
	q := b["quiet"].(map[string]any)
	if q["on"] != true || q["by"] != "Mom" || len(b["messages"].([]any)) != 1 || b["chores"] == nil {
		t.Fatalf("board: %v", b)
	}
	e.status("DELETE", "/api/display/quiet", f.dad, nil)
	_, b = e.status("GET", "/api/display/board", f.kid, nil)
	if b["quiet"].(map[string]any)["on"] != false {
		t.Fatal("quiet should be over")
	}
}

func TestDisplayAppPictures(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	if st, _ := e.status("PUT", "/api/display/apps", f.kid, map[string]any{"apps": []map[string]string{{"pkg": "org.jellyfin.androidtv", "label": "Jellyfin"}}}); st != 403 {
		t.Fatalf("children don't list apps: %d", st)
	}
	if st, _ := e.status("PUT", "/api/display/apps", f.mom, map[string]any{"apps": []map[string]string{{"pkg": "org.jellyfin.androidtv", "label": "Jellyfin"}, {"pkg": "../etc", "label": "x"}}}); st != 200 {
		t.Fatalf("apps: %d", st)
	}
	png := append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 64)...)
	put := func(token string, body []byte) int {
		req, _ := http.NewRequest("PUT", e.srv.URL+"/api/display/apps/org.jellyfin.androidtv/image", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if put(f.kid, png) != 403 || put(f.mom, []byte("not a picture")) != 400 || put(f.mom, png) != 200 {
		t.Fatal("picture upload rules")
	}
	req, _ := http.NewRequest("GET", e.srv.URL+"/api/display/apps/org.jellyfin.androidtv/image", nil)
	req.Header.Set("Authorization", "Bearer "+f.kid)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !bytes.Equal(got, png) {
		t.Fatalf("picture back: %d", resp.StatusCode)
	}
	_, b := e.status("GET", "/api/display/board", f.kid, nil)
	apps := b["apps"].([]any)
	if len(apps) != 1 || apps[0].(map[string]any)["image"] == nil {
		t.Fatalf("apps: %v", apps)
	}
}

func TestSunTimes(t *testing.T) {
	// London, midsummer: sunrise about 04:43 UTC, sunset about 20:21 UTC.
	rise, set, ok := sunTimes(time.Date(2026, 6, 21, 12, 0, 0, 0, time.UTC), 51.5, -0.12)
	if !ok || rise.UTC().Hour() != 3 && rise.UTC().Hour() != 4 || set.UTC().Hour() != 20 {
		t.Fatalf("sun: %v %v %v", rise.UTC(), set.UTC(), ok)
	}
}
