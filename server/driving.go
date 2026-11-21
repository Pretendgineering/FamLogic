package main

import "time"

// Driving: the phone decides when its person is driving (a sustained speed
// above about 25 km/h, from GPS) and says so with each fix. Others see
// "Driving" and can choose to be told when someone sets off.

const (
	EventDriveStart = "drive_start"
	// A new drive within this long of the last one is the same drive
	// (a stop for fuel or at a long light): no second alert.
	driveRestartGap = 10 * time.Minute
)

// checkDrivingLocked raises "started driving" when a phone starts reporting it.
// Caller holds the lock and saves.
func (s *Store) checkDrivingLocked(m *Member, wasDriving bool, loc Location) {
	switch {
	case loc.Driving && !wasDriving:
		if time.Since(time.UnixMilli(m.DriveEndedAt)) > driveRestartGap {
			s.addEventLocked(Event{Type: EventDriveStart, MemberID: m.ID, Name: m.Name, Lat: loc.Lat, Lon: loc.Lon})
		}
	case !loc.Driving && wasDriving:
		m.DriveEndedAt = time.Now().UnixMilli()
	}
}
