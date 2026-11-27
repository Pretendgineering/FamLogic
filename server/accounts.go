package main

import (
	"errors"
	"strconv"
	"sync"
	"time"
)

// Accounts: each member can have a username and password, so they can sign in
// again on a new phone or after reinstalling. A person can be signed in on several
// devices (a phone and a tablet, say), up to maxDevices; signing in on one more
// signs out the one used longest ago. A reset link signs out all of them.

var ErrUsernameTaken = errors.New("that username is already taken")

const keepOldTokens = 5

func (s *Store) usernameTakenLocked(username, exceptID string) bool {
	for _, m := range s.d.Members {
		if m.ID != exceptID && m.Username == username {
			return true
		}
	}
	return false
}

// MemberByUsername returns a copy of the member with that (normalized) username.
func (s *Store) MemberByUsername(username string) (*Member, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, m := range s.d.Members {
		if m.Username != "" && m.Username == username {
			cp := *m
			return &cp, true
		}
	}
	return nil, false
}

const maxDevices = 6

// Device is one signed-in phone, tablet or display.
type Device struct {
	ID       string `json:"id"`
	Hash     string `json:"hash,omitempty"`
	Kind     string `json:"kind,omitempty"` // phone, tablet, display or tv (what the app reports)
	App      string `json:"app,omitempty"`
	Created  int64  `json:"created"`
	LastSeen int64  `json:"lastSeen,omitempty"`
}

func (m *Member) retireHashLocked(h string) {
	if h == "" {
		return
	}
	m.OldTokenHashes = append(m.OldTokenHashes, h)
	if len(m.OldTokenHashes) > keepOldTokens+maxDevices {
		m.OldTokenHashes = m.OldTokenHashes[len(m.OldTokenHashes)-keepOldTokens-maxDevices:]
	}
}

// migrateDevicesLocked turns a token from before several devices into a device.
func (m *Member) migrateDevicesLocked() {
	if m.TokenHash != "" {
		m.Devices = append([]Device{{ID: "d" + randomString(6), Hash: m.TokenHash, Kind: m.Device, App: m.App, Created: m.LastSignIn, LastSeen: m.LastContact}}, m.Devices...)
		m.TokenHash = ""
	}
}

// signOutAllLocked signs out every device of the member.
func (m *Member) signOutAllLocked() {
	m.migrateDevicesLocked()
	for _, d := range m.Devices {
		m.retireHashLocked(d.Hash)
	}
	m.Devices = nil
}

// rotateTokenLocked signs in one more device for the member (the one unused
// longest goes if there are too many). Caller holds the lock and saves.
func (s *Store) rotateTokenLocked(m *Member) string {
	m.migrateDevicesLocked()
	for len(m.Devices) >= maxDevices {
		oldest := 0
		for i, d := range m.Devices {
			if max(d.LastSeen, d.Created) < max(m.Devices[oldest].LastSeen, m.Devices[oldest].Created) {
				oldest = i
			}
		}
		m.retireHashLocked(m.Devices[oldest].Hash)
		m.Devices = append(m.Devices[:oldest], m.Devices[oldest+1:]...)
	}
	token := randomString(32)
	now := time.Now().UnixMilli()
	m.Devices = append(m.Devices, Device{ID: "d" + randomString(6), Hash: hashToken(token), Created: now, LastSeen: now})
	m.LastSignIn = now
	return token
}

// SignOutDevice signs out one of a member's devices.
func (s *Store) SignOutDevice(memberID, deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return ErrNotFound
	}
	m.migrateDevicesLocked()
	for i, d := range m.Devices {
		if d.ID == deviceID {
			m.retireHashLocked(d.Hash)
			m.Devices = append(m.Devices[:i], m.Devices[i+1:]...)
			if m.Display && len(m.Devices) == 0 {
				s.removeMemberLocked(m.ID) // a display is only its pairing: signed out, it's gone
			}
			return s.saveLocked()
		}
	}
	return ErrNotFound
}

// SetDeviceStatus records what one device reports about itself.
func (s *Store) SetDeviceStatus(memberID, deviceID, kind, app string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return
	}
	for i := range m.Devices {
		if m.Devices[i].ID == deviceID {
			if kind == "phone" || kind == "tablet" || kind == "display" || kind == "tv" {
				m.Devices[i].Kind = kind
			}
			if len(app) <= 20 {
				m.Devices[i].App = app
			}
		}
	}
}

// SignIn issues a new device token for a member whose password was just checked.
// Any previously signed-in phone stops working.
func (s *Store) SignIn(id string) (*Member, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, "", ErrNotFound
	}
	token := s.rotateTokenLocked(m)
	cp := *m
	return &cp, token, s.saveLocked()
}

// SetCredentials sets a member's password, and their username if given (only
// allowed while they don't have one yet). The signed-in phone stays signed in.
func (s *Store) SetCredentials(id, username, passwordHash string) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, ErrNotFound
	}
	if m.Username == "" {
		if username == "" {
			return nil, errors.New("choose a username")
		}
		if s.usernameTakenLocked(username, id) {
			return nil, ErrUsernameTaken
		}
		m.Username = username
	}
	m.PasswordHash = passwordHash
	cp := *m
	return &cp, s.saveLocked()
}

// CreateResetInvite makes a one-time link that lets a member choose a new
// password and sign in on a phone, keeping who they are. Older reset links for
// the same member are cancelled.
func (s *Store) CreateResetInvite(memberID string, ttl time.Duration) (*Invite, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[memberID]
	if !ok {
		return nil, ErrNotFound
	}
	for code, inv := range s.d.Invites {
		if inv.MemberID == memberID {
			delete(s.d.Invites, code)
		}
	}
	now := time.Now()
	inv := &Invite{Code: inviteCode(), Name: m.Name, MemberID: memberID, CreatedAt: now.UnixMilli(), ExpiresAt: now.Add(ttl).UnixMilli()}
	s.d.Invites[inv.Code] = inv
	return inv, s.saveLocked()
}

// InviteInfo looks up a code without using it up. For reset invites it also
// returns the member it belongs to.
func (s *Store) InviteInfo(code string) (*Invite, *Member, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	inv, ok := s.d.Invites[normalizeCode(code)]
	if !ok || inv.ExpiresAt < time.Now().UnixMilli() {
		return nil, nil, false
	}
	ic := *inv
	if inv.MemberID == "" {
		ic.CircleID = s.inviteCircleLocked(inv)
		return &ic, nil, true
	}
	m, ok := s.d.Members[inv.MemberID]
	if !ok {
		return nil, nil, false
	}
	mc := *m
	return &ic, &mc, true
}

// RedeemReset uses up a reset invite: sets the password (and username, if the
// member had none) and signs in the phone that redeemed it.
func (s *Store) RedeemReset(code, username, passwordHash string) (*Member, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	code = normalizeCode(code)
	inv, ok := s.d.Invites[code]
	if !ok || inv.ExpiresAt < time.Now().UnixMilli() || inv.MemberID == "" {
		return nil, "", ErrNotFound
	}
	m, ok := s.d.Members[inv.MemberID]
	if !ok {
		return nil, "", ErrNotFound
	}
	if m.Username == "" {
		if username == "" {
			return nil, "", errors.New("choose a username")
		}
		if s.usernameTakenLocked(username, m.ID) {
			return nil, "", ErrUsernameTaken
		}
		m.Username = username
	}
	m.PasswordHash = passwordHash
	m.signOutAllLocked() // a reset link: a lost phone stops working
	token := s.rotateTokenLocked(m)
	delete(s.d.Invites, code)
	cp := *m
	return &cp, token, s.saveLocked()
}

// ---- slowing down password guessing ----

const (
	maxFailures   = 5
	failureWindow = 15 * time.Minute
	lockoutTime   = 15 * time.Minute
)

type failState struct {
	count       int
	first       time.Time
	lockedUntil time.Time
}

// loginLimiter locks a username for a while after repeated wrong passwords.
// Unknown usernames are counted too, so they behave exactly like real ones.
type loginLimiter struct {
	mu    sync.Mutex
	fails map[string]*failState
}

func newLoginLimiter() *loginLimiter { return &loginLimiter{fails: map[string]*failState{}} }

// Locked returns how long the username is still locked for (0 if not).
func (l *loginLimiter) Locked(username string) time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	if f, ok := l.fails[username]; ok {
		if d := time.Until(f.lockedUntil); d > 0 {
			return d
		}
	}
	return 0
}

func (l *loginLimiter) Fail(username string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if len(l.fails) > 10_000 { // don't let junk usernames grow this forever
		for k, f := range l.fails {
			if now.Sub(f.first) > failureWindow && now.After(f.lockedUntil) {
				delete(l.fails, k)
			}
		}
	}
	f, ok := l.fails[username]
	if !ok || now.Sub(f.first) > failureWindow {
		f = &failState{first: now}
		l.fails[username] = f
	}
	f.count++
	if f.count >= maxFailures {
		f.lockedUntil = now.Add(lockoutTime)
		f.count = 0
		f.first = now
	}
}

func (l *loginLimiter) Succeed(username string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, username)
}

// minutesText turns a duration into "about 12 minutes".
func minutesText(d time.Duration) string {
	m := int(d.Minutes() + 0.999)
	if m <= 1 {
		return "about a minute"
	}
	return "about " + strconv.Itoa(m) + " minutes"
}

// deferToPhone: this device isn't a phone and one of the person's phones has
// been in touch in the last half hour, so its location wins.
func (s *Store) deferToPhone(memberID, deviceID string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	m, ok := s.d.Members[memberID]
	if !ok || deviceID == "" {
		return false
	}
	var this *Device
	for i := range m.Devices {
		if m.Devices[i].ID == deviceID {
			this = &m.Devices[i]
		}
	}
	if this == nil || this.Kind == "phone" || this.Kind == "" {
		return false
	}
	cut := time.Now().Add(-30 * time.Minute).UnixMilli()
	for _, d := range m.Devices {
		if d.ID != deviceID && d.Kind == "phone" && d.LastSeen > cut {
			return true
		}
	}
	return false
}
