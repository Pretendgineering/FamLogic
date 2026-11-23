package main

import (
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// Parents' controls for children:
//   - the family's parent PIN, which unlocks settings on a child's device (and a
//     family display's), set by an admin;
//   - per child, which sections and features of the app they get, starting from
//     a preset (younger or older) that parents can then fine-tune, and what's on
//     their home screen;
//   - chat moderation: a child's new group waits for a parent's OK, and parents
//     can pause, block or delete any chat with a child in it, and delete messages.

// KidSettings are a child's switches. Nil maps (or missing keys) follow the preset.
type KidSettings struct {
	Preset   string          `json:"preset"` // "younger" or "older"
	Sections map[string]bool `json:"sections,omitempty"`
	Features map[string]bool `json:"features,omitempty"`
}

type kidSwitch struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Younger bool   `json:"younger"` // on in the "younger" preset
	Older   bool   `json:"older"`
}

// The parts of the app parents can switch per child. Keep ids stable: they're stored.
var kidSections = []kidSwitch{
	{"map", "Map: where everyone is", false, true},
	{"chat", "Chat", true, true},
}

var kidFeatures = []kidSwitch{
	{"sos", "SOS button", true, true},
	{"checkin", "Check in (\"I'm here\")", true, true},
	{"requests", "Ask for a pick-up or a call", true, true},
	{"chat_start", "Start new chats and groups (groups with a child need a parent's OK)", true, true},
	{"chat_photos", "Send photos in chat", true, true},
	{"trips", "See other people's trips", false, true},
	{"pause", "Pause sharing their location", false, false},
}

// Home screen tiles. Module tiles show once the module is available and on.
var homeTiles = []string{"map", "chat", "sos", "checkin", "calendar", "todos", "chores", "meals"}

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
	Preset   string          `json:"preset"`
	Sections map[string]bool `json:"sections"`
	Features map[string]bool `json:"features"`
}

func (m *Member) kidSettings() kidView {
	v := kidView{Preset: presetFor(m), Sections: map[string]bool{}, Features: map[string]bool{}}
	fill := func(dst map[string]bool, list []kidSwitch, stored map[string]bool) {
		for _, sw := range list {
			on := sw.Older
			if v.Preset == "younger" {
				on = sw.Younger
			}
			if m.role() != RoleChild {
				on = true
			} else if val, ok := stored[sw.ID]; ok {
				on = val
			}
			dst[sw.ID] = on
		}
	}
	var sec, feat map[string]bool
	if m.Kid != nil {
		sec, feat = m.Kid.Sections, m.Kid.Features
	}
	fill(v.Sections, kidSections, sec)
	fill(v.Features, kidFeatures, feat)
	return v
}

// can says whether this person may use a section or feature. Adults can use everything.
func (m *Member) can(id string) bool {
	if m.role() != RoleChild {
		return true
	}
	v := m.kidSettings()
	if on, ok := v.Sections[id]; ok {
		return on
	}
	return v.Features[id]
}

func knownSwitch(list []kidSwitch, id string) bool {
	return slices.ContainsFunc(list, func(s kidSwitch) bool { return s.ID == id })
}

func cleanHome(tiles []string) ([]string, bool) {
	out := []string{}
	for _, t := range tiles {
		if !slices.Contains(homeTiles, t) {
			return nil, false
		}
		if !slices.Contains(out, t) {
			out = append(out, t)
		}
	}
	return out, len(out) <= maxHomeTiles
}

var ErrNotChild = errors.New("these settings are for children")

// SetKidSettings stores a child's settings. A new preset starts them afresh from
// it; switches given alongside apply on top. home nil leaves it as is.
func (s *Store) SetKidSettings(id string, preset string, sections, features map[string]bool, home []string) (*Member, error) {
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
	set(&m.Kid.Sections, sections)
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

// ---- a child's settings, for parents (app or console) and admins

type kidResponse struct {
	ID       string      `json:"id"`
	Name     string      `json:"name"`
	Settings kidView     `json:"settings"`
	Home     []string    `json:"home"`
	Sections []kidSwitch `json:"sections"` // the catalogue, with each preset's defaults
	Features []kidSwitch `json:"features"`
	Tiles    []string    `json:"tiles"`
}

func kidResponseFor(m *Member) kidResponse {
	home := m.Home
	if home == nil {
		home = []string{}
	}
	return kidResponse{ID: m.ID, Name: m.Name, Settings: m.kidSettings(), Home: home,
		Sections: kidSections, Features: kidFeatures, Tiles: homeTiles}
}

func (s *Server) kidSettingsHTTP(w http.ResponseWriter, r *http.Request) {
	m, ok := s.store.MemberByID(r.PathValue("id"))
	if !ok {
		writeError(w, http.StatusNotFound, "no such person")
		return
	}
	if m.role() != RoleChild {
		writeError(w, http.StatusBadRequest, "these settings are for children")
		return
	}
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, kidResponseFor(m))
		return
	}
	var body struct {
		Preset   string          `json:"preset"`
		Sections map[string]bool `json:"sections"`
		Features map[string]bool `json:"features"`
		Home     []string        `json:"home"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Preset != "" && body.Preset != "younger" && body.Preset != "older" {
		writeError(w, http.StatusBadRequest, `preset is "younger" or "older"`)
		return
	}
	for id := range body.Sections {
		if !knownSwitch(kidSections, id) {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("unknown section %q", id))
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
			writeError(w, http.StatusBadRequest, "unknown home tile, or too many")
			return
		}
	}
	m, err := s.store.SetKidSettings(m.ID, body.Preset, body.Sections, body.Features, home)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not save")
		return
	}
	writeJSON(w, http.StatusOK, kidResponseFor(m))
}

func (s *Server) handleKidSettings(w http.ResponseWriter, r *http.Request, me *Member) {
	if me.role() != RoleParent {
		writeError(w, http.StatusForbidden, "only parents can change a child's settings")
		return
	}
	s.kidSettingsHTTP(w, r)
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
		own := msg.SenderID == me.ID
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
