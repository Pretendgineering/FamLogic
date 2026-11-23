package main

import (
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Parents' controls for children:
//   - the family's parent PIN, which unlocks settings on a child's device (and a
//     family display's), set by an admin;
//   - per child, which sections and features of the app they get, starting from
//     a preset (younger or older) that parents can then fine-tune, and what's on
//     their home screen;
//   - chat moderation: a child's new group waits for a parent's OK, and parents
//     can pause, block or delete any chat with a child in it, and delete messages.

// KidSettings are a child's switches. Missing keys follow the preset.
type KidSettings struct {
	Preset string `json:"preset"` // "younger" or "older"
	// Each module's access level: off, view, add or full (see levels).
	Modules map[string]string `json:"modules,omitempty"`
	// Before 0.20: "map" and "chat" on or off (read as the Thereabouts and Chitchat modules).
	Sections map[string]bool `json:"sections,omitempty"`
	Features map[string]bool `json:"features,omitempty"`
}

// Access levels for a module, least to most.
const (
	LevelOff  = "off"  // hidden
	LevelView = "view" // can look, not change (e.g. recipes a child is learning)
	LevelAdd  = "add"  // can add their own things, not change or delete others'
	LevelFull = "full" // everything
)

var levels = []string{LevelOff, LevelView, LevelAdd, LevelFull}

func levelAtLeast(have, want string) bool {
	return slices.Index(levels, have) >= slices.Index(levels, want)
}

type kidModule struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Plain   string `json:"plain"` // the plain-wording name
	Younger string `json:"younger"`
	Older   string `json:"older"`
}

// Modules parents set a level for, per child. Keep ids stable: they're stored.
var kidModules = []kidModule{
	{"thereabouts", "Thereabouts", "Map", LevelFull, LevelFull},
	{"chat", "Chitchat", "Chat", LevelFull, LevelFull},
	{"calendar", "Datebook", "Calendar", LevelOff, LevelFull},
	{"todos", "Odds & Ends", "Lists", LevelOff, LevelFull},
	{"chores", "Fair Share", "Chores", LevelOff, LevelFull},
	{"meals", "Pantry", "Meals", LevelOff, LevelFull},
}

type kidSwitch struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Module  string `json:"module,omitempty"` // shown under this module
	Younger bool   `json:"younger"`          // on in the "younger" preset
	Older   bool   `json:"older"`
}

// Single features parents switch per child. Keep ids stable: they're stored.
var kidFeatures = []kidSwitch{
	{"sos", "SOS button", "thereabouts", true, true},
	{"checkin", "Check in (\"I'm here\")", "thereabouts", true, true},
	{"requests", "Ask for a pick-up or a call", "thereabouts", true, true},
	{"nudges", "Send nudges (👍 ❤️ 👋 …)", "thereabouts", true, true},
	{"trips", "See other people's trips", "thereabouts", false, true},
	{"pause", "Pause sharing their location", "thereabouts", false, false},
	{"chat_start", "Start new chats and groups (groups with a child need a parent's OK)", "chat", true, true},
	{"chat_photos", "Send photos", "chat", true, true},
	{"chat_delete", "Delete their own messages", "chat", false, false},
	{"chat_mute", "Mute chats", "chat", false, false},
	{"voice", "Talk instead of typing, and hear messages read aloud", "chat", true, true},
}

// Home screen tiles. Module tiles show once the module is available and on.
// Each can carry a size: "chat:half", "map:big" (half, wide or big; the map is never half).
var homeTiles = []string{"map", "chat", "sos", "checkin", "calendar", "todos", "chores", "meals"}
var tileSizes = []string{"half", "wide", "big"}

const maxHomeTiles = 12

func presetFor(m *Member) string {
	if m.Kid != nil && (m.Kid.Preset == "younger" || m.Kid.Preset == "older") {
		return m.Kid.Preset
	}
	if m.PasswordAllowed {
		return "older"
	}
	return "younger"
}

// kidView is a child's effective settings: the preset with the parents' changes on top.
type kidView struct {
	Preset  string            `json:"preset"`
	Modules map[string]string `json:"modules"`
	// Sections is the pre-0.20 view (map, chat on or off), for older apps.
	Sections map[string]bool `json:"sections"`
	Features map[string]bool `json:"features"`
}

func (m *Member) kidSettings() kidView {
	v := kidView{Preset: presetFor(m), Modules: map[string]string{}, Sections: map[string]bool{}, Features: map[string]bool{}}
	child := m.role() == RoleChild
	var k KidSettings
	if m.Kid != nil {
		k = *m.Kid
	}
	for _, mod := range kidModules {
		lvl := mod.Older
		if v.Preset == "younger" {
			lvl = mod.Younger
		}
		// Settings from before levels: map/chat on or off.
		old := map[string]string{"thereabouts": "map", "chat": "chat"}[mod.ID]
		if on, ok := k.Sections[old]; ok && old != "" {
			lvl = map[bool]string{true: LevelFull, false: LevelOff}[on]
		}
		if val, ok := k.Modules[mod.ID]; ok {
			lvl = val
		}
		if !child {
			lvl = LevelFull
		}
		v.Modules[mod.ID] = lvl
	}
	v.Sections["map"] = v.Modules["thereabouts"] != LevelOff
	v.Sections["chat"] = v.Modules["chat"] != LevelOff
	for _, sw := range kidFeatures {
		on := sw.Older
		if v.Preset == "younger" {
			on = sw.Younger
		}
		if val, ok := k.Features[sw.ID]; ok {
			on = val
		}
		v.Features[sw.ID] = on || !child
	}
	return v
}

// level is this person's access to a module ("full" for adults).
func (m *Member) level(module string) string {
	if m.role() != RoleChild {
		return LevelFull
	}
	if l, ok := m.kidSettings().Modules[module]; ok {
		return l
	}
	return LevelFull
}

// can says whether this person may use a module ("map" and "chat" are the
// Thereabouts and Chitchat modules) or a feature. Adults can use everything.
func (m *Member) can(id string) bool {
	if m.role() != RoleChild {
		return true
	}
	v := m.kidSettings()
	switch id {
	case "map":
		id = "thereabouts"
	}
	if l, ok := v.Modules[id]; ok {
		return l != LevelOff
	}
	return v.Features[id]
}

func knownSwitch(list []kidSwitch, id string) bool {
	return slices.ContainsFunc(list, func(s kidSwitch) bool { return s.ID == id })
}

func knownModule(id string) bool {
	return slices.ContainsFunc(kidModules, func(m kidModule) bool { return m.ID == id })
}

func cleanHome(tiles []string) ([]string, bool) {
	out := []string{}
	seen := []string{}
	for _, t := range tiles {
		id, size, sized := strings.Cut(t, ":")
		if !slices.Contains(homeTiles, id) || (sized && !slices.Contains(tileSizes, size)) || (id == "map" && size == "half") {
			return nil, false
		}
		if !slices.Contains(seen, id) {
			seen = append(seen, id)
			out = append(out, t)
		}
	}
	return out, len(out) <= maxHomeTiles
}

var (
	ErrNotChild   = errors.New("these settings are for children")
	ErrNotAllowed = errors.New("a parent has switched that off")
)

// SetKidSettings stores a child's settings. A new preset starts them afresh from
// it; switches given alongside apply on top. home nil leaves it as is.
func (s *Store) SetKidSettings(id string, preset string, modules map[string]string, features map[string]bool, home []string) (*Member, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return nil, ErrNotFound
	}
	if m.role() != RoleChild {
		return nil, ErrNotChild
	}
	if m.Kid == nil {
		m.Kid = &KidSettings{Preset: presetFor(m)}
	}
	if preset != "" && preset != m.Kid.Preset {
		m.Kid = &KidSettings{Preset: preset}
	}
	set := func(dst *map[string]bool, src map[string]bool) {
		if len(src) == 0 {
			return
		}
		if *dst == nil {
			*dst = map[string]bool{}
		}
		for k, v := range src {
			(*dst)[k] = v
		}
	}
	if len(modules) > 0 {
		if m.Kid.Modules == nil {
			m.Kid.Modules = map[string]string{}
		}
		for k, v := range modules {
			m.Kid.Modules[k] = v
		}
		// Levels replace the older on/off sections.
		m.Kid.Sections = nil
	}
	set(&m.Kid.Features, features)
	if home != nil {
		m.Home = home
	}
	cp := *m
	return &cp, s.saveLocked()
}

// SetHome stores someone's own home screen (adults arrange their own).
func (s *Store) SetHome(id string, home []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	m.Home = home
	return s.saveLocked()
}

// ---- the parent PIN

var pinPattern = regexp.MustCompile(`^[0-9]{4,8}$`)

func (s *Store) SetParentPIN(hash string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.d.ParentPIN = hash
	return s.saveLocked()
}

func (s *Store) ParentPINHash() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.d.ParentPIN
}

func (s *Server) setPIN(w http.ResponseWriter, r *http.Request) {
	var body struct {
		PIN string `json:"pin"` // "" removes it
	}
	if !readJSON(w, r, &body) {
		return
	}
	hash := ""
	if body.PIN != "" {
		if !pinPattern.MatchString(body.PIN) {
			writeError(w, http.StatusBadRequest, "the PIN is 4 to 8 digits")
			return
		}
		var err error
		if hash, err = HashPassword(body.PIN); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	if err := s.store.SetParentPIN(hash); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleAdminPIN(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]bool{"set": s.store.ParentPINHash() != ""})
		return
	}
	s.setPIN(w, r)
}

// handleFamilyPIN lets an admin set the PIN from their phone.
func (s *Server) handleFamilyPIN(w http.ResponseWriter, r *http.Request, me *Member) {
	if !me.Admin || me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "only an admin can set the parent PIN")
		return
	}
	s.setPIN(w, r)
}

// handleCheckPIN: a device asks whether the PIN someone typed is right. Wrong
// guesses lock it for a while, like passwords.
func (s *Server) handleCheckPIN(w http.ResponseWriter, r *http.Request, me *Member) {
	var body struct {
		PIN string `json:"pin"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	hash := s.store.ParentPINHash()
	if hash == "" {
		writeError(w, http.StatusNotFound, "no parent PIN is set; an admin sets one in the web console")
		return
	}
	key := "pin:" + me.ID
	if d := s.limiter.Locked(key); d > 0 {
		writeError(w, http.StatusTooManyRequests, "too many wrong tries; try again in "+minutesText(d))
		return
	}
	if !VerifyPassword(body.PIN, hash) {
		s.limiter.Fail(key)
		writeError(w, http.StatusForbidden, "that's not the parent PIN")
		return
	}
	s.limiter.Succeed(key)
	w.WriteHeader(http.StatusNoContent)
}

// ---- a child's (or grandparent's) settings, for parents (app or console) and admins

type kidResponse struct {
	ID       string      `json:"id"`
	Name     string      `json:"name"`
	Access   string      `json:"access"`
	Settings *kidView    `json:"settings,omitempty"` // children only
	Home     []string    `json:"home"`
	Big      bool        `json:"big"`      // larger buttons and text
	Pictures bool        `json:"pictures"` // picture icons instead of the usual ones
	Modules  []kidModule `json:"modules"`  // the catalogue, with each preset's defaults
	Features []kidSwitch `json:"features"`
	Levels   []string    `json:"levels"`
	Tiles    []string    `json:"tiles"`
	Sizes    []string    `json:"sizes"`
}

func kidResponseFor(m *Member) kidResponse {
	home := m.Home
	if home == nil {
		home = []string{}
	}
	out := kidResponse{ID: m.ID, Name: m.Name, Access: m.access(), Home: home, Big: m.big(), Pictures: m.pictures(),
		Modules: kidModules, Features: kidFeatures, Levels: levels, Tiles: homeTiles, Sizes: tileSizes}
	if m.role() == RoleChild {
		v := m.kidSettings()
		out.Settings = &v
	}
	return out
}

// big and pictures: on by default for younger children, off for everyone else.
func (m *Member) big() bool {
	if m.Big != nil {
		return *m.Big
	}
	return m.access() == AccessYoungerChild
}

func (m *Member) pictures() bool {
	if m.Pictures != nil {
		return *m.Pictures
	}
	return m.access() == AccessYoungerChild
}

// SetAssist sets someone's larger buttons and picture icons (nil leaves one as is).
func (s *Store) SetAssist(id string, big, pictures *bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.d.Members[id]
	if !ok {
		return ErrNotFound
	}
	if big != nil {
		v := *big
		m.Big = &v
	}
	if pictures != nil {
		v := *pictures
		m.Pictures = &v
	}
	return s.saveLocked()
}

func (s *Server) kidSettingsHTTP(w http.ResponseWriter, r *http.Request) {
	m, ok := s.store.MemberByID(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such person")
		return
	}
	child := m.role() == RoleChild
	if !child && m.role() != RoleGrandparent {
		writeError(w, http.StatusBadRequest, "these settings are for children and grandparents")
		return
	}
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, kidResponseFor(m))
		return
	}
	var body struct {
		Preset   string            `json:"preset"`
		Modules  map[string]string `json:"modules"`
		Sections map[string]bool   `json:"sections"` // before 0.20
		Features map[string]bool   `json:"features"`
		Home     []string          `json:"home"`
		Big      *bool             `json:"big"`
		Pictures *bool             `json:"pictures"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Preset != "" && body.Preset != "younger" && body.Preset != "older" {
		writeError(w, http.StatusBadRequest, `preset is "younger" or "older"`)
		return
	}
	if body.Modules == nil {
		body.Modules = map[string]string{}
	}
	for id, on := range body.Sections {
		mod := map[string]string{"map": "thereabouts", "chat": "chat"}[id]
		if mod == "" {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("unknown section %q", id))
			return
		}
		body.Modules[mod] = map[bool]string{true: LevelFull, false: LevelOff}[on]
	}
	for id, lvl := range body.Modules {
		if !knownModule(id) || !slices.Contains(levels, lvl) {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("unknown module %q or level %q (off, view, add or full)", id, lvl))
			return
		}
	}
	for id := range body.Features {
		if !knownSwitch(kidFeatures, id) {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("unknown feature %q", id))
			return
		}
	}
	var home []string
	if body.Home != nil {
		var ok bool
		if home, ok = cleanHome(body.Home); !ok {
			writeError(w, http.StatusBadRequest, "unknown home tile or size, or too many")
			return
		}
	}
	if child && (body.Preset != "" || len(body.Modules) > 0 || len(body.Features) > 0) {
		if _, err := s.store.SetKidSettings(m.ID, body.Preset, body.Modules, body.Features, nil); err != nil {
			writeError(w, http.StatusInternalServerError, "could not save")
			return
		}
	}
	if home != nil {
		s.store.SetHome(m.ID, home)
	}
	if body.Big != nil || body.Pictures != nil {
		s.store.SetAssist(m.ID, body.Big, body.Pictures)
	}
	m, _ = s.store.MemberByID(m.ID)
	writeJSON(w, http.StatusOK, kidResponseFor(m))
}

func (s *Server) handleKidSettings(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "only parents can change these settings")
		return
	}
	s.kidSettingsHTTP(w, r)
}

// ---- opening settings on a child's device from afar

const EventUnlock = "unlock" // to one device's person: settings may open for Duration ms

func (s *Server) unlockHTTP(w http.ResponseWriter, r *http.Request, by string) {
	m, ok := s.store.MemberByID(r.PathValue("id"))
	if !ok || m.role() == RoleDependent {
		writeError(w, http.StatusNotFound, "no such person")
		return
	}
	var body struct {
		Minutes int `json:"minutes"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Minutes <= 0 || body.Minutes > 60 {
		body.Minutes = 10
	}
	s.store.Unlock(m.ID, by, time.Duration(body.Minutes)*time.Minute)
	w.WriteHeader(http.StatusNoContent)
}

// Unlock lets settings open on someone's device for a while.
func (s *Store) Unlock(id, byName string, d time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.addEventLocked(Event{Type: EventUnlock, To: id, MemberID: id, Name: byName, Duration: d.Milliseconds()})
	_ = s.saveLocked()
}

func (s *Server) handleUnlock(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "only parents can do that")
		return
	}
	s.unlockHTTP(w, r, me.Name)
}

func (s *Server) handleAdminUnlock(w http.ResponseWriter, r *http.Request) {
	name := "A parent"
	if m := s.session(r).Member; m != nil {
		name = m.Name
	}
	s.unlockHTTP(w, r, name)
}

// ---- chat moderation

// hasChild says whether a conversation has a child in it (parents oversee those).
func hasChild(conv *Conversation, people map[string]Member) bool {
	for _, id := range conv.Members {
		if m, ok := people[id]; ok && m.role() == RoleChild {
			return true
		}
	}
	return false
}

// parentIDs are everyone who can approve a child's group.
func (s *Server) parentIDs(except string) []string {
	var out []string
	for _, m := range s.store.Members() {
		if m.role() == RoleParent && m.ID != except {
			out = append(out, m.ID)
		}
	}
	return out
}

// askParents tells parents that a child's group needs their OK.
func (s *Server) askParents(conv *Conversation, from *Member, people map[string]Member) {
	var names []string
	for _, id := range conv.Members {
		if id != from.ID {
			if m, ok := people[id]; ok {
				names = append(names, m.Name)
			}
		}
	}
	note := fmt.Sprintf("%s wants a group, %q, with %s. Open it to say yes or no.", from.Name, conv.Name, joinNames(names))
	s.store.ChatEvents(s.parentIDs(from.ID), from.ID, from.Name, conv.ID, "A new group needs your OK", note, 0)
}

func joinNames(n []string) string {
	switch len(n) {
	case 0:
		return "nobody yet"
	case 1:
		return n[0]
	}
	return fmt.Sprintf("%s and %s", strings.Join(n[:len(n)-1], ", "), n[len(n)-1])
}

// moderate applies a parent's (or the main admin's) decision to a chat with a child in it.
func (s *Server) moderate(w http.ResponseWriter, r *http.Request, by *Member) {
	if !s.chatReady(w) {
		return
	}
	users, people := s.chatUsers()
	conv, err := s.chat.conversation(r.PathValue("id"), users)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such conversation")
		return
	}
	if conv.Kind == ConvFamily {
		writeError(w, http.StatusBadRequest, "the family chat can't be paused, blocked or deleted")
		return
	}
	if by != nil && !hasChild(conv, people) {
		writeError(w, http.StatusForbidden, "parents manage chats with a child in them")
		return
	}
	var body struct {
		Action string `json:"action"` // approve, pause, resume, block, unblock, delete
	}
	if !readJSON(w, r, &body) {
		return
	}
	status := ""
	switch body.Action {
	case "approve", "resume", "unblock":
	case "pause":
		status = ChatPaused
	case "block":
		status = ChatBlocked
	case "delete":
		if err := s.chat.DeleteConversation(conv.ID); err != nil {
			writeError(w, http.StatusInternalServerError, "could not delete")
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	default:
		writeError(w, http.StatusBadRequest, "action is approve, pause, resume, block, unblock or delete")
		return
	}
	if err := s.chat.SetStatus(conv.ID, status); err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	if body.Action == "approve" && conv.Status == ChatPending {
		from := &Member{Name: "A parent"}
		if by != nil {
			from = by
		}
		var to []string
		for _, id := range conv.Members {
			if by == nil || id != by.ID {
				to = append(to, id)
			}
		}
		s.store.ChatEvents(to, from.ID, from.Name, conv.ID, conv.Name, "Said yes to this group. You can chat now!", 0)
	}
	conv.Status = status
	viewer := ""
	if by != nil {
		viewer = by.ID
	}
	post := by != nil && slices.Contains(conv.Members, by.ID) && status == ""
	writeJSON(w, http.StatusOK, s.view(conv, viewer, !post, people))
}

func (s *Server) handleChatModerate(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "only parents can do that")
		return
	}
	s.moderate(w, r, me)
}

func (s *Server) handleAdminChatModerate(w http.ResponseWriter, r *http.Request) {
	s.moderate(w, r, s.session(r).Member)
}

// deleteMessage: people delete their own messages; parents (and the main admin)
// any message in a chat with a child in it.
func (s *Server) deleteMessage(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	id, _ := strconv.ParseInt(r.PathValue("mid"), 10, 64)
	msg, err := s.chat.Message(id)
	if err != nil || msg.Conv != r.PathValue("id") {
		writeError(w, http.StatusNotFound, "no such message")
		return
	}
	if me != nil {
		users, people := s.chatUsers()
		conv, err := s.chat.conversation(msg.Conv, users)
		if err != nil {
			writeError(w, http.StatusNotFound, "no such message")
			return
		}
		_, read := access(me, conv, people)
		own := msg.SenderID == me.ID && me.can("chat_delete")
		oversee := me.role() == RoleParent && hasChild(conv, people)
		if !read || !(own || oversee) {
			writeError(w, http.StatusForbidden, "you can delete your own messages; parents can delete messages in children's chats")
			return
		}
	}
	if err := s.chat.DeleteMessage(id); err != nil {
		writeError(w, http.StatusInternalServerError, "could not delete")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleChatDeleteMessage(w http.ResponseWriter, r *http.Request, me *Member) {
	s.deleteMessage(w, r, me)
}

func (s *Server) handleAdminChatDeleteMessage(w http.ResponseWriter, r *http.Request) {
	s.deleteMessage(w, r, s.session(r).Member)
}

// kidChatOK refuses chat to a child whose parents switched it off.
func kidChatOK(w http.ResponseWriter, me *Member) bool {
	if !me.can("chat") {
		writeError(w, http.StatusForbidden, "chat is switched off for you")
		return false
	}
	return true
}
