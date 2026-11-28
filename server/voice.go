package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

// Voice: speech to text (whisper.cpp) and text to speech (Kokoro, through sherpa-onnx), both baked into
// the container under /voice and run on demand on this server, so nothing leaves
// the house. Apps send short recordings (16 kHz mono WAV) and get text back, and
// send text to hear it read aloud. Phones whose own speech services work well may
// use those instead; this is the fallback that always works.
//
// Layout under VOICE_DIR (default /voice):
//   whisper/whisper-cli (plus its libraries), models/whisper.bin
//   tts/famlogic-tts (plus its libraries), models/kokoro/ (model.onnx, voices.bin,
//   tokens.txt, lexicon-us-en.txt, espeak-ng-data/)
//
// Reading aloud keeps the voice model loaded in a helper process (tts/famlogic-tts.c)
// between messages, and stops it after a while with nothing to say.

const (
	maxVoiceBytes = 2 << 20 // ~60 s of 16 kHz mono 16-bit audio
	maxSpeakRunes = 600
	voiceTimeout  = 90 * time.Second
)

type Voice struct {
	whisper, whisperModel string
	tts                   *speaker
	tmp                   string
	slot                  chan struct{} // one job at a time: these use every core
}

// openVoice finds the voice tools. VOICE=off switches voice off.
func openVoice(dir, tmp string) *Voice {
	if strings.EqualFold(os.Getenv("VOICE"), "off") {
		return &Voice{}
	}
	v := &Voice{tmp: tmp, slot: make(chan struct{}, 1)}
	exists := func(p string) bool { st, err := os.Stat(p); return err == nil && !st.IsDir() }
	if w, m := filepath.Join(dir, "whisper", "whisper-cli"), filepath.Join(dir, "models", "whisper.bin"); exists(w) && exists(m) {
		v.whisper, v.whisperModel = w, m
	}
	if p, m := filepath.Join(dir, "tts", "famlogic-tts"), filepath.Join(dir, "models", "kokoro"); exists(p) && exists(filepath.Join(m, "model.onnx")) {
		v.tts = &speaker{bin: p, models: m}
	}
	if v.whisper != "" || v.tts != nil {
		log.Printf("voice: speech to text %v, read aloud %v", v.whisper != "", v.tts != nil)
	}
	os.MkdirAll(tmp, 0o700)
	return v
}

func (v *Voice) canListen() bool { return v != nil && v.whisper != "" }
func (v *Voice) canSpeak() bool  { return v != nil && v.tts != nil }

func (v *Voice) features() []string {
	var f []string
	if v.canListen() {
		f = append(f, "voice-stt")
	}
	if v.canSpeak() {
		f = append(f, "voice-tts")
	}
	return f
}

var errBusy = errors.New("busy")

// take waits for the voice slot (a few seconds at most).
func (v *Voice) take(ctx context.Context) (func(), error) {
	select {
	case v.slot <- struct{}{}:
		return func() { <-v.slot }, nil
	case <-ctx.Done():
		return nil, errBusy
	case <-time.After(45 * time.Second):
		return nil, errBusy
	}
}

func threads() string { return strconv.Itoa(min(runtime.NumCPU(), 4)) }

// wavSeconds checks a recording is 16 kHz, mono, 16-bit PCM WAV and says how long it is.
func wavSeconds(b []byte) (float64, bool) {
	if len(b) < 44 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WAVE" {
		return 0, false
	}
	pos := 12
	var fmtOK bool
	for pos+8 <= len(b) {
		id := string(b[pos : pos+4])
		size := int(binary.LittleEndian.Uint32(b[pos+4 : pos+8]))
		body := pos + 8
		switch id {
		case "fmt ":
			if body+16 > len(b) {
				return 0, false
			}
			format := binary.LittleEndian.Uint16(b[body:])
			channels := binary.LittleEndian.Uint16(b[body+2:])
			rate := binary.LittleEndian.Uint32(b[body+4:])
			bits := binary.LittleEndian.Uint16(b[body+14:])
			fmtOK = format == 1 && channels == 1 && rate == 16000 && bits == 16
		case "data":
			if !fmtOK {
				return 0, false
			}
			if size > len(b)-body || size == 0 || size == 0xFFFFFFFF {
				size = len(b) - body // streamed recordings may not know their length
			}
			return float64(size) / 32000, true
		}
		pos = body + size + size%2
	}
	return 0, false
}

// whisper marks silence and noises like "[BLANK_AUDIO]" or "(wind blowing)".
var noiseTag = regexp.MustCompile(`\[[^\]]*\]|\([^)]*\)|\*[^*]*\*`)

func cleanTranscript(s string) string {
	s = noiseTag.ReplaceAllString(s, " ")
	s = strings.Join(strings.Fields(s), " ")
	if strings.Trim(s, ".!?, -") == "" {
		return ""
	}
	return s
}

// audioCtx sizes whisper's listening window to the clip (50 per second, at least
// ~10 s: shorter windows make it repeat itself): short clips come back much faster.
func audioCtx(seconds float64) string {
	ac := int((seconds + 3) * 50)
	if ac < 512 {
		ac = 512
	}
	ac = (ac + 63) / 64 * 64
	if ac >= 1500 {
		return "0" // the full 30 s window
	}
	return strconv.Itoa(ac)
}

// Transcribe turns a recording into text ("" if nothing was understood).
func (v *Voice) Transcribe(ctx context.Context, wav []byte) (string, error) {
	secs, ok := wavSeconds(wav)
	if !ok {
		return "", errBadAudio
	}
	release, err := v.take(ctx)
	if err != nil {
		return "", err
	}
	defer release()
	f, err := os.CreateTemp(v.tmp, "rec-*.wav")
	if err != nil {
		return "", err
	}
	defer os.Remove(f.Name())
	if _, err := f.Write(wav); err != nil {
		f.Close()
		return "", err
	}
	f.Close()
	ctx, cancel := context.WithTimeout(ctx, voiceTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, v.whisper, "-m", v.whisperModel, "-f", f.Name(), "-nt", "-np", "-l", "en",
		"-t", threads(), "-ac", audioCtx(secs))
	cmd.Dir = filepath.Dir(v.whisper)
	var out, errOut bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errOut
	if err := cmd.Run(); err != nil {
		log.Printf("voice: whisper: %v: %s", err, lastLine(errOut.String()))
		return "", err
	}
	return cleanTranscript(out.String()), nil
}

var errBadAudio = errors.New("send 16 kHz mono 16-bit WAV audio")

// The voices people can choose (Settings → You, or a parent for a child): Kokoro's
// "Heart" and "Michael", two of its warmest English voices.
var voiceSpeakers = map[string]int{"": 3, "female": 3, "male": 16}

// Reading speeds: everyone hears a calm pace; young children a little slower still
// (unhurried, not sleepy).
const (
	normalSpeed = 0.9
	slowSpeed   = 0.8
)

func validVoice(v string) bool { _, ok := voiceSpeakers[v]; return ok }

// Speak reads text aloud in a voice ("female", "male" or "" for the default): WAV audio.
func (v *Voice) Speak(ctx context.Context, text, voice string, slow bool) ([]byte, error) {
	release, err := v.take(ctx)
	if err != nil {
		return nil, err
	}
	defer release()
	f, err := os.CreateTemp(v.tmp, "say-*.wav")
	if err != nil {
		return nil, err
	}
	f.Close()
	defer os.Remove(f.Name())
	ctx, cancel := context.WithTimeout(ctx, voiceTimeout)
	defer cancel()
	speed := normalSpeed
	if slow {
		speed = slowSpeed
	}
	if err := v.tts.say(ctx, voiceSpeakers[voice], speed, text, f.Name()); err != nil {
		log.Printf("voice: reading aloud: %v", err)
		return nil, err
	}
	return os.ReadFile(f.Name())
}

// speaker is the reading-aloud helper: started on first use, kept running (the
// model stays loaded, ~300 MB) until [speakerIdle] passes with nothing to say.
type speaker struct {
	bin, models string

	mu   sync.Mutex
	cmd  *exec.Cmd
	in   io.WriteCloser
	out  *bufio.Reader
	idle *time.Timer
}

const speakerIdle = 10 * time.Minute

func (sp *speaker) start() error {
	cmd := exec.Command(sp.bin, sp.models, threads())
	cmd.Dir = filepath.Dir(sp.bin)
	in, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	sp.cmd, sp.in, sp.out = cmd, in, bufio.NewReader(out)
	line, err := sp.readLine(60 * time.Second)
	if err != nil || !strings.HasPrefix(line, "ready") {
		sp.stop()
		return fmt.Errorf("voice model didn't load: %v %q", err, line)
	}
	return nil
}

// stop ends the helper (callers hold mu).
func (sp *speaker) stop() {
	if sp.cmd == nil {
		return
	}
	sp.in.Close()
	sp.cmd.Process.Kill()
	sp.cmd.Wait()
	sp.cmd, sp.in, sp.out = nil, nil, nil
}

func (sp *speaker) readLine(timeout time.Duration) (string, error) {
	type res struct {
		s   string
		err error
	}
	ch := make(chan res, 1)
	r := sp.out
	go func() { s, err := r.ReadString('\n'); ch <- res{strings.TrimSpace(s), err} }()
	select {
	case x := <-ch:
		return x.s, x.err
	case <-time.After(timeout):
		return "", errors.New("timed out")
	}
}

func (sp *speaker) say(ctx context.Context, sid int, speed float64, text, out string) error {
	sp.mu.Lock()
	defer sp.mu.Unlock()
	if sp.idle != nil {
		sp.idle.Stop()
	}
	defer func() {
		sp.idle = time.AfterFunc(speakerIdle, func() { sp.mu.Lock(); sp.stop(); sp.mu.Unlock() })
	}()
	if sp.cmd == nil {
		if err := sp.start(); err != nil {
			return err
		}
	}
	// One request per line; tabs and line breaks in the text would break that.
	text = strings.Join(strings.Fields(text), " ")
	if _, err := fmt.Fprintf(sp.in, "%d\t%.2f\t%s\t%s\n", sid, speed, out, text); err != nil {
		sp.stop()
		return err
	}
	wait := voiceTimeout
	if d, ok := ctx.Deadline(); ok {
		wait = time.Until(d)
	}
	line, err := sp.readLine(wait)
	if err != nil {
		sp.stop() // stuck or gone: start afresh next time
		return err
	}
	if line != "ok" {
		return errors.New(line)
	}
	return nil
}

// ---- making text good to listen to

var (
	urlRe   = regexp.MustCompile(`https?://\S+|www\.\S+`)
	punctRe = regexp.MustCompile(`([!?.,])[!?.,]+`)
	laughRe = regexp.MustCompile(`(?i)\b(?:ha){3,}h?\b|\b(?:he){3,}\b|\blo+l\b`)
)

// speakable turns a chat message into something good to hear: no emoji (they'd be
// read as their names), "hellooooo" said as "hello", "!!!" as "!", links as "a
// link", and keyboard mashes left out. "" when nothing is left worth saying.
func speakable(text string) string {
	text = urlRe.ReplaceAllString(text, " a link ")
	var b strings.Builder
	for _, r := range text {
		if isEmojiRune(r) {
			b.WriteRune(' ')
			continue
		}
		b.WriteRune(r)
	}
	text = b.String()
	text = laughRe.ReplaceAllStringFunc(text, func(string) string { return "ha ha" })
	text = unstretch(text)
	text = punctRe.ReplaceAllString(text, "$1")
	words := strings.Fields(text)
	keep := words[:0]
	for _, w := range words {
		letters := strings.TrimFunc(w, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) })
		if utf8.RuneCountInString(letters) > 18 || (utf8.RuneCountInString(letters) > 6 && !hasVowel(letters)) {
			continue // "asdfghjklqwerty": a keyboard mash, not a word
		}
		keep = append(keep, w)
	}
	text = strings.Join(keep, " ")
	if strings.IndexFunc(text, func(r rune) bool { return unicode.IsLetter(r) || unicode.IsDigit(r) }) < 0 {
		return ""
	}
	return text
}

// unstretch says "hellooooo" as "hello": a letter three or more times in a row counts once.
func unstretch(s string) string {
	rs := []rune(s)
	var b strings.Builder
	for i := 0; i < len(rs); {
		j := i
		for j < len(rs) && rs[j] == rs[i] {
			j++
		}
		n := j - i
		if n >= 3 && unicode.IsLetter(rs[i]) {
			n = 1
		}
		for k := 0; k < n; k++ {
			b.WriteRune(rs[i])
		}
		i = j
	}
	return b.String()
}

func hasVowel(s string) bool { return strings.ContainsAny(strings.ToLower(s), "aeiouy") }

// isEmojiRune: pictographs, symbols, flags, skin tones and the joiners between them.
func isEmojiRune(r rune) bool {
	switch {
	case r == 0x200D || r == 0xFE0F || r == 0xFE0E || r == 0x20E3:
		return true
	case r >= 0x1F000 && r <= 0x1FAFF, r >= 0x2600 && r <= 0x27BF, r >= 0x2300 && r <= 0x23FF,
		r >= 0x2B00 && r <= 0x2BFF, r >= 0x1F1E6 && r <= 0x1F1FF, r >= 0xE0020 && r <= 0xE007F:
		return true
	}
	return unicode.Is(unicode.So, r)
}

func lastLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.LastIndexByte(s, '\n'); i >= 0 {
		s = s[i+1:]
	}
	return s
}

// ---- HTTP

func (s *Server) handleTranscribe(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.voice.canListen() {
		writeError(w, http.StatusServiceUnavailable, "speech to text isn't available on this server")
		return
	}
	if !me.can("voice") {
		writeError(w, http.StatusForbidden, "a parent has switched talking off for you")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxVoiceBytes))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "that recording is too long (a minute at most)")
		return
	}
	text, err := s.voice.Transcribe(r.Context(), body)
	switch {
	case errors.Is(err, errBadAudio):
		writeError(w, http.StatusBadRequest, errBadAudio.Error())
	case errors.Is(err, errBusy):
		writeError(w, http.StatusServiceUnavailable, "the server is busy; try again in a moment")
	case err != nil:
		writeError(w, http.StatusInternalServerError, "couldn't understand that recording")
	default:
		writeJSON(w, http.StatusOK, map[string]string{"text": text})
	}
}

func (s *Server) handleSpeak(w http.ResponseWriter, r *http.Request, me *Member) {
	if !s.voice.canSpeak() {
		writeError(w, http.StatusServiceUnavailable, "reading aloud isn't available on this server")
		return
	}
	var body struct {
		Text  string `json:"text"`
		Voice string `json:"voice"` // "female", "male" or "" (the default)
		Slow  bool   `json:"slow"`  // a little slower, for young children
	}
	if !readJSON(w, r, &body) {
		return
	}
	if utf8.RuneCountInString(body.Text) > maxSpeakRunes {
		writeError(w, http.StatusBadRequest, "send 1 to 600 characters to read aloud")
		return
	}
	text := speakable(body.Text)
	if text == "" {
		writeError(w, http.StatusUnprocessableEntity, "nothing to read aloud there")
		return
	}
	if !validVoice(body.Voice) {
		body.Voice = ""
	}
	// Younger children always hear it a little slower.
	wav, err := s.voice.Speak(r.Context(), text, body.Voice, body.Slow || me.access() == AccessYoungerChild)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "couldn't read that aloud right now")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.Write(wav)
}
