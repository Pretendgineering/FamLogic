package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDriveway(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chores": true, "garage": true}, "setupDone": true})
	st, v := e.status("POST", "/api/vehicles", f.mom, map[string]any{"name": "The Sienna", "make": "Toyota", "year": "2019"})
	if st != 200 {
		t.Fatalf("add: %d %v", st, v)
	}
	id := v["id"].(string)
	if st, _ := e.status("POST", "/api/vehicles", f.kid, map[string]any{"name": "Bike"}); st != 403 {
		t.Fatalf("younger child adds: %d", st)
	}
	for _, fill := range []map[string]any{
		{"date": "2026-11-01", "odometer": 10000, "amount": 15, "cost": 50},
		{"date": "2026-11-10", "odometer": 10300, "amount": 10, "cost": 35},
		{"date": "2026-11-20", "odometer": 10600, "amount": 12, "cost": 40},
	} {
		if st, out := e.status("POST", "/api/vehicles/"+id+"/fuel", f.mom, fill); st != 200 {
			t.Fatalf("fuel: %d %v", st, out)
		}
	}
	_, all := e.status("GET", "/api/vehicles?units=mi", f.teen, nil)
	got := all["vehicles"].([]any)[0].(map[string]any)
	// 600 miles on 22 gallons = 27.3 mpg.
	if got["avgEconomy"] != 27.3 || got["odometer"] != 10600.0 {
		t.Fatalf("worked out: %v", got)
	}
	if st, out := e.status("POST", "/api/vehicles/"+id+"/service", f.mom, map[string]any{"date": "2026-11-05", "what": "Oil change", "cost": 60, "every": "6 months"}); st != 200 || len(out["reminders"].([]any)) != 1 {
		t.Fatalf("service: %d %v", st, out)
	}
	b, _ := os.ReadFile(filepath.Join(filepath.Dir(e.path), "vault", "Vehicles", "The Sienna.md"))
	if !strings.Contains(string(b), "| 2026-11-10 | 10300 | 10 | 35.00 | yes |") || !strings.Contains(string(b), "Oil change") {
		t.Fatalf("note: %s", b)
	}
	if st, _ := e.status("DELETE", "/api/vehicles/"+id, f.mom, nil); st != 204 {
		t.Fatalf("delete: %d", st)
	}
}
