package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

func typesFor(s *Store, viewer string, since int64) []string {
	evs, _ := s.EventsFor(viewer, since)
	var out []string
	for _, e := range evs {
		out = append(out, e.Type+":"+e.Name+":"+e.Kind+":"+e.Note)
	}
	return out
}

func TestSOSSeenAndRequestsGoToOnePerson(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom", "Dad")
	sam, mom, dad := ms[0].ID, ms[1].ID, ms[2].ID
	_, cs := s.EventsFor(sam, -1)
	_, cm := s.EventsFor(mom, -1)
	_, cd := s.EventsFor(dad, -1)

	if err := s.SOSSeen(mom, sam); err != ErrNoSOS {
		t.Fatalf("seen without an SOS: %v", err)
	}
	s.StartSOS(sam, nil)
	s.SOSSeen(mom, sam)
	s.SOSSeen(mom, sam) // once only
	got := typesFor(s, sam, cs)
	if len(got) != 1 || got[0] != "sos_seen:Mom::" {
		t.Fatalf("Sam got %v", got)
	}
	views, _ := s.VisibleTo(dad)
	for _, v := range views {
		if v.ID == sam && (len(v.SOSSeenNames) != 1 || v.SOSSeenNames[0] != "Mom") {
			t.Fatalf("seen by: %v", v.SOSSeenNames)
		}
	}
	// Dad sees the SOS but not Mom's receipt.
	for _, e := range typesFor(s, dad, cd) {
		if strings.HasPrefix(e, "sos_seen") {
			t.Fatalf("Dad got someone else's receipt: %v", e)
		}
	}

	if err := s.Request(sam, dad, "pickup", nil); err != nil {
		t.Fatal(err)
	}
	if err := s.Request(sam, dad, "dance", nil); err != ErrBadRequest {
		t.Fatalf("bad kind accepted: %v", err)
	}
	var dadGot, momGot int
	for _, e := range typesFor(s, dad, cd) {
		if e == "request:Sam:pickup:" {
			dadGot++
		}
	}
	for _, e := range typesFor(s, mom, cm) {
		if strings.HasPrefix(e, "request") {
			momGot++
		}
	}
	if dadGot != 1 || momGot != 0 {
		t.Fatalf("request delivery: dad %d, mom %d", dadGot, momGot)
	}
	s.EndSOS(sam)
	if s.d.Members[sam].SOSSeenBy != nil {
		t.Fatal("seen list not cleared")
	}
}

func TestPlaceRemindersFireOnce(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	family := s.Circles()[0].ID
	at(t, s, sam, 2000, 20)
	home, _ := s.CreatePlace(mom, Place{CircleID: family, Name: "Home", Lat: homeLat, Lon: homeLon, Radius: 100})
	if _, err := s.AddReminder(sam, home.ID, "Take the bins out", false); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddReminder(sam, home.ID, "Lock the door", true); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddReminder(sam, "nope", "x", false); err == nil {
		t.Fatal("reminder for an unknown fence accepted")
	}
	_, cs := s.EventsFor(sam, -1)
	_, cm := s.EventsFor(mom, -1)
	at(t, s, sam, 10, 10) // arrive
	at(t, s, sam, 20, 10)
	got := typesFor(s, sam, cs)
	if len(got) != 1 || got[0] != "reminder:Sam:arrive:Take the bins out" {
		t.Fatalf("Sam got %v", got)
	}
	for _, e := range typesFor(s, mom, cm) {
		if strings.HasPrefix(e, "reminder") {
			t.Fatal("Mom saw Sam's private reminder")
		}
	}
	if r := s.RemindersFor(sam); len(r) != 1 || !r[0].OnLeave {
		t.Fatalf("left: %+v", r)
	}
	at(t, s, sam, 4000, 10) // clearly gone
	if got := typesFor(s, sam, cs); len(got) != 2 || got[1] != "reminder:Sam:leave:Lock the door" {
		t.Fatalf("leave reminder: %v", got)
	}
	// Deleting the fence deletes its reminders.
	s.AddReminder(sam, home.ID, "again", false)
	s.DeletePlace(mom, home.ID)
	if len(s.RemindersFor(sam)) != 0 {
		t.Fatal("reminder outlived its fence")
	}
}

func TestReminderFromSpeech(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam")
	sam := ms[0].ID
	family := s.Circles()[0].ID
	s.CreatePlace(sam, Place{CircleID: family, Name: "Home", Lat: 1, Lon: 1, Radius: 100})
	s.CreatePlace(sam, Place{CircleID: family, Name: "Grandma's House", Lat: 2, Lon: 2, Radius: 100})
	r, err := s.ReminderFromSpeech(sam, "When I leave grandma's house remind me to grab the cake")
	if err != nil || !r.OnLeave {
		t.Fatalf("%+v %v", r, err)
	}
	if _, err := s.ReminderFromSpeech(sam, "remind me about the dentist"); err != errIndexNoPlace {
		t.Fatalf("no fence named: %v", err)
	}
}

// ringRequest builds a webhook request like the Pebble app does (optionally signed).
func ringRequest(t *testing.T, url, transcription, delivery, secret string, test bool) *http.Request {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	mw.WriteField("transcription", transcription)
	mw.WriteField("recordedAt", strconv.FormatInt(time.Now().UnixMilli(), 10))
	mw.WriteField("client", "ring")
	mw.Close()
	req, _ := http.NewRequest("POST", url, bytes.NewReader(body.Bytes()))
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Index-Webhook-Version", "1")
	req.Header.Set("X-Index-Trigger", "double-click-hold")
	req.Header.Set("X-Index-Delivery", delivery)
	isTest := "0"
	if test {
		req.Header.Set("X-Index-Test", "true")
		isTest = "1"
	}
	if secret != "" {
		ts := strconv.FormatInt(time.Now().Unix(), 10)
		mac := hmac.New(sha256.New, []byte(secret))
		mac.Write([]byte("v1\n" + ts + "\n" + delivery + "\ndouble-click-hold\n" + isTest + "\n"))
		mac.Write(body.Bytes())
		req.Header.Set("X-Index-Timestamp", ts)
		req.Header.Set("X-Index-Signature", hex.EncodeToString(mac.Sum(nil)))
	}
	return req
}

func TestIndexRingWebhooks(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Sam"})
	_, joined := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
	tok := joined["token"].(string)
	_, urls := e.do("GET", "/api/me/index", tok, nil)
	sosURL, secret := urls["sos"].(string), urls["signingSecret"].(string)
	if !strings.Contains(sosURL, "/api/index/") || len(secret) < 32 {
		t.Fatalf("urls: %v", urls)
	}
	send := func(req *http.Request) int {
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	sosOf := func() float64 {
		_, data := e.do("GET", "/api/admin/members", "", nil)
		v, _ := data["members"].([]any)[0].(map[string]any)["sosSince"].(float64)
		return v
	}
	// A test event does nothing.
	if c := send(ringRequest(t, sosURL, "test", "t1", secret, true)); c != 200 || sosOf() != 0 {
		t.Fatalf("test event: %d", c)
	}
	// A wrong signature is refused.
	if c := send(ringRequest(t, sosURL, "help", "d1", "wrong-secret", false)); c != 401 || sosOf() != 0 {
		t.Fatalf("bad signature: %d", c)
	}
	// A good one raises the SOS, with what was said.
	if c := send(ringRequest(t, sosURL, "I need help", "d2", secret, false)); c != 200 || sosOf() == 0 {
		t.Fatalf("signed SOS: %d", c)
	}
	// Unknown links are 404.
	if c := send(ringRequest(t, e.srv.URL+"/api/index/nottherighttokenatall/sos", "x", "d3", "", false)); c != 404 {
		t.Fatalf("unknown token: %d", c)
	}
	// Resetting makes the old links stop working.
	e.do("POST", "/api/me/index/reset", tok, nil)
	if c := send(ringRequest(t, sosURL, "x", "d4", "", false)); c != 404 {
		t.Fatalf("old link after reset: %d", c)
	}
}

func TestNudgesAndPausedLocation(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Sam", "Mom")
	sam, mom := ms[0].ID, ms[1].ID
	_, cm := s.EventsFor(mom, -1)
	s.UpdateLocation(sam, Location{Lat: 51.5, Lon: -0.1, Accuracy: 10, Battery: 80, Time: time.Now().UnixMilli()})
	for _, k := range []string{"where", "heart", "thumbs", "here"} {
		if err := s.Request(sam, mom, k, nil); err != nil {
			t.Fatalf("%s: %v", k, err)
		}
	}
	evs, _ := s.EventsFor(mom, cm)
	if len(evs) != 4 || evs[3].Kind != "here" || evs[3].Lat != 51.5 {
		t.Fatalf("nudges: %+v", evs)
	}
	// Paused: their old location isn't revealed by a nudge.
	paused := true
	s.UpdateMember(sam, MemberPatch{Paused: &paused})
	_, cm = s.EventsFor(mom, -1)
	s.Request(sam, mom, "heart", nil)
	evs, _ = s.EventsFor(mom, cm)
	if len(evs) != 1 || evs[0].Lat != 0 {
		t.Fatalf("paused nudge leaked a location: %+v", evs)
	}
}
