package main

import (
	"path/filepath"
	"testing"
)

func TestModulesDefaults(t *testing.T) {
	// A brand-new server: setup not done yet, available default modules on.
	fresh, err := OpenStore(filepath.Join(t.TempDir(), "famlogic.json"))
	if err != nil {
		t.Fatal(err)
	}
	if m := fresh.Modules(); m.SetupDone || !m.Enabled["thereabouts"] || m.Enabled["chat"] {
		t.Fatalf("fresh: %+v", m)
	}
	// An existing family (from before modules): location on, no setup screen.
	s, _ := newStoreWithMembers(t, "Sam")
	if m := s.Modules(); !m.SetupDone || !m.Enabled["thereabouts"] {
		t.Fatalf("existing: %+v", m)
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
	if feats := s.moduleFeatures(); len(feats) != 0 {
		t.Fatalf("features should be empty until chat is built: %v", feats)
	}
}

func TestRoles(t *testing.T) {
	s, ms := newStoreWithMembers(t, "Mom", "Kid")
	if ms[1].role() != RoleParent {
		t.Fatal("existing members should be parents")
	}
	if err := s.SetRole(ms[1].ID, RoleChild); err != nil {
		t.Fatal(err)
	}
	if err := s.SetRole(ms[1].ID, "boss"); err != ErrBadRequest {
		t.Fatalf("bad role: %v", err)
	}
	for _, m := range s.Members() {
		if m.ID == ms[1].ID && m.role() != RoleChild {
			t.Fatal("role not saved")
		}
	}
}
