package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Location is the most recent position a member's phone reported.
type Location struct {
	Lat      float64 `json:"lat"`
	Lon      float64 `json:"lon"`
	Accuracy float64 `json:"acc"`             // metres
	Speed    float64 `json:"speed,omitempty"` // metres per second
	Battery  int     `json:"battery"`         // 0-100, -1 if unknown
	Charging bool    `json:"charging"`
	Time     int64   `json:"time"`     // when the fix was taken (unix ms, from the phone)
	Received int64   `json:"received"` // when the server got it (unix ms)
}

// Member is one person (one phone) in the family.
type Member struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Color     string    `json:"color"`
	TokenHash string    `json:"tokenHash"`
	CreatedAt int64     `json:"createdAt"`
	Paused    bool      `json:"paused"`
	Loc       *Location `json:"loc,omitempty"`

	// Sign-in (see accounts.go). Members who joined before accounts existed have
	// neither until they set a password in the app.
	// SOS: set while this person has an SOS active, until they say they're OK.
	SOSSince int64 `json:"sosSince,omitempty"`

	Username     string `json:"username,omitempty"`
	PasswordHash string `json:"passwordHash,omitempty"`
	LastSignIn   int64  `json:"lastSignIn,omitempty"`
	// Tokens of phones that were signed out by a newer sign-in, so they can be
	// told why instead of just "unknown device". Newest last, a few kept.
	OldTokenHashes []string `json:"oldTokenHashes,omitempty"`

	// Feature switches this person controls from their phone. Pointers so that a
	// missing value (older data files) means "on" by default.
	ShareLowBattery *bool `json:"shareLowBattery,omitempty"`

	// Lowest low-battery level already announced in the current discharge
	// (0 = none yet). Reset once the battery is back above batteryRearmLevel.
	BatteryAlertedAt int `json:"batteryAlertedAt,omitempty"`
}

// SharesLowBattery reports whether others get told when this person's battery is low.
func (m *Member) SharesLowBattery() bool { return m.ShareLowBattery == nil || *m.ShareLowBattery }

// Invite is a single-use code that lets a phone join. A "reset" invite
// (MemberID set) instead lets an existing member choose a new password.
type Invite struct {
	Code      string `json:"code"`
	Name      string `json:"name,omitempty"`
	MemberID  string `json:"memberId,omitempty"`
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt int64  `json:"expiresAt"`
}

type data struct {
	Secret  string             `json:"secret"` // signs admin session cookies
	Members map[string]*Member `json:"members"`
	Invites map[string]*Invite `json:"invites"`

	// Recent alerts for phones to pick up (see events.go).
	LastEventID int64   `json:"lastEventId"`
	Events      []Event `json:"events,omitempty"`
}

// Store keeps everything in memory and writes it to one JSON file.
// A family has a handful of members, so this is plenty and means
// there is no database to set up, back up or migrate.
type Store struct {
	mu      sync.RWMutex
	path    string
	d       data
	changed chan struct{} // closed and replaced whenever an event is added
}

var palette = []string{"#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4", "#42d4f4", "#f032e6", "#9a6324", "#469990", "#808000"}

var ErrNotFound = errors.New("not found")

func OpenStore(path string) (*Store, error) {
	s := &Store{path: path, changed: make(chan struct{})}
	b, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		s.d = data{Secret: randomString(32), Members: map[string]*Member{}, Invites: map[string]*Invite{}}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, err
		}
		return s, s.saveLocked()
	case err != nil:
		return nil, err
	}
	if err := json.Unmarshal(b, &s.d); err != nil {
		return nil, err
	}
	if s.d.Members == nil {
		s.d.Members = map[string]*Member{}
	}
	if s.d.Invites == nil {
		s.d.Invites = map[string]*Invite{}
	}
	if s.d.Secret == "" {
		s.d.Secret = randomString(32)
	}
	return s, nil
}

// saveLocked writes atomically (temp file + rename) so a crash never
// leaves a half-written file. Caller must hold the lock.
func (s *Store) saveLocked() error {
	b, err := json.MarshalIndent(s.d, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) Secret() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.d.Secret
}

func (s *Store) CreateInvite(name string, ttl time.Duration) (*Invite, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	inv := &Invite{Code: inviteCode(), Name: name, CreatedAt: now.UnixMilli(), ExpiresAt: now.Add(ttl).UnixMilli()}
	s.d.Invites[inv.Code] = inv
	return inv, s.saveLocked()
}

func (s *Store) Invites() []Invite {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UnixMilli()
	out := []Invite{}
	for code, inv := range s.d.Invites {
		if inv.ExpiresAt < now {
			delete(s.d.Invites, code) // tidy up as we go; persisted on next save
			continue
		}
		out = append(out, *inv)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt < out[j].CreatedAt })
	return out
}

func (s *Store) DeleteInvite(code string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.d.Invites[normalizeCode(code)]; !ok {
		return ErrNotFound
	}
	delete(s.d.Invites, normalizeCode(code))
	return s.saveLocked()
}

// Join consumes a (non-reset) invite and creates a member. username and
// passwordHash may be empty for apps from before accounts existed. It returns the
// member and the device token (only ever shown this once; we store a hash).
func (s *Store) Join(code, name, username, passwordHash string) (*Member, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	code = normalizeCode(code)
	inv, ok := s.d.Invites[code]
	if !ok || inv.ExpiresAt < time.Now().UnixMilli() || inv.MemberID != "" {
		return nil, "", ErrNotFound
	}
	if username != "" && s.usernameTakenLocked(username, "") {
		return nil, "", ErrUsernameTaken
	}
	if name == "" {
		name = inv.Name
	}
	token := randomString(32)
	now := time.Now().UnixMilli()
	m := &Member{
		ID:           randomString(9),
		Name:         name,
		Color:        palette[len(s.d.Members)%len(palette)],
		TokenHash:    hashToken(token),
		CreatedAt:    now,
		Username:     username,
		PasswordHash: passwordHash,
	}
	if username != "" {
		m.LastSignIn = now
	}
	s.d.Members[m.ID] = m
	delete(s.d.Invites, code)
	cp := *m
	return &cp, token, s.saveLocked()
}

func (s *Store) MemberByToken(token string) (*Member, bool) {
	m, _ := s.lookupToken(token)
	return m, m != nil
}

// lookupToken finds the member a device token belongs to. If it's an old token
// that a newer sign-in replaced, it returns signedOutBy = true instead.
func (s *Store) lookupToken(token string) (m *Member, signedOut bool) {
	if token == "" {
		return nil, false
	}
	h := hashToken(token)
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, mm := range s.d.Members {
		if mm.TokenHash == h {
			cp := *mm
			return &cp, false
		}
		for _, old := range mm.OldTokenHashes {
			if old == h {
				signedOut = true
			}
		}
	}
	return nil, signedOut
}

// Members returns copies sorted by name.
func (s *Store) Members() []Member {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Member, 0, len(s.d.Members))
	for _, m := range s.d.Members {
		cp := *m
		if m.Loc != nil {
			l := *m.Loc
			cp.Loc = &l
		}
		out = append(out, cp)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

// UpdateLocation records a fix, ignoring ones older than what we already have
// (phones can deliver queued fixes out of order after being offline).
func (s *Store) UpdateLocation(id string, loc Location) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	if m.Paused || (m.Loc != nil && loc.Time < m.Loc.Time) {
		return nil
	}
	m.Loc = &loc
	s.checkBatteryLocked(m, loc)
	return s.saveLocked()
}

// MemberPatch lists the fields to change; nil fields are left alone.
type MemberPatch struct {
	Name            *string
	Paused          *bool
	Color           *string
	ShareLowBattery *bool
}

// UpdateMember applies a patch. Pausing also forgets the last location so
// nobody can see where you were when you paused.
func (s *Store) UpdateMember(id string, p MemberPatch) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, ErrNotFound
	}
	if p.Name != nil {
		m.Name = *p.Name
	}
	if p.Color != nil {
		m.Color = *p.Color
	}
	if p.Paused != nil {
		m.Paused = *p.Paused
		if *p.Paused {
			m.Loc = nil
		}
	}
	if p.ShareLowBattery != nil {
		v := *p.ShareLowBattery
		m.ShareLowBattery = &v
	}
	cp := *m
	return &cp, s.saveLocked()
}

func (s *Store) DeleteMember(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.d.Members[id]; !ok {
		return ErrNotFound
	}
	delete(s.d.Members, id)
	for code, inv := range s.d.Invites {
		if inv.MemberID == id {
			delete(s.d.Invites, code) // their reset links die with them
		}
	}
	return s.saveLocked()
}

func randomString(nBytes int) string {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// inviteCode is short and easy to type: 8 chars of base32 without the
// look-alikes I/O/0/1, shown as XXXX-XXXX.
func inviteCode() string {
	b := make([]byte, 5)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	enc := base32.NewEncoding("ABCDEFGHJKLMNPQRSTUVWXYZ23456789").WithPadding(base32.NoPadding)
	c := enc.EncodeToString(b)
	return c[:4] + "-" + c[4:8]
}

func normalizeCode(c string) string {
	c = strings.ToUpper(strings.TrimSpace(c))
	c = strings.NewReplacer(" ", "", "-", "").Replace(c)
	if len(c) == 8 {
		return c[:4] + "-" + c[4:]
	}
	return c
}

func hashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}
