package main

import (
	"errors"
	"slices"
	"time"
	_ "time/tzdata" // time zones for fence schedules, even in a minimal container
)

// Fence schedules: a fence can announce arrivals and departures only at certain
// times, e.g. School on weekdays 07:00-17:00. Times are in the time zone of
// whoever set the schedule, so a server running on UTC still gets it right.

type Schedule struct {
	Days []int  `json:"days"` // 0 = Sunday ... 6 = Saturday
	From string `json:"from"` // "07:00"
	To   string `json:"to"`   // "17:00"; earlier than From means overnight; equal means all day
	TZ   string `json:"tz"`   // IANA name, e.g. "Europe/Berlin"
}

var ErrBadSchedule = errors.New("a schedule needs at least one day, times like 07:30, and a valid time zone")

func parseHM(v string) (int, bool) {
	t, err := time.Parse("15:04", v)
	if err != nil {
		return 0, false
	}
	return t.Hour()*60 + t.Minute(), true
}

func (sc *Schedule) valid() bool {
	if len(sc.Days) == 0 || len(sc.Days) > 7 {
		return false
	}
	for _, d := range sc.Days {
		if d < 0 || d > 6 {
			return false
		}
	}
	_, ok1 := parseHM(sc.From)
	_, ok2 := parseHM(sc.To)
	if !ok1 || !ok2 {
		return false
	}
	_, err := time.LoadLocation(sc.TZ)
	return err == nil && sc.TZ != ""
}

// Active reports whether alerts are on at time t.
func (sc *Schedule) Active(t time.Time) bool {
	loc, err := time.LoadLocation(sc.TZ)
	if err != nil {
		return true // can't tell: better to alert
	}
	t = t.In(loc)
	from, _ := parseHM(sc.From)
	to, _ := parseHM(sc.To)
	now := t.Hour()*60 + t.Minute()
	today := int(t.Weekday())
	yesterday := (today + 6) % 7
	switch {
	case from == to: // all day on the chosen days
		return slices.Contains(sc.Days, today)
	case from < to:
		return slices.Contains(sc.Days, today) && now >= from && now < to
	default: // overnight, e.g. 22:00-06:00: the night belongs to the day it starts
		return slices.Contains(sc.Days, today) && now >= from || slices.Contains(sc.Days, yesterday) && now < to
	}
}
