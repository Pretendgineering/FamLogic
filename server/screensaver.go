package main

// The TV's screen saver: the family's photos (uploaded on the console, a whole folder
// at a time if you like), or pictures from a link (an Immich shared album, an RSS or
// Atom feed of photos, a web page's pictures, or one image that changes, like a
// webcam), as well as FamLogic's own family clock and themes, which need nothing here.

import (
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
)

// DisplayPhoto is one photo in the family's screen saver album.
type DisplayPhoto struct {
	ID    string `json:"id"`
	Name  string `json:"name,omitempty"`
	Added int64  `json:"added"`
}

const maxDisplayPhotos = 500

func (s *Server) displayPhotoPath(id string) string {
	return filepath.Join(filepath.Dir(s.store.path), "display", "photos", id+".img")
}

var photoIDRe = regexp.MustCompile(`^p[0-9a-f]{10}$`)

// POST /api/display/photos (the picture's bytes; ?name=): adds a photo (parents).
func (s *Server) handleAddDisplayPhoto(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) {
		writeError(w, http.StatusForbidden, "parents choose the TV's photos")
		return
	}
	if len(s.store.Display().Photos) >= maxDisplayPhotos {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("the album holds %d photos; remove some first", maxDisplayPhotos))
		return
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, 12<<20+1))
	if err != nil || len(data) == 0 || len(data) > 12<<20 {
		writeError(w, http.StatusBadRequest, "a photo of 12 MB at most")
		return
	}
	if t := http.DetectContentType(data); t != "image/png" && t != "image/jpeg" && t != "image/webp" && t != "image/gif" {
		writeError(w, http.StatusBadRequest, "a PNG, JPEG, WebP or GIF picture")
		return
	}
	p := DisplayPhoto{ID: "p" + randomHex(5), Name: strings.TrimSpace(r.URL.Query().Get("name")), Added: time.Now().UnixMilli()}
	if len(p.Name) > 120 {
		p.Name = p.Name[:120]
	}
	path := s.displayPhotoPath(p.ID)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil || os.WriteFile(path, data, 0o644) != nil {
		writeError(w, http.StatusInternalServerError, "couldn't save it")
		return
	}
	_ = s.store.updateDisplay(func(d *DisplayState) { d.Photos = append(d.Photos, p) })
	writeJSON(w, http.StatusOK, p)
}

// DELETE /api/display/photos/{id} (parents).
func (s *Server) handleDeleteDisplayPhoto(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) {
		writeError(w, http.StatusForbidden, "parents choose the TV's photos")
		return
	}
	id := r.PathValue("id")
	if !photoIDRe.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	_ = os.Remove(s.displayPhotoPath(id))
	_ = s.store.updateDisplay(func(d *DisplayState) {
		d.Photos = slices.DeleteFunc(d.Photos, func(p DisplayPhoto) bool { return p.ID == id })
	})
	w.WriteHeader(http.StatusNoContent)
}

// GET /api/display/photos/{id}: the photo.
func (s *Server) handleDisplayPhoto(w http.ResponseWriter, r *http.Request, me *Member) {
	id := r.PathValue("id")
	if !photoIDRe.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	data, err := os.ReadFile(s.displayPhotoPath(id))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", http.DetectContentType(data))
	w.Header().Set("Cache-Control", "private, max-age=86400")
	_, _ = w.Write(data)
}

// PUT /api/display/stream {url}: the photo link for the screen saver (parents; "" for none).
func (s *Server) handleSetDisplayStream(w http.ResponseWriter, r *http.Request, me *Member) {
	if !mayControlDisplays(me) {
		writeError(w, http.StatusForbidden, "parents choose the TV's photos")
		return
	}
	var body struct {
		URL string `json:"url"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	link := strings.TrimSpace(body.URL)
	if link != "" {
		u, err := url.Parse(link)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			writeError(w, http.StatusBadRequest, "paste the album's link (it starts with https://)")
			return
		}
		if _, err := s.streamPictures(link, true); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	_ = s.store.updateDisplay(func(d *DisplayState) { d.Stream = link })
	writeJSON(w, http.StatusOK, map[string]any{"stream": link})
}

// GET /api/display/stream: the pictures behind the photo link, for the TV to show.
func (s *Server) handleDisplayStream(w http.ResponseWriter, r *http.Request, me *Member) {
	link := s.store.Display().Stream
	if link == "" {
		writeJSON(w, http.StatusOK, map[string]any{"pictures": []string{}})
		return
	}
	pics, err := s.streamPictures(link, false)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"pictures": []string{}, "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"pictures": pics})
}

var (
	streamClient = &http.Client{Timeout: 20 * time.Second}
	streamCache  = struct {
		sync.Mutex
		link string
		at   time.Time
		pics []string
	}{}
	immichShareRe = regexp.MustCompile(`^(https?://[^/]+)/share/([A-Za-z0-9_\-]+)`)
	imgSrcRe      = regexp.MustCompile(`(?i)<img[^>]+src=["']([^"']+)["']`)
	ogImageRe     = regexp.MustCompile(`(?i)<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']`)
)

// streamPictures lists a photo link's pictures (cached for 30 minutes; fresh when checking a new link).
func (s *Server) streamPictures(link string, fresh bool) ([]string, error) {
	streamCache.Lock()
	if !fresh && streamCache.link == link && time.Since(streamCache.at) < 30*time.Minute {
		pics := streamCache.pics
		streamCache.Unlock()
		return pics, nil
	}
	streamCache.Unlock()
	pics, err := resolveStream(link)
	if err != nil {
		return nil, err
	}
	if len(pics) == 0 {
		return nil, errors.New("no pictures found at that link")
	}
	if len(pics) > 1000 {
		pics = pics[:1000]
	}
	streamCache.Lock()
	streamCache.link, streamCache.at, streamCache.pics = link, time.Now(), pics
	streamCache.Unlock()
	return pics, nil
}

func streamGet(u string) (*http.Response, error) {
	req, err := http.NewRequest(http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "FamLogic screen saver")
	res, err := streamClient.Do(req)
	if err != nil {
		return nil, errors.New("couldn't reach that link")
	}
	if res.StatusCode != http.StatusOK {
		res.Body.Close()
		return nil, fmt.Errorf("that link answered %d", res.StatusCode)
	}
	return res, nil
}

// resolveStream understands an Immich shared link, an RSS/Atom/media feed, a web
// page (its pictures), or a single picture (shown, and fetched again, as it is).
func resolveStream(link string) ([]string, error) {
	if m := immichShareRe.FindStringSubmatch(link); m != nil {
		return immichShare(m[1], m[2])
	}
	res, err := streamGet(link)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	ct := strings.ToLower(res.Header.Get("Content-Type"))
	if strings.HasPrefix(ct, "image/") {
		return []string{link}, nil
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if err != nil {
		return nil, errors.New("the download didn't finish")
	}
	base, _ := url.Parse(link)
	abs := func(u string) string {
		if p, err := base.Parse(strings.TrimSpace(u)); err == nil && (p.Scheme == "http" || p.Scheme == "https") {
			return p.String()
		}
		return ""
	}
	out := []string{}
	add := func(u string) {
		if a := abs(u); a != "" && !slices.Contains(out, a) {
			out = append(out, a)
		}
	}
	text := string(b)
	if strings.Contains(ct, "xml") || strings.Contains(text[:min(len(text), 400)], "<rss") || strings.Contains(text[:min(len(text), 400)], "<feed") {
		for _, u := range feedPictures(b) {
			add(u)
		}
		return out, nil
	}
	for _, m := range ogImageRe.FindAllStringSubmatch(text, -1) {
		add(m[1])
	}
	for _, m := range imgSrcRe.FindAllStringSubmatch(text, -1) {
		if l := strings.ToLower(m[1]); strings.Contains(l, ".svg") || strings.Contains(l, "icon") || strings.Contains(l, "logo") || strings.HasPrefix(l, "data:") {
			continue
		}
		add(m[1])
	}
	return out, nil
}

// feedPictures pulls picture links from an RSS or Atom feed: enclosures, media:content,
// media:thumbnail and <img> in the descriptions.
func feedPictures(b []byte) []string {
	out := []string{}
	dec := xml.NewDecoder(strings.NewReader(string(b)))
	dec.Strict = false
	for {
		t, err := dec.Token()
		if err != nil {
			break
		}
		switch el := t.(type) {
		case xml.StartElement:
			attr := func(name string) string {
				for _, a := range el.Attr {
					if a.Name.Local == name {
						return a.Value
					}
				}
				return ""
			}
			switch el.Name.Local {
			case "enclosure", "content", "thumbnail":
				typ := attr("type")
				if u := attr("url"); u != "" && (typ == "" || strings.HasPrefix(typ, "image/") || el.Name.Local == "thumbnail") {
					out = append(out, u)
				}
			case "link":
				if strings.HasPrefix(attr("type"), "image/") {
					out = append(out, attr("href"))
				}
			}
		case xml.CharData:
			for _, m := range imgSrcRe.FindAllStringSubmatch(string(el), -1) {
				out = append(out, m[1])
			}
		}
	}
	return out
}

// immichShare lists a shared Immich album's photos as preview links the TV can load.
func immichShare(host, key string) ([]string, error) {
	res, err := streamGet(host + "/api/shared-links/me?key=" + url.QueryEscape(key))
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	var share struct {
		Assets []struct {
			ID   string `json:"id"`
			Type string `json:"type"`
		} `json:"assets"`
		Album *struct {
			Assets []struct {
				ID   string `json:"id"`
				Type string `json:"type"`
			} `json:"assets"`
		} `json:"album"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 16<<20)).Decode(&share); err != nil {
		return nil, errors.New("that doesn't look like an Immich shared link")
	}
	assets := share.Assets
	if share.Album != nil && len(share.Album.Assets) > 0 {
		assets = share.Album.Assets
	}
	out := []string{}
	for _, a := range assets {
		if a.Type == "" || a.Type == "IMAGE" {
			out = append(out, fmt.Sprintf("%s/api/assets/%s/thumbnail?size=preview&key=%s", host, a.ID, url.QueryEscape(key)))
		}
	}
	return out, nil
}
