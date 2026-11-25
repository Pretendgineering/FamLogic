package main

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestModulesDefaults(t *testing.T) {
	// A brand-new server: setup not done yet, available default modules on.
	fresh, err := OpenStore(filepath.Join(t.TempDir(), "famlogic.json"))
	if err != nil {
		t.Fatal(err)
	}
	if m := fresh.Modules(); m.SetupDone || !m.Enabled["thereabouts"] || !m.Enabled["chat"] || !m.Enabled["chores"] || m.Enabled["meals"] {
		t.Fatalf("fresh: %+v", m)
	}
	// An existing family (a data file from before modules, with people in it):
	// location on, no setup screen.
	old := filepath.Join(t.TempDir(), "famlogic.json")
	os.WriteFile(old, []byte(`{"secret":"x","members":{"a1":{"id":"a1","name":"Sam","color":"#fff","tokenHash":"h","createdAt":1}}}`), 0o600)
	s, err := OpenStore(old)
	if err != nil {
		t.Fatal(err)
	}
	if m := s.Modules(); !m.SetupDone || !m.Enabled["thereabouts"] || m.Enabled["chat"] {
		t.Fatalf("existing: %+v", m)
	}
	// People joining a brand-new server before setup doesn't change its defaults.
	fresh2, _ := newStoreWithMembers(t, "Alex")
	if m := fresh2.Modules(); m.SetupDone || !m.Enabled["chat"] {
		t.Fatalf("new family after a join: %+v", m)
	}
	if feats := s.moduleFeatures(); len(feats) != 1 || feats[0] != "module:thereabouts" {
		t.Fatalf("features: %v", feats)
	}
	// Choosing modules: unknown ids dropped; chosen-but-not-built ones don't show as features yet.
	if err := s.SetModules(map[string]bool{"thereabouts": false, "chat": true, "bogus": true}, true); err != nil {
		t.Fatal(err)
	}
	m := s.Modules()
	if m.Enabled["thereabouts"] || !m.Enabled["chat"] || m.Enabled["bogus"] || !m.SetupDone {
		t.Fatalf("after set: %+v", m)
	}
	if err := s.SetModules(map[string]bool{"display": true}, true); err != nil {
		t.Fatal(err)
	}
	if feats := s.moduleFeatures(); len(feats) != 0 {
		t.Fatalf("features should be empty until display is built: %v", feats)
	}
}

func TestRoles(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Mom", "Kid")
	kid := ms[1].ID
	if ms[1].access() != AccessParent {
		t.Fatal("existing members should be parents")
	}
	if err := s.SetAccess(kid, AccessYoungerChild, false); err != nil {
		t.Fatal(err)
	}
	if err := s.SetAccess(kid, "boss", false); err != ErrBadRequest {
		t.Fatalf("bad role: %v", err)
	}
	// Only the main admin makes admins.
	if err := s.SetAccess(ms[0].ID, AccessAdmin, false); err != ErrNeedsOwner {
		t.Fatalf("admin without owner: %v", err)
	}
	if err := s.SetAccess(ms[0].ID, AccessAdmin, true); err != nil {
		t.Fatal(err)
	}
	// Users can't become non-users.
	if err := s.SetAccess(kid, AccessDependent, true); err != ErrBadRequest {
		t.Fatalf("user to dependent: %v", err)
	}
	m, _ := s.MemberByID(kid)
	if m.access() != AccessYoungerChild || m.canUsePassword() {
		t.Fatalf("kid: %+v", m)
	}
	s.SetAccess(kid, AccessOlderChild, false)
	if m, _ := s.MemberByID(kid); !m.canUsePassword() {
		t.Fatal("older children may use a password")
	}
	baby, err := s.AddDependent("Baby")
	if err != nil || baby.role() != RoleDependent || baby.canUsePassword() || baby.TokenHash != "" {
		t.Fatalf("dependent: %+v %v", baby, err)
	}
	views, _ := s.VisibleTo(ms[0].ID)
	for _, v := range views {
		if v.ID == baby.ID {
			t.Fatal("non-users shouldn't be in the app's people list")
		}
	}
}

func TestDeviceModuleSwitches(t *testing.T) {
	store, ms := newStoreWithMembers(t, "Mom", "Dad")
	s := NewServer(Config{AdminPassword: "correct horse"}, store, nil)
	put := func(m *Member) int {
		rec := httptest.NewRecorder()
		s.handleDeviceSetModules(rec, httptest.NewRequest("PUT", "/api/modules", strings.NewReader(`{"enabled":{"thereabouts":true,"chat":false}}`)), m)
		return rec.Code
	}
	if code := put(ms[1]); code != 403 {
		t.Fatalf("a parent who isn't an admin: %d", code)
	}
	ms[0].Admin = true
	if code := put(ms[0]); code != 200 {
		t.Fatalf("admin: %d", code)
	}
	if m := store.Modules(); m.Enabled["chat"] || !m.Enabled["thereabouts"] || !m.SetupDone {
		t.Fatalf("after: %+v", m)
	}
}
