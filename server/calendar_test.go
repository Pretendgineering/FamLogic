package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/emersion/go-ical"
	"github.com/emersion/go-webdav"
	"github.com/emersion/go-webdav/caldav"
)

func TestCalendarAndCalDAV(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	vault := filepath.Join(filepath.Dir(e.dataFile()), "vault")
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "chat": true, "calendar": true}, "setupDone": true})

	// The app adds a weekly event.
	st, ev := e.status("POST", "/api/events", f.mom, map[string]any{"title": "Swimming", "start": "2026-12-01T17:00", "end": "2026-12-01T18:00", "every": "week", "who": []string{f.kidID}})
	if st != 200 {
		t.Fatalf("create: %d %v", st, ev)
	}
	b, _ := os.ReadFile(filepath.Join(vault, "Calendar", "2026-12-01 Swimming.md"))
	if !strings.Contains(string(b), "rrule: FREQ=WEEKLY") || !strings.Contains(string(b), "# Swimming") {
		t.Fatalf("note:\n%s", b)
	}
	from := time.Date(2026, 12, 1, 0, 0, 0, 0, time.Local).UnixMilli()
	to := time.Date(2026, 12, 31, 0, 0, 0, 0, time.Local).UnixMilli()
	_, occ := e.status("GET", fmt.Sprintf("/api/events?from=%d&to=%d", from, to), f.kid, nil)
	if n := len(occ["events"].([]any)); n != 5 {
		t.Fatalf("5 Tuesdays in December 2026, got %d", n)
	}
	if st, _ := e.status("POST", "/api/events", f.kid, map[string]any{"title": "Party", "start": "2026-12-02"}); st != 403 {
		t.Fatalf("a younger child can only look: %d", st)
	}

	// A phone over CalDAV: mom needs a password.
	e.status("POST", "/api/me/password", f.mom, map[string]string{"username": "mom", "newPassword": "blue canoe at midnight"})
	hc := webdav.HTTPClientWithBasicAuth(http.DefaultClient, "mom", "blue canoe at midnight")
	cl, err := caldav.NewClient(hc, e.srv.URL+"/dav/")
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	principal, err := cl.FindCurrentUserPrincipal(ctx)
	if err != nil {
		t.Fatalf("principal: %v", err)
	}
	home, err := cl.FindCalendarHomeSet(ctx, principal)
	if err != nil {
		t.Fatalf("home: %v", err)
	}
	cals, err := cl.FindCalendars(ctx, home)
	if err != nil || len(cals) != 1 {
		t.Fatalf("calendars: %v %v", cals, err)
	}
	objs, err := cl.QueryCalendar(ctx, cals[0].Path, &caldav.CalendarQuery{CompRequest: caldav.CalendarCompRequest{Name: "VCALENDAR", AllProps: true, AllComps: true}, CompFilter: caldav.CompFilter{Name: "VCALENDAR"}})
	if err != nil || len(objs) != 1 {
		t.Fatalf("query: %v %v", len(objs), err)
	}
	// The phone adds an event.
	cal := ical.NewCalendar()
	cal.Props.SetText(ical.PropVersion, "2.0")
	cal.Props.SetText(ical.PropProductID, "-//test//EN")
	ve := ical.NewEvent()
	ve.Props.SetText(ical.PropUID, "phone-123")
	ve.Props.SetDateTime(ical.PropDateTimeStamp, time.Now().UTC())
	ve.Props.SetText(ical.PropSummary, "Dentist")
	ve.Props.SetDateTime(ical.PropDateTimeStart, time.Date(2026, 12, 5, 15, 30, 0, 0, time.Local).UTC())
	ve.Props.SetDateTime(ical.PropDateTimeEnd, time.Date(2026, 12, 5, 16, 15, 0, 0, time.Local).UTC())
	cal.Children = append(cal.Children, ve.Component)
	if _, err := cl.PutCalendarObject(ctx, cals[0].Path+"phone-123.ics", cal, nil); err != nil {
		t.Fatalf("put: %v", err)
	}
	b, err = os.ReadFile(filepath.Join(vault, "Calendar", "2026-12-05 Dentist.md"))
	if err != nil || !strings.Contains(string(b), "start: 2026-12-05T15:30") || !strings.Contains(string(b), "uid: phone-123") {
		t.Fatalf("note from the phone: %v\n%s", err, b)
	}
	// And deletes it.
	if err := cl.RemoveAll(ctx, cals[0].Path+"phone-123.ics", nil); err != nil {
		t.Fatalf("delete: %v", err)
	}
	// Wrong password: no.
	bad, _ := caldav.NewClient(webdav.HTTPClientWithBasicAuth(http.DefaultClient, "mom", "nope nope"), e.srv.URL+"/dav/")
	if _, err := bad.FindCurrentUserPrincipal(ctx); err == nil {
		t.Fatal("a wrong password must not get in")
	}
}
