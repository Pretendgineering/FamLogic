package main

import (
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

// names returns who the viewer sees, with "*" after people whose location is visible.
func names(s *Store, viewer string) string {
	views, _ := s.VisibleTo(viewer)
	var out []string
	for _, v := range views {
		n := v.Name
		if v.Loc != nil {
			n += "*"
		}
		out = append(out, n)
	}
	sort.Strings(out)
	return strings.Join(out, " ")
}

func TestCircleVisibility(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Mom", "Kid", "Grandpa")
	mom, kid, grandpa := ms[0].ID, ms[1].ID, ms[2].ID
	family := s.Circles()[0].ID
	grandparents, _ := s.CreateCircle("Grandparents")

	// Grandpa is only in "Grandparents"; Mom is in both; Kid only in the family circle.
	s.SetMembership(grandpa, family, false)
	s.SetMembership(grandpa, grandparents.ID, true)
	s.SetMembership(mom, grandparents.ID, true)
	for _, id := range []string{mom, kid, grandpa} {
		report(t, s, id, 80, false)
	}

	if got := names(s, kid); got != "Kid* Mom*" {
		t.Fatalf("Kid sees %q", got)
	}
	if got := names(s, grandpa); got != "Grandpa* Mom*" {
		t.Fatalf("Grandpa sees %q", got)
	}
	if got := names(s, mom); got != "Grandpa* Kid* Mom*" {
		t.Fatalf("Mom sees %q", got)
	}

	// Mom stops sharing with Grandparents: Grandpa still sees her, but as paused.
	s.SetSharingIn(mom, grandparents.ID, false)
	if got := names(s, grandpa); got != "Grandpa* Mom" {
		t.Fatalf("Grandpa sees %q after Mom hid from Grandparents", got)
	}
	if got := names(s, kid); got != "Kid* Mom*" {
		t.Fatalf("Kid sees %q (Mom still shares with the family)", got)
	}
	views, myCircles := s.VisibleTo(mom)
	if len(myCircles) != 2 || myCircles[0].ID != family {
		t.Fatalf("Mom's circles: %+v", myCircles)
	}
	for _, v := range views {
		if v.Name == "Grandpa" && (len(v.SharedCircles) != 1 || v.SharedCircles[0] != grandparents.ID) {
			t.Fatalf("shared circles with Grandpa: %v", v.SharedCircles)
		}
	}

	// Alerts follow circles: Grandpa hears Mom's SOS but not her low battery
	// (she isn't sharing her location with him); Kid hears neither of Grandpa's.
	_, gcur := s.EventsFor(grandpa, -1)
	_, kcur := s.EventsFor(kid, -1)
	report(t, s, mom, 50, false)
	report(t, s, mom, 14, false)
	s.StartSOS(mom, nil)
	s.StartSOS(grandpa, nil)
	evTypes := func(viewer string, cur int64) string {
		evs, _ := s.EventsFor(viewer, cur)
		var out []string
		for _, e := range evs {
			out = append(out, e.Name+":"+e.Type)
		}
		return strings.Join(out, " ")
	}
	if got := evTypes(grandpa, gcur); got != "Mom:sos" {
		t.Fatalf("Grandpa got %q", got)
	}
	if got := evTypes(kid, kcur); got != "Mom:battery_low Mom:sos" {
		t.Fatalf("Kid got %q", got)
	}
}

func TestDeleteCircleAndInvites(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Mom")
	family := s.Circles()[0].ID
	friends, _ := s.CreateCircle("Friends")

	// An invite to Friends puts the newcomer in Friends only.
	inv, _ := s.CreateInvite("Pat", friends.ID, time.Hour)
	pat, _, _ := s.Join(inv.Code, "", "", "")
	if len(pat.Circles) != 1 || pat.Circles[0] != friends.ID {
		t.Fatalf("Pat's circles: %v", pat.Circles)
	}
	// Mom joins Friends too, with a code, while staying who she is.
	inv2, _ := s.CreateInvite("", friends.ID, time.Hour)
	if _, err := s.JoinCircleWithCode(ms[0].ID, inv2.Code); err != nil {
		t.Fatal(err)
	}
	if _, err := s.JoinCircleWithCode(ms[0].ID, inv2.Code); err == nil {
		t.Fatal("invite used twice")
	}
	if got := names(s, pat.ID); got != "Mom Pat" {
		t.Fatalf("Pat sees %q", got)
	}

	// Deleting Friends: its invites die, nobody's in it, the last circle can't go.
	inv3, _ := s.CreateInvite("", friends.ID, time.Hour)
	if err := s.DeleteCircle(friends.ID); err != nil {
		t.Fatal(err)
	}
	if _, _, ok := s.InviteInfo(inv3.Code); ok {
		t.Fatal("invite to a deleted circle still works")
	}
	if got := names(s, pat.ID); got != "Pat" {
		t.Fatalf("Pat still sees %q", got)
	}
	if err := s.DeleteCircle(family); err != ErrLastCircle {
		t.Fatalf("deleting the last circle: %v", err)
	}
}

func TestUpgradeCreatesDefaultCircle(t *testing.T) {
	// A data file from before circles: members without any circle.
	path := filepath.Join(t.TempDir(), "famlogic.json")
	s, _ := OpenStore(path)
	inv, _ := s.CreateInvite("Alex", "", time.Hour)
	alex, _, _ := s.Join(inv.Code, "", "", "")
	inv2, _ := s.CreateInvite("Bob", "", time.Hour)
	bob, _, _ := s.Join(inv2.Code, "", "", "")
	if names(s, alex.ID) != "Alex" {
		t.Fatal("setup: members shouldn't share a circle yet")
	}
	s2, _ := OpenStore(path)
	s2.EnsureDefaultCircle("The Smiths")
	s2.EnsureDefaultCircle("ignored the second time")
	if cs := s2.Circles(); len(cs) != 1 || cs[0].Name != "The Smiths" {
		t.Fatalf("circles after upgrade: %+v", cs)
	}
	if got := names(s2, bob.ID); got != "Alex Bob" {
		t.Fatalf("after upgrade Bob sees %q", got)
	}
}

func TestCirclesOverHTTP(t *testing.T) {
	e := newEnv(t)
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	_, c := e.do("POST", "/api/admin/circles", "", map[string]string{"name": "  Friends  "})
	if c["name"] != "Friends" {
		t.Fatalf("create circle: %v", c)
	}
	friends := c["id"].(string)
	_, inv := e.do("POST", "/api/admin/invites", "", map[string]string{"name": "Pat", "circleId": friends})
	if st, info := e.status("GET", "/api/invites/"+inv["code"].(string), "", nil); st != 200 || info["circle"] != "Friends" {
		t.Fatalf("invite info: %d %v", st, info)
	}
	_, j := e.do("POST", "/api/join", "", map[string]string{"code": inv["code"].(string)})
	pat := j["token"].(string)
	patID := j["member"].(map[string]any)["id"].(string)

	_, list := e.do("GET", "/api/members", pat, nil)
	circles := list["circles"].([]any)
	if len(circles) != 1 || circles[0].(map[string]any)["name"] != "Friends" || circles[0].(map[string]any)["sharing"] != true {
		t.Fatalf("Pat's circles: %v", circles)
	}
	// Turn sharing off for Friends, then check it's reported back.
	if st, _ := e.status("POST", "/api/me/circles/"+friends, pat, map[string]bool{"sharing": false}); st != 204 {
		t.Fatalf("set sharing: %d", st)
	}
	_, list = e.do("GET", "/api/members", pat, nil)
	if list["circles"].([]any)[0].(map[string]any)["sharing"] != false {
		t.Fatal("per-circle sharing not saved")
	}
	// Admin moves Pat into the family circle as well; rename and delete work.
	_, adm := e.do("GET", "/api/admin/members", "", nil)
	family := adm["circles"].([]any)[0].(map[string]any)["id"].(string)
	if st, _ := e.status("PUT", "/api/admin/circles/"+family+"/members/"+patID, "", nil); st != 204 {
		t.Fatalf("add to circle: %d", st)
	}
	if st, _ := e.status("PATCH", "/api/admin/circles/"+friends, "", map[string]string{"name": "Pals"}); st != 204 {
		t.Fatalf("rename: %d", st)
	}
	if st, _ := e.status("DELETE", "/api/admin/circles/"+friends, "", nil); st != 204 {
		t.Fatalf("delete: %d", st)
	}
	if st, _ := e.status("DELETE", "/api/admin/circles/"+family, "", nil); st != 409 {
		t.Fatalf("deleting the last circle: %d", st)
	}
	_, list = e.do("GET", "/api/members", pat, nil)
	if cs := list["circles"].([]any); len(cs) != 1 || cs[0].(map[string]any)["id"] != family {
		t.Fatalf("Pat's circles after changes: %v", cs)
	}
	// Joining another circle with a code while signed in.
	_, c2 := e.do("POST", "/api/admin/circles", "", map[string]string{"name": "Book club"})
	_, inv2 := e.do("POST", "/api/admin/invites", "", map[string]string{"circleId": c2["id"].(string)})
	if st, out := e.status("POST", "/api/me/circles/join", pat, map[string]string{"code": inv2["code"].(string)}); st != 200 || out["name"] != "Book club" {
		t.Fatalf("join with code: %d %v", st, out)
	}
	if st, _ := e.status("POST", "/api/me/circles/join", pat, map[string]string{"code": "AAAA-BBBB"}); st != 404 {
		t.Fatalf("bad code: %d", st)
	}
}

func TestOldInvitesShowTheirCircle(t *testing.T) {
	path := filepath.Join(t.TempDir(), "famlogic.json")
	s, _ := OpenStore(path)
	inv, _ := s.CreateInvite("Old", "", time.Hour) // made before circles existed
	s2, _ := OpenStore(path)
	s2.EnsureDefaultCircle("Family")
	family := s2.Circles()[0].ID
	if got := s2.Invites()[0].CircleID; got != family {
		t.Fatalf("listed invite circle = %q, want the default circle", got)
	}
	if info, _, _ := s2.InviteInfo(inv.Code); info.CircleID != family {
		t.Fatalf("invite info circle = %q", info.CircleID)
	}
}
