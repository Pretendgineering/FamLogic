package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMeetings(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "meetings": true, "todos": true}, "setupDone": true})
	in := map[string]any{"title": "Sunday family meeting", "date": "2026-11-29", "slides": []map[string]any{
		{"title": "Weekend plans", "text": "Park or pool?", "jobs": []map[string]string{{"text": "Pack the picnic", "who": "Kid"}, {"text": "Bring the football"}}},
		{"title": "Chores"},
	}}
	if st, _ := e.status("POST", "/api/meetings", f.kid, in); st != 403 {
		t.Fatalf("children don't make meetings: %d", st)
	}
	st, b := e.status("POST", "/api/meetings", f.mom, in)
	if st != 200 {
		t.Fatalf("create: %d %v", st, b)
	}
	m := b["meeting"].(map[string]any)
	id := m["id"].(string)
	slides := m["slides"].([]any)
	jobs := slides[0].(map[string]any)["jobs"].([]any)
	if len(slides) != 2 || len(jobs) != 2 || jobs[0].(map[string]any)["who"] == nil || jobs[1].(map[string]any)["who"] != nil {
		t.Fatalf("meeting: %v", m)
	}
	// During the meeting: a volunteer takes the football, a note, and a new job.
	if st, _ := e.status("POST", "/api/meetings/"+id+"/slides/0/jobs/1", f.dad, map[string]string{"who": "Dad"}); st != 200 {
		t.Fatalf("volunteer: %d", st)
	}
	e.status("POST", "/api/meetings/"+id+"/slides/0/notes", f.mom, map[string]string{"text": "Park if it's dry"})
	e.status("POST", "/api/meetings/"+id+"/slides/1/jobs", f.mom, map[string]string{"text": "Hoover the stairs"})
	// The note in the vault reads naturally.
	_, b = e.status("GET", "/api/meetings/"+id, f.kid, nil)
	file := b["meeting"].(map[string]any)["file"].(string)
	raw, _ := os.ReadFile(filepath.Join(filepath.Dir(e.dataFile()), "vault", file))
	for _, want := range []string{"## Weekend plans", "- [ ] Pack the picnic @Kid", "- [ ] Bring the football @Dad", "> Park if it's dry", "- [ ] Hoover the stairs"} {
		if !strings.Contains(string(raw), want) {
			t.Fatalf("note lacks %q:\n%s", want, raw)
		}
	}
	// Send: the two jobs with someone go onto the Docket, once.
	_, b = e.status("POST", "/api/meetings/"+id+"/send", f.mom, nil)
	if b["sent"].(float64) != 2 {
		t.Fatalf("sent: %v", b["sent"])
	}
	_, b = e.status("POST", "/api/meetings/"+id+"/send", f.mom, nil)
	if b["sent"].(float64) != 0 {
		t.Fatalf("sent twice: %v", b["sent"])
	}
}
