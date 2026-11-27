package main

import (
	"path/filepath"
	"testing"
	"time"
)

func TestDisplayRepairSameName(t *testing.T) {
	st, err := OpenStore(filepath.Join(t.TempDir(), "d.json"))
	if err != nil {
		t.Fatal(err)
	}
	pair := func(name string) *Member {
		inv, err := st.CreateInvite("", "", time.Hour, AccessDisplay)
		if err != nil {
			t.Fatal(err)
		}
		m, _, err := st.Join(inv.Code, name, "", "")
		if err != nil {
			t.Fatal(err)
		}
		return m
	}
	a := pair("Living Room")
	b := pair("living room")
	if a.ID != b.ID {
		t.Fatalf("re-pairing made a second display")
	}
	if n := len(st.d.Members[a.ID].Devices); n != 1 {
		t.Fatalf("old pairing kept: %d devices", n)
	}
	c := pair("Kitchen")
	if err := st.SignOutDevice(c.ID, st.d.Members[c.ID].Devices[0].ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := st.d.Members[c.ID]; ok {
		t.Fatalf("signed-out display still listed")
	}
}
