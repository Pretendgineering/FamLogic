package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestScreensaverPhotos(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	png := append([]byte("\x89PNG\r\n\x1a\n"), make([]byte, 64)...)
	post := func(token string) int {
		req, _ := http.NewRequest("POST", e.srv.URL+"/api/display/photos?name=beach.png", bytes.NewReader(png))
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if post(f.kid) != 403 || post(f.mom) != 200 {
		t.Fatal("photo upload rules")
	}
	_, b := e.status("GET", "/api/display/board", f.kid, nil)
	photos := b["photos"].([]any)
	if len(photos) != 1 {
		t.Fatalf("photos: %v", photos)
	}
	id := photos[0].(map[string]any)["id"].(string)
	if st, _ := e.status("DELETE", "/api/display/photos/"+id, f.mom, nil); st != 204 {
		t.Fatalf("delete: %d", st)
	}
}

func TestResolveStream(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/feed":
			w.Header().Set("Content-Type", "application/rss+xml")
			w.Write([]byte(`<rss><channel><item><enclosure url="https://example.com/a.jpg" type="image/jpeg"/></item><item><description>&lt;img src="https://example.com/b.jpg"&gt;</description></item></channel></rss>`))
		case "/page":
			w.Write([]byte(`<html><img src="/c.jpg"><img src="/logo.svg"></html>`))
		case "/api/shared-links/me":
			w.Write([]byte(`{"assets":[{"id":"x1","type":"IMAGE"},{"id":"v1","type":"VIDEO"}]}`))
		default:
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write([]byte{0xff, 0xd8})
		}
	}))
	defer srv.Close()
	if p, _ := resolveStream(srv.URL + "/feed"); len(p) != 2 {
		t.Fatalf("feed: %v", p)
	}
	if p, _ := resolveStream(srv.URL + "/page"); len(p) != 1 || p[0] != srv.URL+"/c.jpg" {
		t.Fatalf("page: %v", p)
	}
	if p, _ := resolveStream(srv.URL + "/share/abc"); len(p) != 1 {
		t.Fatalf("immich: %v", p)
	}
	if p, _ := resolveStream(srv.URL + "/cam.jpg"); len(p) != 1 {
		t.Fatalf("image: %v", p)
	}
}
