package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Pebble Index 01 ring: the Pebble phone app sends each recording to a webhook
// (one URL per gesture, see https://developer.repebble.com/index-01/webhooks/).
// Each person gets secret URLs that do one thing each:
//
//	/api/index/<token>/sos      raise an SOS (what was said becomes the note)
//	/api/index/<token>/checkin  check in with what was said
//	/api/index/<token>/remind   "when I get home, take the bins out": a place reminder
//
// The token in the URL identifies the person. Requests may also be signed
// (HMAC-SHA256, protocol version 1) with the person's signing secret, and are
// then checked; deliveries seen before are acknowledged without acting twice.

const (
	indexMaxBody   = 16 << 20 // audio of a few minutes, if they send it
	indexMaxSkew   = 5 * time.Minute
	indexSeenFor   = 30 * time.Minute
	indexActionSOS = "sos"
)

var errIndexNoPlace = errors.New("say which fence, e.g. \"when I get home …\"")

// IndexSettings returns (creating if needed) a member's ring token and signing secret.
func (s *Store) IndexSettings(memberID string, reset bool) (token, secret string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return "", "", ErrNotFound
	}
	if m.IndexToken == "" || reset {
		m.IndexToken = randomString(18)
		m.IndexSecret = randomString(32)
		if err := s.saveLocked(); err != nil {
			return "", "", err
		}
	}
	return m.IndexToken, m.IndexSecret, nil
}

// memberByIndexToken finds who a ring URL belongs to.
func (s *Store) memberByIndexToken(token string) (*Member, bool) {
	if len(token) < 16 {
		return nil, false
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, m := range s.d.Members {
		if m.IndexToken != "" && subtle.ConstantTimeCompare([]byte(m.IndexToken), []byte(token)) == 1 {
			cp := *m
			return &cp, true
		}
	}
	return nil, false
}

// ReminderFromSpeech turns "when I get home remind me to take the bins out" into
// a reminder: it finds one of the member's fences named in the text, and treats
// "leave/leaving/left" as a departure.
func (s *Store) ReminderFromSpeech(memberID, text string) (*Reminder, error) {
	lower := strings.ToLower(text)
	best := ""
	bestID := ""
	for _, p := range s.PlacesFor(memberID) {
		if n := strings.ToLower(p.Name); n != "" && strings.Contains(lower, n) && len(n) > len(best) {
			best, bestID = n, p.ID
		}
	}
	if bestID == "" {
		return nil, errIndexNoPlace
	}
	leaving := false
	for _, w := range []string{"leave", "leaving", "left", "head out", "heading out"} {
		if strings.Contains(lower, w) {
			leaving = true
		}
	}
	return s.AddReminder(memberID, bestID, text, leaving)
}

// indexDeliveries remembers recent delivery ids so retries don't act twice.
type indexDeliveries struct {
	mu   sync.Mutex
	seen map[string]time.Time
}

func (d *indexDeliveries) firstTime(id string) bool {
	if id == "" {
		return true
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now()
	for k, t := range d.seen {
		if now.Sub(t) > indexSeenFor {
			delete(d.seen, k)
		}
	}
	if _, ok := d.seen[id]; ok {
		return false
	}
	d.seen[id] = now
	return true
}

// verifyIndexSignature checks X-Index-Signature over "v1\n<ts>\n<delivery>\n<trigger>\n<test>\n" + body.
func verifyIndexSignature(r *http.Request, body []byte, secret string) error {
	version := r.Header.Get("X-Index-Webhook-Version")
	if version != "1" {
		return errors.New("unsupported webhook version")
	}
	ts, err := strconv.ParseInt(r.Header.Get("X-Index-Timestamp"), 10, 64)
	if err != nil {
		return errors.New("invalid timestamp")
	}
	if d := time.Since(time.Unix(ts, 0)); d > indexMaxSkew || d < -indexMaxSkew {
		return errors.New("expired request")
	}
	test := "0"
	if r.Header.Get("X-Index-Test") == "true" {
		test = "1"
	}
	prefix := "v" + version + "\n" + strconv.FormatInt(ts, 10) + "\n" + r.Header.Get("X-Index-Delivery") + "\n" +
		r.Header.Get("X-Index-Trigger") + "\n" + test + "\n"
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(prefix))
	mac.Write(body)
	want := hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(want), []byte(strings.ToLower(r.Header.Get("X-Index-Signature")))) {
		return errors.New("invalid signature")
	}
	return nil
}

// indexTranscription reads the "transcription" field from the multipart body (audio is ignored).
func indexTranscription(r *http.Request, body []byte) string {
	_, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || params["boundary"] == "" {
		return ""
	}
	mr := multipart.NewReader(bytes.NewReader(body), params["boundary"])
	for {
		part, err := mr.NextPart()
		if err != nil {
			return ""
		}
		if part.FormName() == "transcription" {
			b, _ := io.ReadAll(io.LimitReader(part, 4096))
			return strings.TrimSpace(string(b))
		}
	}
}

func (s *Server) handleIndexWebhook(w http.ResponseWriter, r *http.Request) {
	m, ok := s.store.memberByIndexToken(r.PathValue("token"))
	if !ok {
		writeError(w, http.StatusNotFound, "unknown ring link")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, indexMaxBody))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "too big")
		return
	}
	// Signing is optional in the Pebble app; when a request is signed, it must check out.
	if r.Header.Get("X-Index-Signature") != "" {
		if err := verifyIndexSignature(r, body, m.IndexSecret); err != nil {
			writeError(w, http.StatusUnauthorized, err.Error())
			return
		}
	}
	if !s.indexSeen.firstTime(m.ID + "/" + r.Header.Get("X-Index-Delivery")) {
		w.WriteHeader(http.StatusOK) // a retry of something already done
		return
	}
	if r.Header.Get("X-Index-Test") == "true" {
		writeJSON(w, http.StatusOK, map[string]string{"ok": "Thereabouts got the test for " + m.Name})
		return
	}
	text := indexTranscription(r, body)
	switch r.PathValue("action") {
	case indexActionSOS:
		_, err = s.store.StartSOSWith(m.ID, nil, SOSDetails{Note: text, Source: "ring"})
	case "checkin":
		if text == "" {
			text = "Checked in from my ring"
		}
		err = s.store.CheckIn(m.ID, text, nil)
	case "remind":
		_, err = s.store.ReminderFromSpeech(m.ID, text)
		if err != nil {
			// Tell them on their phone, so the thought isn't lost.
			s.store.Notice(m.ID, "Couldn't set that reminder: "+err.Error(), text)
			err = nil
		}
	default:
		writeError(w, http.StatusNotFound, "unknown ring action")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	w.WriteHeader(http.StatusOK)
}

// Notice is a private message to one member's phones (e.g. a ring reminder that didn't work).
func (s *Store) Notice(memberID, title, note string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return
	}
	s.addEventLocked(Event{Type: "notice", MemberID: m.ID, Name: title, To: m.ID, Note: cleanNote(note)})
	_ = s.saveLocked()
}
