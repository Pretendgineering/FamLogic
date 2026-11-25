package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCalendarFeeds(t *testing.T) {
	f := newChatFamily(t)
	e := f.e
	day := time.Now().AddDate(0, 0, 2).Format("20060102")
	ics := fmt.Sprintf("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//School//EN\r\n"+
		"BEGIN:VEVENT\r\nUID:term@school\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:%s\r\nSUMMARY:Inset day\r\nEND:VEVENT\r\n"+
		"BEGIN:VEVENT\r\nUID:club@school\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:%sT160000\r\nDTEND:%sT170000\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:Chess club\r\nEND:VEVENT\r\n"+
		"END:VCALENDAR\r\n", day, day, day)
	src := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/bad" {
			fmt.Fprint(w, "<html>not a calendar</html>")
			return
		}
		w.Header().Set("Content-Type", "text/calendar")
		fmt.Fprint(w, ics)
	}))
	defer src.Close()
	e.do("POST", "/api/admin/login", "", map[string]string{"password": "correct horse"})
	e.status("PUT", "/api/admin/modules", "", map[string]any{"enabled": map[string]bool{"thereabouts": true, "calendar": true}, "setupDone": true})

	if st, _ := e.status("POST", "/api/calendar/feeds", f.teen, map[string]any{"name": "School", "url": src.URL}); st != 403 {
		t.Fatalf("children don't add calendars: %d", st)
	}
	if st, _ := e.status("POST", "/api/calendar/feeds", f.mom, map[string]any{"name": "School", "url": src.URL + "/bad"}); st != 400 {
		t.Fatalf("not a calendar: %d", st)
	}
	st, feed := e.status("POST", "/api/calendar/feeds", f.mom, map[string]any{"name": "School", "url": src.URL, "color": "#ff8800"})
	if st != 200 || feed["events"].(float64) != 2 {
		t.Fatalf("add: %d %v", st, feed)
	}
	// Everyone sees its events, read-only, marked with the calendar's name.
	from := time.Now().Truncate(24 * time.Hour).UnixMilli()
	_, ev := e.status("GET", fmt.Sprintf("/api/events?from=%d&to=%d", from, from+40*86400000), f.kid, nil)
	n := 0
	for _, x := range ev["events"].([]any) {
		if x.(map[string]any)["calendar"] == "School" {
			n++
		}
	}
	if n != 5 { // the inset day and four chess clubs
		t.Fatalf("feed events: %d in %v", n, ev["events"])
	}
	if st, _ := e.status("DELETE", "/api/calendar/feeds/"+feed["id"].(string), f.mom, nil); st != 204 {
		t.Fatalf("remove: %d", st)
	}
	_, ev = e.status("GET", fmt.Sprintf("/api/events?from=%d&to=%d", from, from+40*86400000), f.kid, nil)
	if len(ev["events"].([]any)) != 0 {
		t.Fatalf("gone: %v", ev["events"])
	}
}
