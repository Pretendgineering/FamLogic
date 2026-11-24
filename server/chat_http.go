package main

import (
	"io"
	"log"
	"net/http"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// EventChat tells one person about a new message (To = them).
const (
	EventChat       = "chat"
	EventChatUpdate = "chat_update" // a chat changed quietly (a reaction): apps refresh it
)

// convView is a conversation as one person sees it.
type convView struct {
	Conversation
	Title     string   `json:"title"` // "Family", the group's name, or the other person's name
	Last      *Message `json:"last,omitempty"`
	Unread    int      `json:"unread"`
	Oversight bool     `json:"oversight,omitempty"` // a parent looking at a child's chat: read only
}

// chatUsers are the people who use the app (everyone but non-users), by id.
func (s *Server) chatUsers() ([]string, map[string]Member) {
	var ids []string
	byID := map[string]Member{}
	for _, m := range s.store.Members() {
		if m.role() == RoleDependent {
			continue
		}
		ids = append(ids, m.ID)
		byID[m.ID] = m
	}
	sort.Strings(ids)
	return ids, byID
}

// access says whether someone can post in a conversation, or (parents) only read it.
// Pending and paused chats are read only for everyone; blocked ones are hidden from the children in them.
func access(me *Member, conv *Conversation, people map[string]Member) (post, read bool) {
	if slices.Contains(conv.Members, me.ID) {
		if conv.Status == ChatBlocked && me.role() == RoleChild {
			return false, false
		}
		// A child whose parents set Chitchat to "view only" reads but doesn't post.
		return conv.Status == "" && levelAtLeast(me.level("chat"), LevelAdd), true
	}
	if me.role() == RoleParent {
		for _, id := range conv.Members {
			if m, ok := people[id]; ok && m.role() == RoleChild {
				return false, true // parents can see their children's chats
			}
		}
	}
	return false, false
}

func titleFor(conv *Conversation, viewerID string, people map[string]Member) string {
	switch conv.Kind {
	case ConvFamily:
		return "Family"
	case ConvGroup:
		return conv.Name
	}
	var names []string
	for _, id := range conv.Members {
		if id != viewerID {
			if m, ok := people[id]; ok {
				names = append(names, m.Name)
			}
		}
	}
	if len(names) == 0 {
		return "Just you"
	}
	return strings.Join(names, " & ")
}

func (s *Server) view(conv *Conversation, viewerID string, oversight bool, people map[string]Member) convView {
	v := convView{Conversation: *conv, Title: titleFor(conv, viewerID, people), Oversight: oversight}
	if oversight && conv.Kind == ConvDirect {
		v.Title = titleFor(conv, "", people) // "Sam & Alex"
	}
	v.Last, _ = s.chat.Last(conv.ID)
	if !oversight && viewerID != "" {
		v.Unread, _ = s.chat.Unread(conv.ID, viewerID)
	}
	return v
}

func sortViews(out []convView) {
	sort.SliceStable(out, func(i, j int) bool {
		if (out[i].Kind == ConvFamily) != (out[j].Kind == ConvFamily) {
			return out[i].Kind == ConvFamily // the family chat first
		}
		ti, tj := out[i].CreatedAt, out[j].CreatedAt
		if out[i].Last != nil {
			ti = out[i].Last.Time
		}
		if out[j].Last != nil {
			tj = out[j].Last.Time
		}
		return ti > tj
	})
}

// chatReady checks the chat module is on and the database is open.
func (s *Server) chatReady(w http.ResponseWriter) bool {
	if s.chat == nil {
		writeError(w, http.StatusServiceUnavailable, "chat isn't available on this server")
		return false
	}
	if !s.store.ModuleOn("chat") {
		writeError(w, http.StatusNotFound, "chat is switched off for your family")
		return false
	}
	return true
}

// handleChatList: the conversations someone is in, plus (for parents) their children's.
func (s *Server) handleChatList(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	if me.role() == RoleDependent {
		writeError(w, http.StatusForbidden, "not a chat user")
		return
	}
	if !kidChatOK(w, me) {
		return
	}
	users, people := s.chatUsers()
	all, err := s.chat.allConversations(users)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read chats")
		return
	}
	out := []convView{}
	for i := range all {
		if post, read := access(me, &all[i], people); read {
			out = append(out, s.view(&all[i], me.ID, !post, people))
		}
	}
	sortViews(out)
	writeJSON(w, http.StatusOK, map[string]any{"conversations": out})
}

func (s *Server) loadConv(w http.ResponseWriter, r *http.Request, me *Member) (*Conversation, map[string]Member, bool, bool) {
	if !kidChatOK(w, me) {
		return nil, nil, false, false
	}
	users, people := s.chatUsers()
	conv, err := s.chat.conversation(r.PathValue("id"), users)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such conversation")
		return nil, nil, false, false
	}
	post, read := access(me, conv, people)
	if !read {
		writeError(w, http.StatusNotFound, "no such conversation")
		return nil, nil, false, false
	}
	return conv, people, post, true
}

func (s *Server) handleChatMessages(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	conv, people, post, ok := s.loadConv(w, r, me)
	if !ok {
		return
	}
	before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	msgs, err := s.chat.Messages(conv.ID, before, after, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read messages")
		return
	}
	reads, _ := s.chat.Reads(conv.ID)
	writeJSON(w, http.StatusOK, map[string]any{
		"conversation": s.view(conv, me.ID, !post, people), "messages": msgs, "reads": reads, "canPost": post,
		"typing": s.typing.who(conv.ID, me.ID),
	})
}

func (s *Server) handleChatPost(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	conv, people, post, ok := s.loadConv(w, r, me)
	if !ok {
		return
	}
	if !post {
		switch {
		case slices.Contains(conv.Members, me.ID) && conv.Status == ChatPending:
			writeError(w, http.StatusForbidden, "this group is waiting for a parent to say yes")
		case slices.Contains(conv.Members, me.ID) && conv.Status != "":
			writeError(w, http.StatusForbidden, "a parent has paused this chat")
		default:
			writeError(w, http.StatusForbidden, "you can read this chat, but not post in it")
		}
		return
	}
	var text, photo, audio string
	var replyTo, audioMs int64
	if strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/") {
		r.Body = http.MaxBytesReader(w, r.Body, maxPhotoBytes+(1<<20))
		if err := r.ParseMultipartForm(4 << 20); err != nil {
			writeError(w, http.StatusRequestEntityTooLarge, "that photo is too big")
			return
		}
		text = r.FormValue("text")
		replyTo, _ = strconv.ParseInt(r.FormValue("replyTo"), 10, 64)
		// A voice message.
		if f, _, err := r.FormFile("audio"); err == nil {
			data, err := io.ReadAll(io.LimitReader(f, maxAudioBytes+1))
			f.Close()
			if err != nil || len(data) > maxAudioBytes {
				writeError(w, http.StatusRequestEntityTooLarge, "that recording is too long")
				return
			}
			if audio, err = s.chat.SaveAudio(data); err != nil {
				writeError(w, http.StatusBadRequest, capitalize(err.Error()))
				return
			}
			audioMs, _ = strconv.ParseInt(r.FormValue("audioMs"), 10, 64)
			audioMs = max(0, min(audioMs, 5*60_000))
		}
		if f, _, err := r.FormFile("photo"); err == nil {
			if !me.can("chat_photos") {
				f.Close()
				writeError(w, http.StatusForbidden, "sending photos is switched off for you")
				return
			}
			data, err := io.ReadAll(io.LimitReader(f, maxPhotoBytes+1))
			f.Close()
			if err != nil || len(data) > maxPhotoBytes {
				writeError(w, http.StatusRequestEntityTooLarge, "that photo is too big")
				return
			}
			if photo, err = s.chat.SavePhoto(data); err != nil {
				writeError(w, http.StatusBadRequest, capitalize(err.Error()))
				return
			}
		}
	} else {
		var body struct {
			Text    string `json:"text"`
			ReplyTo int64  `json:"replyTo"`
		}
		if !readJSON(w, r, &body) {
			return
		}
		text, replyTo = body.Text, body.ReplyTo
	}
	text, okLen := cleanMessage(text)
	if !okLen {
		writeError(w, http.StatusBadRequest, "that message is too long")
		return
	}
	if text == "" && photo == "" && audio == "" {
		writeError(w, http.StatusBadRequest, "an empty message")
		return
	}
	msg, err := s.chat.PostFull(&Message{Conv: conv.ID, SenderID: me.ID, Text: text, Photo: photo, ReplyTo: replyTo, Audio: audio, AudioMs: audioMs})
	s.typing.stop(conv.ID, me.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not send")
		return
	}
	s.announce(conv, me, msg, people)
	writeJSON(w, http.StatusOK, msg)
}

// announce tells everyone else in the conversation, through the same alert stream as SOS.
func (s *Server) announce(conv *Conversation, from *Member, msg *Message, people map[string]Member) {
	preview := msg.Text
	if msg.Photo != "" && preview == "" {
		preview = "Photo"
	}
	if msg.Audio != "" && preview == "" {
		preview = "Voice message"
	}
	if r := []rune(preview); len(r) > 140 {
		preview = string(r[:140]) + "…"
	}
	var to []string
	for _, id := range conv.Members {
		if id != from.ID {
			to = append(to, id)
		}
	}
	title := ""
	if conv.Kind != ConvDirect {
		title = titleFor(conv, "", people)
	}
	s.store.ChatEvents(to, from.ID, from.Name, conv.ID, title, preview, msg.ID)
}

func (s *Server) handleChatRead(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	conv, _, post, ok := s.loadConv(w, r, me)
	if !ok {
		return
	}
	var body struct {
		UpTo int64 `json:"upTo"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if post { // parents looking in don't leave read receipts
		s.chat.MarkRead(conv.ID, me.ID, body.UpTo)
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleChatDirect opens (or starts) a one-to-one chat with someone in the family.
func (s *Server) handleChatDirect(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	var body struct {
		To string `json:"to"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if !kidChatOK(w, me) {
		return
	}
	if !me.can("chat_start") {
		writeError(w, http.StatusForbidden, "ask a parent to start a new chat")
		return
	}
	_, people := s.chatUsers()
	if _, ok := people[body.To]; !ok || body.To == me.ID {
		writeError(w, http.StatusBadRequest, "choose someone in your family")
		return
	}
	id, err := s.chat.Direct(me.ID, body.To)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not start the chat")
		return
	}
	s.writeConv(w, id, me, people)
}

func (s *Server) writeConv(w http.ResponseWriter, id string, me *Member, people map[string]Member) {
	users, _ := s.chatUsers()
	conv, err := s.chat.conversation(id, users)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read the chat")
		return
	}
	writeJSON(w, http.StatusOK, s.view(conv, me.ID, false, people))
}

func cleanGroupName(n string) (string, bool) {
	n = strings.Join(strings.Fields(n), " ")
	return n, n != "" && len([]rune(n)) <= 40
}

func (s *Server) cleanPeople(ids []string, people map[string]Member) ([]string, bool) {
	var out []string
	for _, id := range ids {
		if _, ok := people[id]; !ok {
			return nil, false
		}
		if !slices.Contains(out, id) {
			out = append(out, id)
		}
	}
	return out, true
}

func (s *Server) handleChatCreateGroup(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	var body struct {
		Name    string   `json:"name"`
		Members []string `json:"members"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	name, ok := cleanGroupName(body.Name)
	if !ok {
		writeError(w, http.StatusBadRequest, "give the group a name (up to 40 characters)")
		return
	}
	_, people := s.chatUsers()
	members, ok := s.cleanPeople(body.Members, people)
	if !ok || len(members) == 0 {
		writeError(w, http.StatusBadRequest, "choose people in your family")
		return
	}
	if !kidChatOK(w, me) {
		return
	}
	if !me.can("chat_start") {
		writeError(w, http.StatusForbidden, "ask a parent to start a new group")
		return
	}
	// A group with a child in it starts once a parent says yes, unless a parent made it.
	status := ""
	if me.role() != RoleParent && hasChild(&Conversation{Members: append([]string{me.ID}, members...)}, people) {
		status = ChatPending
	}
	id, err := s.chat.CreateGroup(me.ID, name, members, status)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not make the group")
		return
	}
	if status == ChatPending {
		users, _ := s.chatUsers()
		if conv, err := s.chat.conversation(id, users); err == nil {
			s.askParents(conv, me, people)
		}
	}
	s.writeConv(w, id, me, people)
}

// handleChatUpdateGroup renames a group, adds people, or removes them. Anyone in
// the group can rename it, add people and leave; removing someone else takes the
// group's creator or a parent.
func (s *Server) handleChatUpdateGroup(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	conv, people, post, ok := s.loadConv(w, r, me)
	if !ok {
		return
	}
	var body struct {
		Emoji  *string  `json:"emoji"`
		Color  *string  `json:"color"`
		Name   *string  `json:"name"`
		Add    []string `json:"add"`
		Remove []string `json:"remove"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	// The icon: the family chat's is the parents' to choose; a group's, anyone in it.
	if body.Emoji != nil || body.Color != nil {
		switch {
		case conv.Kind == ConvFamily && me.role() != RoleParent:
			writeError(w, http.StatusForbidden, "parents choose the family chat's icon")
			return
		case conv.Kind == ConvGroup && (!post || !slices.Contains(conv.Members, me.ID)):
			writeError(w, http.StatusForbidden, "only people in a group can change its icon")
			return
		case conv.Kind == ConvDirect:
			writeError(w, http.StatusBadRequest, "one-to-one chats show the other person's icon")
			return
		}
		emoji, color := conv.Emoji, conv.Color
		if body.Emoji != nil {
			e, ok := cleanEmoji(*body.Emoji)
			if !ok {
				writeError(w, http.StatusBadRequest, "pick a single emoji (or none)")
				return
			}
			emoji = e
		}
		if body.Color != nil {
			if *body.Color != "" && !colorRe.MatchString(*body.Color) {
				writeError(w, http.StatusBadRequest, "colour must look like #6f5aa8")
				return
			}
			color = *body.Color
		}
		s.chat.SetIcon(conv.ID, emoji, color)
		if body.Name == nil && len(body.Add) == 0 && len(body.Remove) == 0 {
			s.writeConv(w, conv.ID, me, people)
			return
		}
	}
	if !slices.Contains(conv.Members, me.ID) || conv.Kind != ConvGroup {
		writeError(w, http.StatusForbidden, "only people in a group chat can change it")
		return
	}
	// While a group waits for a parent, or a parent has paused it, people can only leave.
	if !post && (body.Name != nil || len(body.Add) > 0 || slices.ContainsFunc(body.Remove, func(id string) bool { return id != me.ID })) {
		writeError(w, http.StatusForbidden, "a parent needs to say yes before this group can change")
		return
	}
	if body.Name != nil {
		name, ok := cleanGroupName(*body.Name)
		if !ok {
			writeError(w, http.StatusBadRequest, "group names are 1-40 characters")
			return
		}
		s.chat.RenameGroup(conv.ID, name)
	}
	if len(body.Add) > 0 {
		add, ok := s.cleanPeople(body.Add, people)
		if !ok {
			writeError(w, http.StatusBadRequest, "choose people in your family")
			return
		}
		s.chat.AddToGroup(conv.ID, add)
		// A child adding people: the group waits for a parent again.
		if me.role() == RoleChild {
			s.chat.SetStatus(conv.ID, ChatPending)
			users, _ := s.chatUsers()
			if c, err := s.chat.conversation(conv.ID, users); err == nil {
				s.askParents(c, me, people)
			}
		}
	}
	for _, id := range body.Remove {
		if id != me.ID && conv.CreatedBy != me.ID && me.role() != RoleParent {
			writeError(w, http.StatusForbidden, "only the group's creator or a parent can remove someone")
			return
		}
		s.chat.RemoveFromGroup(conv.ID, id)
	}
	if slices.Contains(body.Remove, me.ID) {
		w.WriteHeader(http.StatusNoContent) // left the group
		return
	}
	s.writeConv(w, conv.ID, me, people)
}

// handleChatMedia serves a photo to someone who can read its conversation (or the main admin).
func (s *Server) handleChatMedia(w http.ResponseWriter, r *http.Request) {
	if s.chat == nil {
		http.NotFound(w, r)
		return
	}
	name := filepath.Base(r.PathValue("name"))
	path, ok := s.chat.PhotoPath(name)
	if !ok {
		http.NotFound(w, r)
		return
	}
	convID, err := s.chat.PhotoConversation(name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if !s.session(r).Owner {
		me := s.requestMember(r)
		if me == nil {
			writeError(w, http.StatusUnauthorized, "please sign in")
			return
		}
		users, people := s.chatUsers()
		conv, err := s.chat.conversation(convID, users)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		if _, read := access(me, conv, people); !read {
			http.NotFound(w, r)
			return
		}
	}
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	http.ServeFile(w, r, path)
}

// requestMember is the person behind a request: a phone's token, or a parent's console session.
func (s *Server) requestMember(r *http.Request) *Member {
	if token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
		m, _ := s.store.lookupToken(strings.TrimSpace(token))
		return m
	}
	return s.session(r).Member
}

// ---- the main admin's view (admin password): every conversation, read only.

func (s *Server) handleAdminChatList(w http.ResponseWriter, r *http.Request) {
	if s.chat == nil {
		writeJSON(w, http.StatusOK, map[string]any{"conversations": []convView{}})
		return
	}
	users, people := s.chatUsers()
	all, err := s.chat.allConversations(users)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read chats")
		return
	}
	out := []convView{}
	for i := range all {
		out = append(out, s.view(&all[i], "", true, people))
	}
	sortViews(out)
	writeJSON(w, http.StatusOK, map[string]any{"conversations": out})
}

func (s *Server) handleAdminChatMessages(w http.ResponseWriter, r *http.Request) {
	if s.chat == nil {
		http.NotFound(w, r)
		return
	}
	users, people := s.chatUsers()
	conv, err := s.chat.conversation(r.PathValue("id"), users)
	if err != nil {
		writeError(w, http.StatusNotFound, "no such conversation")
		return
	}
	before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
	msgs, err := s.chat.Messages(conv.ID, before, 0, 0)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not read messages")
		return
	}
	reads, _ := s.chat.Reads(conv.ID)
	writeJSON(w, http.StatusOK, map[string]any{"conversation": s.view(conv, "", true, people), "messages": msgs, "reads": reads, "canPost": false})
}

// openChat opens chat.db next to the data file. Without it the server still runs; chat says it's unavailable.
func openChatNextTo(dataFile string) *ChatStore {
	c, err := OpenChat(filepath.Dir(dataFile))
	if err != nil {
		log.Printf("chat database: %v (chat is unavailable)", err)
		return nil
	}
	return c
}

// ---- reactions, typing, search

// POST /api/chat/{id}/messages/{mid}/react {kind}: react with a nudge ("" takes it back).
func (s *Server) handleChatReact(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	conv, people, post, ok := s.loadConv(w, r, me)
	if !ok {
		return
	}
	if !post {
		writeError(w, http.StatusForbidden, "you can read this chat, but not react in it")
		return
	}
	var body struct {
		Kind string `json:"kind"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if body.Kind != "" && !reactionKinds[body.Kind] {
		writeError(w, http.StatusBadRequest, "unknown reaction")
		return
	}
	mid, _ := strconv.ParseInt(r.PathValue("mid"), 10, 64)
	if err := s.chat.React(conv.ID, mid, me.ID, body.Kind); err != nil {
		writeError(w, http.StatusNotFound, "no such message")
		return
	}
	// Everyone else's chat refreshes (quietly: no notification for a reaction).
	var to []string
	for _, id := range conv.Members {
		if id != me.ID {
			to = append(to, id)
		}
	}
	s.store.ChatQuiet(to, conv.ID)
	_ = people
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/chat/{id}/typing: "I'm typing" (it lasts a few seconds).
func (s *Server) handleChatTyping(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	conv, _, post, ok := s.loadConv(w, r, me)
	if !ok || !post {
		if ok {
			w.WriteHeader(http.StatusNoContent)
		}
		return
	}
	s.typing.start(conv.ID, me.ID)
	w.WriteHeader(http.StatusNoContent)
}

// GET /api/chat/search?q=: messages you can read that contain q, newest first.
func (s *Server) handleChatSearch(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.chatReady(w) {
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if utf8.RuneCountInString(q) < 2 {
		writeJSON(w, http.StatusOK, map[string]any{"messages": []Message{}})
		return
	}
	users, people := s.chatUsers()
	all, err := s.chat.allConversations(users)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not search")
		return
	}
	ids := []string{}
	for i := range all {
		if _, read := access(me, &all[i], people); read {
			ids = append(ids, all[i].ID)
		}
	}
	msgs, err := s.chat.Search(ids, q, 50)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not search")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": msgs})
}

// reactionKinds: the friendly nudges, as reactions.
var reactionKinds = map[string]bool{"thumbs": true, "heart": true, "smile": true, "wave": true, "cowboy": true, "alien": true}

// typingNow remembers who's typing where, for a few seconds (memory only).
type typingNow struct {
	mu   sync.Mutex
	seen map[string]map[string]time.Time // conv → member → when
}

func (t *typingNow) start(conv, member string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.seen == nil {
		t.seen = map[string]map[string]time.Time{}
	}
	if t.seen[conv] == nil {
		t.seen[conv] = map[string]time.Time{}
	}
	t.seen[conv][member] = time.Now()
}

func (t *typingNow) stop(conv, member string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.seen[conv], member)
}

func (t *typingNow) who(conv, except string) []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := []string{}
	for id, at := range t.seen[conv] {
		if id != except && time.Since(at) < 6*time.Second {
			out = append(out, id)
		}
	}
	return out
}
