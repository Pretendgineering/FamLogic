package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// wav16k makes a silent 16 kHz mono 16-bit recording of the given length.
func wav16k(seconds float64) []byte {
	n := int(seconds * 16000)
	var b bytes.Buffer
	b.WriteString("RIFF")
	binary.Write(&b, binary.LittleEndian, uint32(36+2*n))
	b.WriteString("WAVEfmt ")
	binary.Write(&b, binary.LittleEndian, uint32(16))
	binary.Write(&b, binary.LittleEndian, uint16(1))
	binary.Write(&b, binary.LittleEndian, uint16(1))
	binary.Write(&b, binary.LittleEndian, uint32(16000))
	binary.Write(&b, binary.LittleEndian, uint32(32000))
	binary.Write(&b, binary.LittleEndian, uint16(2))
	binary.Write(&b, binary.LittleEndian, uint16(16))
	b.WriteString("data")
	binary.Write(&b, binary.LittleEndian, uint32(2*n))
	b.Write(make([]byte, 2*n))
	return b.Bytes()
}

func TestWavAndTranscripts(t *testing.T) {
	if s, ok := wavSeconds(wav16k(2.5)); !ok || s != 2.5 {
		t.Fatalf("wav: %v %v", s, ok)
	}
	bad := wav16k(1)
	binary.LittleEndian.PutUint32(bad[24:], 44100)
	if _, ok := wavSeconds(bad); ok {
		t.Fatal("44.1 kHz accepted")
	}
	if got := cleanTranscript(" [BLANK_AUDIO] (wind blowing) Hi mom, I fed the cat. "); got != "Hi mom, I fed the cat." {
		t.Fatalf("clean: %q", got)
	}
	if cleanTranscript("[BLANK_AUDIO] . ") != "" {
		t.Fatal("silence should be empty")
	}
	if audioCtx(2) != "512" || audioCtx(9) != "640" || audioCtx(40) != "0" {
		t.Fatalf("audio ctx: %s %s %s", audioCtx(2), audioCtx(9), audioCtx(40))
	}
}

// fakeVoice puts stand-in whisper and reading-aloud programs in place.
func fakeVoice(t *testing.T) string {
	dir := t.TempDir()
	for _, d := range []string{"whisper", "tts", "models/kokoro"} {
		os.MkdirAll(filepath.Join(dir, d), 0o755)
	}
	os.WriteFile(filepath.Join(dir, "models", "whisper.bin"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "models", "kokoro", "model.onnx"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "whisper", "whisper-cli"), []byte("#!/bin/sh\necho ' [BLANK_AUDIO] Can you pick me up?'\n"), 0o755)
	// Like the real helper: "ready", then one "sid TAB out TAB text" per line, answering "ok".
	os.WriteFile(filepath.Join(dir, "tts", "famlogic-tts"), []byte("#!/bin/sh\necho ready 54\nwhile IFS='\t' read -r sid speed out text; do printf 'RIFF%s' \"$sid\" > \"$out\"; echo ok; done\n"), 0o755)
	return dir
}

func TestVoiceEndpoints(t *testing.T) {
	dir := os.Getenv("FAMLOGIC_VOICE_DIR") // a real install, to try the real thing
	if dir == "" {
		dir = fakeVoice(t)
	}
	t.Setenv("VOICE_DIR", dir)
	f := newChatFamily(t)
	e := f.e
	if _, info := e.do("GET", "/api/info", "", nil); !containsAny(info["features"], "voice-stt") || !containsAny(info["features"], "voice-tts") {
		t.Fatalf("features: %v", info["features"])
	}
	post := func(path, token, ctype string, body []byte) (int, []byte) {
		req, _ := http.NewRequest("POST", e.srv.URL+path, bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", ctype)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, b
	}
	rec := wav16k(1.5)
	if real := os.Getenv("FAMLOGIC_VOICE_SAMPLE"); real != "" {
		rec, _ = os.ReadFile(real)
	}
	st, b := post("/api/voice/transcribe", f.kid, "audio/wav", rec)
	if st != 200 {
		t.Fatalf("transcribe: %d %s", st, b)
	}
	t.Logf("heard: %s", b)
	if os.Getenv("FAMLOGIC_VOICE_DIR") == "" && !bytes.Contains(b, []byte(`"Can you pick me up?"`)) {
		t.Fatalf("transcript: %s", b)
	}
	if st, _ := post("/api/voice/transcribe", f.kid, "audio/wav", []byte("not audio")); st != 400 {
		t.Fatalf("junk audio: %d", st)
	}
	st, b = post("/api/voice/speak", f.kid, "application/json", []byte(`{"text":"Dinner is ready"}`))
	if st != 200 || !bytes.HasPrefix(b, []byte("RIFF")) {
		t.Fatalf("speak: %d %q", st, b[:min(len(b), 20)])
	}
	if real := os.Getenv("FAMLOGIC_VOICE_DIR"); real != "" {
		os.WriteFile(filepath.Join(t.TempDir(), "spoken.wav"), b, 0o644)
	} else if string(b) != "RIFF3" {
		t.Fatalf("default voice: %q", b)
	}
	// A male voice, and the helper stays running between requests.
	st, b = post("/api/voice/speak", f.kid, "application/json", []byte(`{"text":"I'll pick you up","voice":"male"}`))
	if st != 200 || (os.Getenv("FAMLOGIC_VOICE_DIR") == "" && string(b) != "RIFF16") {
		t.Fatalf("male voice: %d %q", st, b[:min(len(b), 20)])
	}
	if st, _ := post("/api/voice/speak", f.kid, "application/json", []byte(`{"text":"😂😂👍"}`)); st != 422 {
		t.Fatalf("emoji only: %d", st)
	}
	// A parent can switch talking off for a child.
	e.do("PUT", "/api/members/"+f.kidID+"/kid", f.mom, map[string]any{"features": map[string]bool{"voice": false}})
	if st, _ := post("/api/voice/transcribe", f.kid, "audio/wav", rec); st != 403 {
		t.Fatalf("voice off: %d", st)
	}
}

func TestSpeakable(t *testing.T) {
	for in, want := range map[string]string{
		"Hellooooooo mom!!!! 😂😂":          "Hello mom!",
		"Dinner is ready 🍕🍕":              "Dinner is ready",
		"😂👍❤️":                            "",
		"look https://example.com/x cool": "look a link cool",
		"hahahaha that's funny":           "ha ha that's funny",
		"asdfghjklqwertyuiop ok":          "ok",
		"Can we gooooo to the park??":     "Can we go to the park?",
		"see you at 3:30":                 "see you at 3:30",
		"👨‍👩‍👧 family time":               "family time",
	} {
		if got := speakable(in); got != want {
			t.Errorf("speakable(%q) = %q, want %q", in, got, want)
		}
	}
}

func containsAny(list any, want string) bool {
	l, _ := list.([]any)
	for _, v := range l {
		if v == want {
			return true
		}
	}
	return false
}

func TestMemberVoice(t *testing.T) {
	f := newChatFamily(t)
	if st, _ := f.e.status("PATCH", "/api/me", f.mom, map[string]any{"voice": "female"}); st != 200 {
		t.Fatalf("set voice: %d", st)
	}
	if st, _ := f.e.status("PATCH", "/api/me", f.mom, map[string]any{"voice": "robot"}); st != 400 {
		t.Fatalf("bad voice: %d", st)
	}
	_, res := f.e.do("GET", "/api/members", f.kid, nil)
	found := false
	for _, m := range res["members"].([]any) {
		if mm := m.(map[string]any); mm["voice"] == "female" {
			found = true
		}
	}
	if !found {
		t.Fatal("voice not shared with the family")
	}
}
