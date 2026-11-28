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
	// Direction of travel in degrees (1-360, 360 = north), 0 if unknown or standing still.
	Heading  int   `json:"heading,omitempty"`
	Battery  int   `json:"battery"` // 0-100, -1 if unknown
	Charging bool  `json:"charging"`
	Time     int64 `json:"time"`     // when the fix was taken (unix ms, from the phone)
	Received int64 `json:"received"` // when the server got it (unix ms)
	// The phone is updating less often to save its low battery.
	Saving bool `json:"saving,omitempty"`
	// The phone thinks its person is driving (sustained speed; see the app).
	Driving bool `json:"driving,omitempty"`
	// Their Pebble watch's battery (1-100), if one is connected.
	Watch int `json:"watch,omitempty"`
}

// Member is one person (one phone) in the family.
type Member struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
	Emoji string `json:"emoji,omitempty"` // shown instead of the initial, if set
	// The voice their messages are read aloud in: "female", "male" or "" (the default).
	Voice string `json:"voice,omitempty"`
	// Stars earned for chores (Upkeep), until a parent spends or resets them.
	Stars     int       `json:"stars,omitempty"`
	TokenHash string    `json:"tokenHash"`
	CreatedAt int64     `json:"createdAt"`
	Paused    bool      `json:"paused"`
	Loc       *Location `json:"loc,omitempty"`

	// Sign-in (see accounts.go). Members who joined before accounts existed have
	// neither until they set a password in the app.
	// Circles this person is in, and the ones they've stopped sharing their location with.
	Circles    []string `json:"circles,omitempty"`
	HiddenFrom []string `json:"hiddenFrom,omitempty"`

	// Places they're in (see places.go), and whether their arrivals and
	// departures are announced (nil = yes).
	Presence      map[string]*Presence `json:"presence,omitempty"`
	ShareArrivals *bool                `json:"shareArrivals,omitempty"`

	// How many hours of trail this person keeps for others to see (0 = none, the default).
	TrailHours int `json:"trailHours,omitempty"`
	// How many days of trips they keep (0 = none), and when the last trip they
	// were alerted about ended (see trips.go).
	TripDays    int   `json:"tripDays,omitempty"`
	LastTripEnd int64 `json:"lastTripEnd,omitempty"`

	// SOS: set while this person has an SOS active, until they say they're OK.
	SOSSince int64 `json:"sosSince,omitempty"`

	Username     string `json:"username,omitempty"`
	PasswordHash string `json:"passwordHash,omitempty"`
	LastSignIn   int64  `json:"lastSignIn,omitempty"`
	// Tokens of phones that were signed out by a newer sign-in, so they can be
	// told why instead of just "unknown device". Newest last, a few kept.
	OldTokenHashes []string `json:"oldTokenHashes,omitempty"`
	// Devices signed in as this person (TokenHash is from before there could be several).
	Devices []Device `json:"devices,omitempty"`

	// Feature switches this person controls from their phone. Pointers so that a
	// missing value (older data files) means "on" by default.
	ShareLowBattery *bool `json:"shareLowBattery,omitempty"`

	// Lowest low-battery level already announced in the current discharge
	// (0 = none yet). Reset once the battery is back above batteryRearmLevel.
	BatteryAlertedAt int `json:"batteryAlertedAt,omitempty"`

	// Phone health (see health.go): app version and problems the phone reports,
	// when it last reported them, when it was last in touch at all, and when a
	// "hasn't been in touch" alert went out (0 = none for this silence).
	App         string   `json:"app,omitempty"`
	Device      string   `json:"device,omitempty"` // phone, tablet or display (what the app reports)
	Issues      []string `json:"issues,omitempty"`
	StatusAt    int64    `json:"statusAt,omitempty"`
	LastContact int64    `json:"lastContact,omitempty"`
	SilentSince int64    `json:"silentSince,omitempty"`

	// Who has seen their current SOS (member ids), see requests.go.
	SOSSeenBy []string `json:"sosSeenBy,omitempty"`
	// Safety: a phone number for Call buttons, and who they\'re driving to ("On my way").
	Phone     string `json:"phone,omitempty"`
	RideTo    string `json:"rideTo,omitempty"`
	RideSince int64  `json:"rideSince,omitempty"`
	// Index 01 ring webhooks (see index01.go): the secret in their URLs, and the
	// optional signing secret.
	IndexToken  string `json:"indexToken,omitempty"`
	IndexSecret string `json:"indexSecret,omitempty"`

	// When they last stopped driving (unix ms), so a short stop doesn't
	// announce "started driving" again.
	DriveEndedAt int64 `json:"driveEndedAt,omitempty"`

	// "parent" (the default), "child" or "dependent" (a non-user, e.g. a baby);
	// Admin opens the console's admin pages; PasswordAllowed lets a child set a
	// password (see roles.go).
	Role            string `json:"role,omitempty"`
	Admin           bool   `json:"admin,omitempty"`
	PasswordAllowed bool   `json:"passwordAllowed,omitempty"`
	// A family display (Corkboard): a wall tablet or TV signed in as itself.
	Display bool `json:"display,omitempty"`

	// A child's switches, set by parents (see kids.go), and the tiles on this
	// person's home screen (nil = the app's default).
	Kid  *KidSettings `json:"kid,omitempty"`
	Home []string     `json:"home,omitempty"`
	// WebHome is their web console home: tiles as "id:WxH", in order (empty: the default).
	WebHome []string `json:"webHome,omitempty"`
	// Larger buttons and text, and picture icons (nil = the default for their role).
	Big      *bool `json:"big,omitempty"`
	Pictures *bool `json:"pictures,omitempty"`
}

// SharesLowBattery reports whether others get told when this person's battery is low.
func (m *Member) SharesLowBattery() bool { return m.ShareLowBattery == nil || *m.ShareLowBattery }

// SharesArrivals reports whether this person's arrivals and departures at places are announced.
func (m *Member) SharesArrivals() bool { return m.ShareArrivals == nil || *m.ShareArrivals }

// Invite is a single-use code that lets a phone join. A "reset" invite
// (MemberID set) instead lets an existing member choose a new password.
type Invite struct {
	Code      string `json:"code"`
	Name      string `json:"name,omitempty"`
	MemberID  string `json:"memberId,omitempty"`
	CircleID  string `json:"circleId,omitempty"` // the circle a (normal) invite joins
	Access    string `json:"access,omitempty"`   // the role a new member gets ("" = parent; see roles.go)
	CreatedAt int64  `json:"createdAt"`
	ExpiresAt int64  `json:"expiresAt"`
}

type data struct {
	Secret string `json:"secret"` // signs admin session cookies
	// Steward: API keys for AI agents, and their recent uses (see apikeys.go).
	APIKeys    []*APIKey      `json:"apiKeys,omitempty"`
	Rewards    []Reward       `json:"rewards,omitempty"`
	RewardAsks []RewardAsk    `json:"rewardAsks,omitempty"`
	Steward    *StewardConfig `json:"steward,omitempty"`
	// Outside calendars shown in Datebook (see calendar_feeds.go).
	Feeds []CalFeed `json:"calendarFeeds,omitempty"`
	// The family displays\' quiet time and messages (see display.go).
	Display *DisplayState `json:"display,omitempty"`
	Fridge  *FridgeNote   `json:"fridge,omitempty"` // the family's sticky note
	// DrivewayUnits: "mi" (miles and gallons) or "km" (km and litres); "" = each device's region.
	DrivewayUnits string `json:"drivewayUnits,omitempty"`
	// MealSlots: which meal slots the Pantry week shows (nil: Breakfast, Lunch, Dinner).
	MealSlots []string `json:"mealSlots,omitempty"`
	// RoutineStarts: routines started by hand today (Rhythms), on top of the timed ones.
	RoutineStarts []RoutineStart `json:"routineStarts,omitempty"`
	// Barters: children trading chores for points (Upkeep).
	Barters []*Barter `json:"barters,omitempty"`
	// OwnerMember is the main admin's own place in the family (set at first setup). They
	// sign in with their own account; the admin password stays for recovery.
	OwnerMember string `json:"ownerMember,omitempty"`
	// OwnerWebHome is the web home of whoever logs in with the admin password.
	OwnerWebHome []string           `json:"ownerWebHome,omitempty"`
	KeyLog       []KeyUse           `json:"keyLog,omitempty"`
	Members      map[string]*Member `json:"members"`
	Invites      map[string]*Invite `json:"invites"`
	Circles      map[string]*Circle `json:"circles"`
	Places       map[string]*Place  `json:"places,omitempty"`
	// Place reminders, by id (see requests.go).
	Reminders map[string]*Reminder `json:"reminders,omitempty"`
	// Trails, per member, oldest point first (see trails.go).
	Trails map[string][]TrailPoint `json:"trails,omitempty"`

	// Which FamLogic modules are on (see modules.go); nil = from before modules.
	Modules *ModuleSettings `json:"modules,omitempty"`

	// The parent PIN (hashed like a password) that unlocks settings on children's
	// devices and family displays; "" = not set.
	ParentPIN string `json:"parentPin,omitempty"`

	// Recent alerts for phones to pick up (see events.go).
	LastEventID int64       `json:"lastEventId"`
	Events      []Event     `json:"events,omitempty"`
	CheckPlans  []CheckPlan `json:"checkPlans,omitempty"`
	FamilyName  string      `json:"familyName,omitempty"` // set in the console (else FAMILY_NAME)
}

// Store keeps everything in memory and writes it to one JSON file.
// A family has a handful of members, so this is plenty and means
// there is no database to set up, back up or migrate.
type Store struct {
	mu      sync.RWMutex
	path    string
	d       data
	changed chan struct{} // closed and replaced whenever an event is added
	// When the silence watch started (unix ms; see health.go).
	watchStart int64
	// Extra daily backups (see backup.go).
	backupHooks []func(dir string, keep int) error
}

var palette = []string{"#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4", "#42d4f4", "#f032e6", "#9a6324", "#469990", "#808000"}

var ErrNotFound = errors.New("not found")

func OpenStore(path string) (*Store, error) {
	s := &Store{path: path, changed: make(chan struct{})}
	b, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		s.d = data{Secret: randomString(32), Members: map[string]*Member{}, Invites: map[string]*Invite{}, Circles: map[string]*Circle{}, Places: map[string]*Place{}, Trails: map[string][]TrailPoint{}, Reminders: map[string]*Reminder{}}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, err
		}
		m := s.modulesLocked() // a new family: default modules, first-run setup to come
		s.d.Modules = &m
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
	if s.d.Circles == nil {
		s.d.Circles = map[string]*Circle{}
	}
	if s.d.Places == nil {
		s.d.Places = map[string]*Place{}
	}
	if s.d.Trails == nil {
		s.d.Trails = map[string][]TrailPoint{}
	}
	if s.d.Reminders == nil {
		s.d.Reminders = map[string]*Reminder{}
	}
	for _, m := range s.d.Members {
		m.migrateDevicesLocked() // one token each, from before several devices
	}
	s.tidyDisplaysLocked()
	if s.d.Secret == "" {
		s.d.Secret = randomString(32)
	}
	if s.d.Modules == nil {
		// Decided once, on opening: a file from before modules with people in it is an
		// existing family (location sharing on); an empty one is a new family.
		m := s.modulesLocked()
		s.d.Modules = &m
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

// CreateInvite makes a one-time code for someone new to join circleID
// ("" = the default circle).
func (s *Store) CreateInvite(name, circleID string, ttl time.Duration, access ...string) (*Invite, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if circleID == "" {
		circleID = s.defaultCircleLocked()
	} else if _, ok := s.d.Circles[circleID]; !ok {
		return nil, ErrNotFound
	}
	now := time.Now()
	inv := &Invite{Code: inviteCode(), Name: name, CircleID: circleID, CreatedAt: now.UnixMilli(), ExpiresAt: now.Add(ttl).UnixMilli()}
	if len(access) > 0 && access[0] != AccessParent {
		inv.Access = access[0]
	}
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
		cp := *inv
		if cp.MemberID == "" {
			cp.CircleID = s.inviteCircleLocked(inv) // invites from before circles join the default one
		}
		out = append(out, cp)
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
		Devices:      []Device{{ID: "d" + randomString(6), Hash: hashToken(token), Created: now, LastSeen: now}},
		CreatedAt:    now,
		Username:     username,
		PasswordHash: passwordHash,
	}
	if username != "" {
		m.LastSignIn = now
	}
	if c := s.inviteCircleLocked(inv); c != "" {
		m.Circles = []string{c}
	}
	applyInviteAccess(m, inv)
	// A display's name is what tells it apart, as for people: pairing "Living Room" again
	// (a reset TV, a reinstall) takes over the old listing, with its settings, and signs
	// out the old pairing, rather than listing the TV twice.
	if m.Display {
		if old := s.displayNamedLocked(m.Name); old != nil {
			old.signOutAllLocked()
			old.Devices = m.Devices
			delete(s.d.Invites, code)
			cp := *old
			return &cp, token, s.saveLocked()
		}
	}
	if !m.canUsePassword() { // younger children: no account, just this device
		m.Username, m.PasswordHash, m.LastSignIn = "", "", 0
	}
	s.d.Members[m.ID] = m
	delete(s.d.Invites, code)
	cp := *m
	return &cp, token, s.saveLocked()
}

func (s *Store) MemberByToken(token string) (*Member, bool) {
	m, _, _ := s.lookupDevice(token)
	return m, m != nil
}

// lookupToken finds the member a device token belongs to (see lookupDevice).
func (s *Store) lookupToken(token string) (m *Member, signedOut bool) {
	m, _, signedOut = s.lookupDevice(token)
	return m, signedOut
}

// lookupDevice finds the member and device a token belongs to. If it's an old
// token that was signed out, it returns signedOut = true instead.
func (s *Store) lookupDevice(token string) (m *Member, deviceID string, signedOut bool) {
	if token == "" {
		return nil, "", false
	}
	h := hashToken(token)
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, mm := range s.d.Members {
		if mm.TokenHash == h {
			cp := *mm
			return &cp, "", false
		}
		for _, d := range mm.Devices {
			if d.Hash == h {
				cp := *mm
				return &cp, d.ID, false
			}
		}
		for _, old := range mm.OldTokenHashes {
			if old == h {
				signedOut = true
			}
		}
	}
	return nil, "", signedOut
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
	if m.Paused || m.Display || (m.Loc != nil && loc.Time < m.Loc.Time) {
		return nil
	}
	wasDriving := m.Loc != nil && m.Loc.Driving
	m.Loc = &loc
	s.checkDrivingLocked(m, wasDriving, loc)
	s.checkBatteryLocked(m, loc)
	s.checkPlacesLocked(m, loc)
	s.checkRideLocked(m, loc)
	s.addTrailPointLocked(m, loc)
	return s.saveLocked()
}

// MemberPatch lists the fields to change; nil fields are left alone.
type MemberPatch struct {
	Name            *string
	Paused          *bool
	Color           *string
	Emoji           *string
	Voice           *string
	Phone           *string
	ShareLowBattery *bool
	ShareArrivals   *bool
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
		if m.Display { // another display already called this is an old listing of the same TV
			if old := s.displayNamedLocked(*p.Name); old != nil && old.ID != id {
				old.signOutAllLocked()
				s.removeMemberLocked(old.ID)
			}
		}
		m.Name = *p.Name
	}
	if p.Color != nil {
		m.Color = *p.Color
	}
	if p.Voice != nil {
		m.Voice = *p.Voice
	}
	if p.Phone != nil {
		m.Phone = strings.TrimSpace(*p.Phone)
	}
	if p.Emoji != nil {
		m.Emoji = *p.Emoji
	}
	if p.Paused != nil {
		m.Paused = *p.Paused
		if *p.Paused {
			m.Loc = nil
			// Forget which places they're in, so resuming later doesn't announce
			// what happened while they were paused.
			m.Presence = nil
		}
	}
	if p.ShareLowBattery != nil {
		v := *p.ShareLowBattery
		m.ShareLowBattery = &v
	}
	if p.ShareArrivals != nil {
		v := *p.ShareArrivals
		m.ShareArrivals = &v
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
	s.removeMemberLocked(id)
	return s.saveLocked()
}

func (s *Store) removeMemberLocked(id string) {
	delete(s.d.Members, id)
	delete(s.d.Trails, id)
	for code, inv := range s.d.Invites {
		if inv.MemberID == id {
			delete(s.d.Invites, code) // their reset links die with them
		}
	}
}

// displayNamedLocked finds the family display with this name (any case), if there is one.
func (s *Store) displayNamedLocked(name string) *Member {
	for _, m := range s.d.Members {
		if m.Display && strings.EqualFold(strings.TrimSpace(m.Name), strings.TrimSpace(name)) {
			return m
		}
	}
	return nil
}

// tidyDisplaysLocked removes displays nothing is signed in to (a display is only its
// pairing), and, where two share a name, keeps the one used last.
func (s *Store) tidyDisplaysLocked() {
	seen := map[string]*Member{}
	for id, m := range s.d.Members {
		if !m.Display {
			continue
		}
		if len(m.Devices) == 0 {
			s.removeMemberLocked(id)
			continue
		}
		key := strings.ToLower(strings.TrimSpace(m.Name))
		if other, ok := seen[key]; ok {
			if displayLastUsed(other) >= displayLastUsed(m) {
				s.removeMemberLocked(m.ID)
				continue
			}
			s.removeMemberLocked(other.ID)
		}
		seen[key] = m
	}
}

func displayLastUsed(m *Member) int64 {
	var t int64
	for _, d := range m.Devices {
		t = max(t, d.LastSeen, d.Created)
	}
	return t
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

func (s *Store) FamilyName() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.d.FamilyName
}

func (s *Store) SetFamilyName(n string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.d.FamilyName = n
	return s.saveLocked()
}

// plainID is 12 lowercase hex characters: safe in a URL path and a note's front matter.
func plainID() string {
	b := make([]byte, 6)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
