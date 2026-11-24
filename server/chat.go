package main

import (
	"bytes"
	"database/sql"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
	_ "modernc.org/sqlite"
)

// Chat (the "chat" module): the family chat (everyone who uses the app), named
// group chats, and one-to-one chats. Messages live in SQLite (chat.db in the data
// folder: a chat history is too big and busy for the JSON file), photos in media/.
// Parents can read, but not post in, any conversation one of their children is in.

const (
	ConvFamily = "family"
	ConvGroup  = "group"
	ConvDirect = "direct"

	familyConvID   = "family"
	maxMessageLen  = 4000
	maxPhotoBytes  = 15 << 20
	maxAudioBytes  = 4 << 20 // a few minutes of AAC
	maxPhotoSide   = 1600
	messagesPerGet = 50
)

var (
	ErrNotInConversation = errors.New("not in this conversation")
	ErrChatOff           = errors.New("chat is switched off")
)

type ChatStore struct {
	db       *sql.DB
	mediaDir string
}

type Conversation struct {
	ID        string   `json:"id"`
	Kind      string   `json:"kind"`
	Name      string   `json:"name"`
	CreatedBy string   `json:"createdBy,omitempty"`
	CreatedAt int64    `json:"createdAt"`
	Members   []string `json:"members"` // for the family chat: everyone who uses the app
	// "" (open), or set by parents for chats with a child in them (see kids.go):
	// "pending" (a child's new group, waiting for a parent's OK), "paused" (read
	// only) or "blocked" (hidden from the children in it).
	Status string `json:"status,omitempty"`
	// The chat's icon (family chat and groups): an emoji and a colour; "" = the default.
	Emoji string `json:"emoji,omitempty"`
	Color string `json:"color,omitempty"`
}

const (
	ChatPending = "pending"
	ChatPaused  = "paused"
	ChatBlocked = "blocked"
)

type Message struct {
	ID       int64  `json:"id"`
	Conv     string `json:"conv"`
	SenderID string `json:"sender"`
	Text     string `json:"text"`
	Photo    string `json:"photo,omitempty"` // file name under /api/chat-media/
	Time     int64  `json:"time"`
	// A reply: the message it answers, and a glimpse of it.
	ReplyTo     int64  `json:"replyTo,omitempty"`
	ReplySender string `json:"replySender,omitempty"`
	ReplyText   string `json:"replyText,omitempty"`
	// A voice message: a file under /api/chat-media/ and its length.
	Audio   string `json:"audio,omitempty"`
	AudioMs int64  `json:"audioMs,omitempty"`
	// Reactions: kind (a nudge, e.g. "heart") → who.
	Reactions map[string][]string `json:"reactions,omitempty"`
}

func OpenChat(dir string) (*ChatStore, error) {
	media := filepath.Join(dir, "media")
	if err := os.MkdirAll(media, 0o700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", "file:"+filepath.Join(dir, "chat.db")+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1) // SQLite writes one at a time; keeps things simple and safe
	_, err = db.Exec(`
CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  created_by TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  pair TEXT UNIQUE
);
CREATE TABLE IF NOT EXISTS conversation_members (
  conv_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  member_id TEXT NOT NULL,
  PRIMARY KEY (conv_id, member_id)
);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conv_id TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  photo TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_by_conv ON messages (conv_id, id);
CREATE TABLE IF NOT EXISTS reads (
  conv_id TEXT NOT NULL,
  member_id TEXT NOT NULL,
  last_read INTEGER NOT NULL,
  PRIMARY KEY (conv_id, member_id)
);
INSERT OR IGNORE INTO conversations (id, kind, name, created_at) VALUES ('family', 'family', '', 0);
`)
	if err != nil {
		db.Close()
		return nil, err
	}
	// Added in 0.20: an icon (emoji and colour) for the family chat and groups.
	for _, col := range []string{"emoji", "color"} {
		if _, err := db.Exec(`ALTER TABLE conversations ADD COLUMN ` + col + ` TEXT NOT NULL DEFAULT ''`); err != nil && !strings.Contains(err.Error(), "duplicate column") {
			db.Close()
			return nil, err
		}
	}
	// Added in 0.32: replies, voice messages and reactions.
	for _, col := range []string{`reply_to INTEGER NOT NULL DEFAULT 0`, `audio TEXT NOT NULL DEFAULT ''`, `audio_ms INTEGER NOT NULL DEFAULT 0`} {
		if _, err := db.Exec(`ALTER TABLE messages ADD COLUMN ` + col); err != nil && !strings.Contains(err.Error(), "duplicate column") {
			db.Close()
			return nil, err
		}
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS reactions (
  message_id INTEGER NOT NULL,
  member_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  PRIMARY KEY (message_id, member_id)
)`); err != nil {
		db.Close()
		return nil, err
	}
	// Added in 0.19: parents' approval and moderation.
	if _, err := db.Exec(`ALTER TABLE conversations ADD COLUMN status TEXT NOT NULL DEFAULT ''`); err != nil && !strings.Contains(err.Error(), "duplicate column") {
		db.Close()
		return nil, err
	}
	return &ChatStore{db: db, mediaDir: media}, nil
}

func (c *ChatStore) Close() error { return c.db.Close() }

// Backup writes a consistent copy of the chat database (photos stay in media/).
func (c *ChatStore) Backup(path string) error {
	os.Remove(path)
	_, err := c.db.Exec(`VACUUM INTO ?`, path)
	return err
}

// ---- conversations

func (c *ChatStore) conversation(id string, users []string) (*Conversation, error) {
	var conv Conversation
	err := c.db.QueryRow(`SELECT id, kind, name, created_by, created_at, status, emoji, color FROM conversations WHERE id = ?`, id).
		Scan(&conv.ID, &conv.Kind, &conv.Name, &conv.CreatedBy, &conv.CreatedAt, &conv.Status, &conv.Emoji, &conv.Color)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if conv.Kind == ConvFamily {
		conv.Members = slices.Clone(users)
		return &conv, nil
	}
	rows, err := c.db.Query(`SELECT member_id FROM conversation_members WHERE conv_id = ? ORDER BY member_id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	conv.Members = []string{}
	for rows.Next() {
		var m string
		rows.Scan(&m)
		conv.Members = append(conv.Members, m)
	}
	return &conv, rows.Err()
}

// conversationsWith lists the conversations someone is in (the family chat always).
func (c *ChatStore) conversationsWith(memberID string, users []string) ([]Conversation, error) {
	rows, err := c.db.Query(`SELECT conv_id FROM conversation_members WHERE member_id = ?`, memberID)
	if err != nil {
		return nil, err
	}
	ids := []string{familyConvID}
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	return c.conversationsByID(ids, users)
}

func (c *ChatStore) conversationsByID(ids []string, users []string) ([]Conversation, error) {
	var out []Conversation
	for _, id := range ids {
		conv, err := c.conversation(id, users)
		if err == nil {
			out = append(out, *conv)
		}
	}
	return out, nil
}

// allConversations is every conversation (for the main admin and for parents' oversight).
func (c *ChatStore) allConversations(users []string) ([]Conversation, error) {
	rows, err := c.db.Query(`SELECT id FROM conversations`)
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	return c.conversationsByID(ids, users)
}

// Direct returns the one-to-one conversation between two people, creating it if needed.
func (c *ChatStore) Direct(a, b string) (string, error) {
	pair := []string{a, b}
	sort.Strings(pair)
	key := pair[0] + "|" + pair[1]
	var id string
	err := c.db.QueryRow(`SELECT id FROM conversations WHERE pair = ?`, key).Scan(&id)
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	id = randomString(10)
	tx, err := c.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`INSERT INTO conversations (id, kind, created_by, created_at, pair) VALUES (?, 'direct', ?, ?, ?)`,
		id, a, time.Now().UnixMilli(), key); err != nil {
		return "", err
	}
	for _, m := range pair {
		if _, err := tx.Exec(`INSERT INTO conversation_members (conv_id, member_id) VALUES (?, ?)`, id, m); err != nil {
			return "", err
		}
	}
	return id, tx.Commit()
}

// CreateGroup makes a named group chat with its creator and the given people.
// status is "" or ChatPending (a child's group, until a parent says yes).
func (c *ChatStore) CreateGroup(creator, name string, members []string, status string) (string, error) {
	id := randomString(10)
	tx, err := c.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`INSERT INTO conversations (id, kind, name, created_by, created_at, status) VALUES (?, 'group', ?, ?, ?, ?)`,
		id, name, creator, time.Now().UnixMilli(), status); err != nil {
		return "", err
	}
	for _, m := range append([]string{creator}, members...) {
		if _, err := tx.Exec(`INSERT OR IGNORE INTO conversation_members (conv_id, member_id) VALUES (?, ?)`, id, m); err != nil {
			return "", err
		}
	}
	return id, tx.Commit()
}

func (c *ChatStore) RenameGroup(id, name string) error {
	_, err := c.db.Exec(`UPDATE conversations SET name = ? WHERE id = ? AND kind = 'group'`, name, id)
	return err
}

func (c *ChatStore) AddToGroup(id string, members []string) error {
	for _, m := range members {
		if _, err := c.db.Exec(`INSERT OR IGNORE INTO conversation_members (conv_id, member_id) VALUES (?, ?)`, id, m); err != nil {
			return err
		}
	}
	return nil
}

func (c *ChatStore) RemoveFromGroup(id, member string) error {
	_, err := c.db.Exec(`DELETE FROM conversation_members WHERE conv_id = ? AND member_id = ?`, id, member)
	return err
}

// ForgetMember removes someone who left the family from every group and direct chat.
func (c *ChatStore) ForgetMember(member string) error {
	_, err := c.db.Exec(`DELETE FROM conversation_members WHERE member_id = ?`, member)
	return err
}

// SetIcon sets a family or group chat's emoji and colour.
func (c *ChatStore) SetIcon(id, emoji, color string) error {
	_, err := c.db.Exec(`UPDATE conversations SET emoji = ?, color = ? WHERE id = ? AND kind != 'direct'`, emoji, color, id)
	return err
}

// SetStatus opens, pauses or blocks a group or one-to-one chat (not the family chat).
func (c *ChatStore) SetStatus(id, status string) error {
	_, err := c.db.Exec(`UPDATE conversations SET status = ? WHERE id = ? AND kind != 'family'`, status, id)
	return err
}

// DeleteConversation removes a group or one-to-one chat with all its messages and photos.
func (c *ChatStore) DeleteConversation(id string) error {
	if id == familyConvID {
		return errors.New("the family chat can't be deleted")
	}
	photos := c.photosWhere(`conv_id = ?`, id)
	tx, err := c.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{`DELETE FROM messages WHERE conv_id = ?`, `DELETE FROM reads WHERE conv_id = ?`,
		`DELETE FROM conversation_members WHERE conv_id = ?`, `DELETE FROM conversations WHERE id = ?`} {
		if _, err := tx.Exec(q, id); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	c.removePhotos(photos)
	return nil
}

// ---- messages

// Message finds one message by id.
func (c *ChatStore) Message(id int64) (*Message, error) {
	var m Message
	err := c.db.QueryRow(`SELECT id, conv_id, sender_id, body, photo, created_at FROM messages WHERE id = ?`, id).
		Scan(&m.ID, &m.Conv, &m.SenderID, &m.Text, &m.Photo, &m.Time)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &m, err
}

// DeleteMessage removes a message and its photo.
func (c *ChatStore) DeleteMessage(id int64) error {
	photos := c.photosWhere(`id = ?`, id)
	if _, err := c.db.Exec(`DELETE FROM messages WHERE id = ?`, id); err != nil {
		return err
	}
	c.removePhotos(photos)
	return nil
}

func (c *ChatStore) photosWhere(where string, arg any) []string {
	rows, err := c.db.Query(`SELECT photo FROM messages WHERE photo != '' AND `+where, arg)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var p string
		rows.Scan(&p)
		out = append(out, p)
	}
	return out
}

func (c *ChatStore) removePhotos(names []string) {
	for _, n := range names {
		if p, ok := c.PhotoPath(n); ok {
			os.Remove(p)
		}
	}
}

func (c *ChatStore) Post(conv, sender, text, photo string) (*Message, error) {
	return c.PostFull(&Message{Conv: conv, SenderID: sender, Text: text, Photo: photo})
}

// PostFull posts a message with any of its extras (a reply, a voice message).
func (c *ChatStore) PostFull(m *Message) (*Message, error) {
	m.Time = time.Now().UnixMilli()
	if m.ReplyTo > 0 {
		// Only a message in the same conversation can be replied to.
		var conv string
		if c.db.QueryRow(`SELECT conv_id FROM messages WHERE id = ?`, m.ReplyTo).Scan(&conv); conv != m.Conv {
			m.ReplyTo = 0
		}
	}
	res, err := c.db.Exec(`INSERT INTO messages (conv_id, sender_id, body, photo, created_at, reply_to, audio, audio_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		m.Conv, m.SenderID, m.Text, m.Photo, m.Time, m.ReplyTo, m.Audio, m.AudioMs)
	if err != nil {
		return nil, err
	}
	m.ID, _ = res.LastInsertId()
	// Sending a message means you've read the conversation up to it.
	c.MarkRead(m.Conv, m.SenderID, m.ID)
	c.fillExtras([]*Message{m})
	return m, nil
}

// fillExtras adds reply glimpses and reactions to messages.
func (c *ChatStore) fillExtras(ms []*Message) {
	for _, m := range ms {
		if m.ReplyTo > 0 {
			var body, photo, audio string
			if c.db.QueryRow(`SELECT sender_id, body, photo, audio FROM messages WHERE id = ?`, m.ReplyTo).Scan(&m.ReplySender, &body, &photo, &audio) == nil {
				switch {
				case body != "":
					if r := []rune(body); len(r) > 80 {
						body = string(r[:80]) + "…"
					}
					m.ReplyText = body
				case photo != "":
					m.ReplyText = "Photo"
				case audio != "":
					m.ReplyText = "Voice message"
				}
			} else {
				m.ReplyText = "A deleted message"
			}
		}
		rows, err := c.db.Query(`SELECT member_id, kind FROM reactions WHERE message_id = ? ORDER BY rowid`, m.ID)
		if err != nil {
			continue
		}
		for rows.Next() {
			var who, kind string
			if rows.Scan(&who, &kind) == nil {
				if m.Reactions == nil {
					m.Reactions = map[string][]string{}
				}
				m.Reactions[kind] = append(m.Reactions[kind], who)
			}
		}
		rows.Close()
	}
}

// React sets (or with kind "", removes) someone's reaction to a message.
func (c *ChatStore) React(conv string, id int64, member, kind string) error {
	var got string
	if err := c.db.QueryRow(`SELECT conv_id FROM messages WHERE id = ?`, id).Scan(&got); err != nil || got != conv {
		return ErrNotFound
	}
	if kind == "" {
		_, err := c.db.Exec(`DELETE FROM reactions WHERE message_id = ? AND member_id = ?`, id, member)
		return err
	}
	_, err := c.db.Exec(`INSERT INTO reactions (message_id, member_id, kind) VALUES (?, ?, ?) ON CONFLICT (message_id, member_id) DO UPDATE SET kind = excluded.kind`, id, member, kind)
	return err
}

// Search finds messages containing text in the given conversations, newest first.
func (c *ChatStore) Search(convs []string, q string, limit int) ([]Message, error) {
	if len(convs) == 0 || strings.TrimSpace(q) == "" {
		return []Message{}, nil
	}
	args := []any{"%" + strings.NewReplacer(`\`, `\\`, "%", `\%`, "_", `\_`).Replace(q) + "%"}
	marks := make([]string, len(convs))
	for i, id := range convs {
		marks[i] = "?"
		args = append(args, id)
	}
	args = append(args, limit)
	rows, err := c.db.Query(`SELECT id, conv_id, sender_id, body, photo, created_at FROM messages WHERE body LIKE ? ESCAPE '\' AND conv_id IN (`+strings.Join(marks, ",")+`) ORDER BY id DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.Conv, &m.SenderID, &m.Text, &m.Photo, &m.Time); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// Messages returns up to limit messages, oldest first: the newest ones, or those
// before `before` (scrolling back), or those after `after` (catching up).
func (c *ChatStore) Messages(conv string, before, after int64, limit int) ([]Message, error) {
	if limit <= 0 || limit > 200 {
		limit = messagesPerGet
	}
	var rows *sql.Rows
	var err error
	switch {
	case after > 0:
		rows, err = c.db.Query(`SELECT id, conv_id, sender_id, body, photo, created_at, reply_to, audio, audio_ms FROM messages WHERE conv_id = ? AND id > ? ORDER BY id ASC LIMIT ?`, conv, after, limit)
	case before > 0:
		rows, err = c.db.Query(`SELECT id, conv_id, sender_id, body, photo, created_at, reply_to, audio, audio_ms FROM messages WHERE conv_id = ? AND id < ? ORDER BY id DESC LIMIT ?`, conv, before, limit)
	default:
		rows, err = c.db.Query(`SELECT id, conv_id, sender_id, body, photo, created_at, reply_to, audio, audio_ms FROM messages WHERE conv_id = ? ORDER BY id DESC LIMIT ?`, conv, limit)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.Conv, &m.SenderID, &m.Text, &m.Photo, &m.Time, &m.ReplyTo, &m.Audio, &m.AudioMs); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	if after == 0 {
		slices.Reverse(out)
	}
	ptrs := make([]*Message, len(out))
	for i := range out {
		ptrs[i] = &out[i]
	}
	c.fillExtras(ptrs)
	return out, nil
}

func (c *ChatStore) Last(conv string) (*Message, error) {
	ms, err := c.Messages(conv, 0, 0, 1)
	if err != nil || len(ms) == 0 {
		return nil, err
	}
	return &ms[0], nil
}

// MarkRead records that someone has read a conversation up to a message (never backwards).
func (c *ChatStore) MarkRead(conv, member string, upTo int64) error {
	_, err := c.db.Exec(`INSERT INTO reads (conv_id, member_id, last_read) VALUES (?, ?, ?)
ON CONFLICT (conv_id, member_id) DO UPDATE SET last_read = max(last_read, excluded.last_read)`, conv, member, upTo)
	return err
}

// Reads is how far each person has read a conversation.
func (c *ChatStore) Reads(conv string) (map[string]int64, error) {
	rows, err := c.db.Query(`SELECT member_id, last_read FROM reads WHERE conv_id = ?`, conv)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{}
	for rows.Next() {
		var m string
		var id int64
		rows.Scan(&m, &id)
		out[m] = id
	}
	return out, rows.Err()
}

func (c *ChatStore) Unread(conv, member string) (int, error) {
	var n int
	err := c.db.QueryRow(`SELECT count(*) FROM messages WHERE conv_id = ? AND sender_id != ?
AND id > coalesce((SELECT last_read FROM reads WHERE conv_id = ? AND member_id = ?), 0)`, conv, member, conv, member).Scan(&n)
	return n, err
}

// PhotoConversation finds which conversation a photo belongs to (for access checks).
func (c *ChatStore) PhotoConversation(name string) (string, error) {
	var conv string
	err := c.db.QueryRow(`SELECT conv_id FROM messages WHERE photo = ? OR audio = ? LIMIT 1`, name, name).Scan(&conv)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	return conv, err
}

// SavePhoto shrinks a picture to at most maxPhotoSide pixels and saves it as JPEG.
// Re-encoding also drops any location and camera details the photo carried.
// SaveAudio keeps a voice message (AAC in MP4/M4A, Ogg or WAV), at most maxAudioBytes.
func (c *ChatStore) SaveAudio(data []byte) (string, error) {
	ext := ""
	switch {
	case len(data) > 12 && string(data[4:8]) == "ftyp":
		ext = ".m4a"
	case len(data) > 4 && string(data[:4]) == "OggS":
		ext = ".ogg"
	case len(data) > 12 && string(data[:4]) == "RIFF" && string(data[8:12]) == "WAVE":
		ext = ".wav"
	default:
		return "", fmt.Errorf("not a recording we can play")
	}
	name := randomString(16) + ext
	if err := os.WriteFile(filepath.Join(c.mediaDir, name), data, 0o600); err != nil {
		return "", err
	}
	return name, nil
}

func (c *ChatStore) SavePhoto(data []byte) (string, error) {
	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return "", fmt.Errorf("not a picture we can read")
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w > maxPhotoSide || h > maxPhotoSide {
		if w >= h {
			h, w = h*maxPhotoSide/w, maxPhotoSide
		} else {
			w, h = w*maxPhotoSide/h, maxPhotoSide
		}
		dst := image.NewRGBA(image.Rect(0, 0, max(1, w), max(1, h)))
		draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Src, nil)
		img = dst
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 82}); err != nil {
		return "", err
	}
	name := randomString(16) + ".jpg"
	if err := os.WriteFile(filepath.Join(c.mediaDir, name), buf.Bytes(), 0o600); err != nil {
		return "", err
	}
	return name, nil
}

func (c *ChatStore) PhotoPath(name string) (string, bool) {
	if name == "" || strings.ContainsAny(name, `/\`) || strings.HasPrefix(name, ".") {
		return "", false
	}
	return filepath.Join(c.mediaDir, name), true
}

// cleanMessage trims a message and checks its length.
func cleanMessage(t string) (string, bool) {
	t = strings.TrimSpace(t)
	return t, utf8.RuneCountInString(t) <= maxMessageLen
}
