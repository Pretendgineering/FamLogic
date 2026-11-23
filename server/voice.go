package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
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
	"time"
	"unicode/utf8"
)

// Voice: speech to text (whisper.cpp) and text to speech (Piper), both baked into
// the container under /voice and run on demand on this server, so nothing leaves
// the house. Apps send short recordings (16 kHz mono WAV) and get text back, and
// send text to hear it read aloud. Phones whose own speech services work well may
// use those instead; this is the fallback that always works.
//
// Layout under VOICE_DIR (default /voice):
//   whisper/whisper-cli (plus its libraries), models/whisper.bin
//   piper/piper (plus its libraries and espeak-ng-data), models/voice.onnx(.json)

const (
	maxVoiceBytes = 2 << 20 // ~60 s of 16 kHz mono 16-bit audio
	maxSpeakRunes = 600
	voiceTimeout  = 90 * time.Second
)

type Voice struct {
	whisper, whisperModel string
	piper, piperModel     string
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
	if p, m := filepath.Join(dir, "piper", "piper"), filepath.Join(dir, "models", "voice.onnx"); exists(p) && exists(m) {
		v.piper, v.piperModel = p, m
	}
	if v.whisper != "" || v.piper != "" {
		log.Printf("voice: speech to text %v, read aloud %v", v.whisper != "", v.piper != "")
	}
	os.MkdirAll(tmp, 0o700)
	return v
}

func (v *Voice) canListen() bool { return v != nil && v.whisper != "" }
func (v *Voice) canSpeak() bool  { return v != nil && v.piper != "" }

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

// Speak reads text aloud: WAV audio.
func (v *Voice) Speak(ctx context.Context, text string) ([]byte, error) {
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
	cmd := exec.CommandContext(ctx, v.piper, "--model", v.piperModel, "--output_file", f.Name())
	cmd.Dir = filepath.Dir(v.piper)
	cmd.Stdin = strings.NewReader(text + "\n")
	var errOut bytes.Buffer
	cmd.Stderr = &errOut
	if err := cmd.Run(); err != nil {
		log.Printf("voice: piper: %v: %s", err, lastLine(errOut.String()))
		return nil, err
	}
	return os.ReadFile(f.Name())
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
		Text string `json:"text"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	text := strings.Join(strings.Fields(body.Text), " ")
	if text == "" || utf8.RuneCountInString(text) > maxSpeakRunes {
		writeError(w, http.StatusBadRequest, "send 1 to 600 characters to read aloud")
		return
	}
	wav, err := s.voice.Speak(r.Context(), text)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "couldn't read that aloud right now")
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.Write(wav)
}
