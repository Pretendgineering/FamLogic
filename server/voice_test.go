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

// fakeVoice puts stand-in whisper and piper programs in place.
func fakeVoice(t *testing.T) string {
	dir := t.TempDir()
	for _, d := range []string{"whisper", "piper", "models"} {
		os.MkdirAll(filepath.Join(dir, d), 0o755)
	}
	os.WriteFile(filepath.Join(dir, "models", "whisper.bin"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "models", "voice.onnx"), []byte("x"), 0o644)
	os.WriteFile(filepath.Join(dir, "whisper", "whisper-cli"), []byte("#!/bin/sh\necho ' [BLANK_AUDIO] Can you pick me up?'\n"), 0o755)
	os.WriteFile(filepath.Join(dir, "piper", "piper"), []byte("#!/bin/sh\nwhile [ \"$1\" != --output_file ]; do shift; done\ncat > /dev/null\nprintf 'RIFFfake' > \"$2\"\n"), 0o755)
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
	// A parent can switch talking off for a child.
	e.do("PUT", "/api/members/"+f.kidID+"/kid", f.mom, map[string]any{"features": map[string]bool{"voice": false}})
	if st, _ := post("/api/voice/transcribe", f.kid, "audio/wav", rec); st != 403 {
		t.Fatalf("voice off: %d", st)
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
