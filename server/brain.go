package main

// Steward's built-in brain: a small open model that runs right here, inside
// FamLogic, so a family needs nothing else (no cloud account, no second computer).
//
// The image carries llama.cpp's llama-server (/brain/llama-server, built for every
// x86-64 and ARM64 CPU, like voice's whisper.cpp). An admin picks a model in the
// console, after a quick look at this computer (the spec check): FamLogic downloads
// it into DATA_DIR/models (checking it against Hugging Face's checksum), starts
// llama-server on 127.0.0.1 with it, and points Steward at it. If it stops, it's
// started again. A graphics card is for a model server on another computer (the
// console shows how), or a llama.cpp CUDA container beside this one.

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// brainModel is one model the console offers.
type brainModel struct {
	ID      string  `json:"id"`
	Name    string  `json:"name"`
	Repo    string  `json:"repo"` // on Hugging Face
	File    string  `json:"file"`
	SizeGB  float64 `json:"sizeGB"` // the download
	RAMGB   float64 `json:"ramGB"`  // memory it needs while running (model and working space)
	Params  float64 `json:"params"` // billions: how much work each word takes
	Licence string  `json:"licence"`
	Good    string  `json:"good"` // what it's good for, in a line
	Tag     string  `json:"tag"`  // Ollama's name for it, for model servers
}

// The catalogue: small open models that can use tools, Q4_K_M (a good size for quality).
var brainModels = []brainModel{
	{"qwen3.5-0.8b", "Qwen3.5 0.8B", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf", 0.53, 1.2, 0.8, "Apache 2.0",
		"Tiny and quick. Simple one-step requests only (\"add milk\"); fine for children's Steward.", "qwen3.5:0.8b"},
	{"lfm3-1.7b", "Liquid LFM3 1.7B", "LiquidAI/LFM3-1.7B-GGUF", "LFM3-1.7B-Q4_K_M.gguf", 1.10, 2.0, 1.7, "LFM Open License (free below $10M revenue a year)",
		"Small, made for tool use. Good for everyday one- and two-step requests.", "lfm3:1.7b"},
	{"qwen3.5-2b", "Qwen3.5 2B", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf", 1.28, 2.2, 2, "Apache 2.0",
		"Small. Everyday requests; shaky on long rambles.", "qwen3.5:2b"},
	{"phi-4-mini", "Phi-4 mini 3.8B", "unsloth/Phi-4-mini-instruct-GGUF", "Phi-4-mini-instruct-Q4_K_M.gguf", 2.49, 4.0, 3.8, "MIT",
		"Mid-size. Handles most of what Steward does.", "phi4-mini"},
	{"qwen3.5-4b", "Qwen3.5 4B", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf", 2.74, 4.2, 4, "Apache 2.0",
		"Mid-size, the best all-rounder for a home server. Handles most of what Steward does.", "qwen3.5:4b"},
	{"gemma-4-e4b", "Gemma 4 E4B", "unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", 4.98, 6.5, 4, "Apache 2.0",
		"Mid-size, careful writer. Needs more memory than its speed suggests.", "gemma4:e4b"},
	{"qwen3.5-9b", "Qwen3.5 9B", "unsloth/Qwen3.5-9B-GGUF", "Qwen3.5-9B-Q4_K_M.gguf", 5.68, 8.0, 9, "Apache 2.0",
		"Large. Close to cloud quality; best with a graphics card.", "qwen3.5:9b"},
}

func brainModelByID(id string) *brainModel {
	for i := range brainModels {
		if brainModels[i].ID == id {
			return &brainModels[i]
		}
	}
	return nil
}

// ---- the spec check

// machineSpec is what FamLogic can see of the computer it runs on.
type machineSpec struct {
	Arch       string  `json:"arch"`
	CPU        string  `json:"cpu"`
	Cores      int     `json:"cores"`
	AVX2       bool    `json:"avx2"`
	AVX512     bool    `json:"avx512"`
	MemGB      float64 `json:"memGB"`      // total (or the container's limit)
	FreeMemGB  float64 `json:"freeMemGB"`  // available now
	DiskFreeGB float64 `json:"diskFreeGB"` // where models go
	GPU        string  `json:"gpu,omitempty"`
	Engine     bool    `json:"engine"` // the built-in engine is in this build
}

func brainBin() string { return env("BRAIN_BIN", "/brain/llama-server") }

func (s *Server) modelsDir() string { return filepath.Join(filepath.Dir(s.store.path), "models") }

func specCheck(modelsDir string) machineSpec {
	sp := machineSpec{Arch: runtime.GOARCH, Cores: runtime.NumCPU()}
	if b, err := os.ReadFile("/proc/cpuinfo"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			k, v, ok := strings.Cut(line, ":")
			if !ok {
				continue
			}
			k, v = strings.TrimSpace(k), strings.TrimSpace(v)
			switch {
			case (k == "model name" || k == "Model") && sp.CPU == "":
				sp.CPU = v
			case k == "flags" && !sp.AVX2:
				sp.AVX2 = strings.Contains(" "+v+" ", " avx2 ")
				sp.AVX512 = strings.Contains(" "+v+" ", " avx512f ")
			}
		}
	}
	if b, err := os.ReadFile("/proc/meminfo"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			f := strings.Fields(line)
			if len(f) < 2 {
				continue
			}
			kb, _ := strconv.ParseFloat(f[1], 64)
			switch f[0] {
			case "MemTotal:":
				sp.MemGB = kb / 1048576
			case "MemAvailable:":
				sp.FreeMemGB = kb / 1048576
			}
		}
	}
	// A container with a memory limit can only use that much.
	if b, err := os.ReadFile("/sys/fs/cgroup/memory.max"); err == nil {
		if n, err := strconv.ParseFloat(strings.TrimSpace(string(b)), 64); err == nil && n > 0 {
			lim := n / (1 << 30)
			if lim < sp.MemGB {
				sp.FreeMemGB = math.Min(sp.FreeMemGB, lim)
				sp.MemGB = lim
			}
		}
	}
	_ = os.MkdirAll(modelsDir, 0o755)
	var st syscall.Statfs_t
	if syscall.Statfs(modelsDir, &st) == nil {
		sp.DiskFreeGB = float64(st.Bavail) * float64(st.Bsize) / (1 << 30)
	}
	if _, err := os.Stat("/dev/nvidia0"); err == nil {
		sp.GPU = "NVIDIA graphics card"
	} else if b, err := os.ReadFile("/proc/driver/nvidia/gpus"); err == nil && len(b) > 0 {
		sp.GPU = "NVIDIA graphics card"
	} else if _, err := os.Stat("/dev/dri/renderD128"); err == nil {
		sp.GPU = "Built-in graphics"
	}
	if _, err := os.Stat(brainBin()); err == nil {
		sp.Engine = true
	}
	return sp
}

// estimate is a rough guess at how quick a model would be on this computer, before
// anyone tries it (the console's test then measures it for real).
type estimate struct {
	Fits     bool    `json:"fits"`
	Why      string  `json:"why,omitempty"` // why it doesn't fit
	ReadTPS  float64 `json:"readTPS"`       // words (tokens) a second it reads
	WriteTPS float64 `json:"writeTPS"`      // ...and writes
	TypicalS float64 `json:"typicalS"`      // a usual answer, once warmed up
	FirstS   float64 `json:"firstS"`        // the first answer after it starts
	Verdict  string  `json:"verdict"`       // quick, fine, slow, too slow
}

func estimateFor(sp machineSpec, m brainModel) estimate {
	e := estimate{Fits: true}
	if sp.MemGB > 0 && m.RAMGB > sp.MemGB*0.8 {
		e.Fits, e.Why = false, fmt.Sprintf("needs about %.1f GB of memory; this computer has %.1f GB", m.RAMGB, sp.MemGB)
	} else if sp.DiskFreeGB > 0 && m.SizeGB+0.5 > sp.DiskFreeGB {
		e.Fits, e.Why = false, fmt.Sprintf("needs %.1f GB of disk; %.1f GB free", m.SizeGB, sp.DiskFreeGB)
	}
	// Writing is limited by how fast memory can be read (the whole model per word);
	// reading the prompt by how much arithmetic the CPU does. Rough figures for each
	// kind of CPU, from published llama.cpp benchmarks (Raspberry Pi 5, N100/N150,
	// desktops).
	bandwidth, perCore := 9.0, 25.0 // GB/s usable; prompt tokens/s per core per billion parameters
	switch {
	case sp.Arch == "amd64" && sp.AVX512:
		bandwidth, perCore = 35, 70
	case sp.Arch == "amd64" && sp.AVX2 && sp.Cores >= 8:
		bandwidth, perCore = 30, 55
	case sp.Arch == "amd64" && sp.AVX2:
		bandwidth, perCore = 16, 45
	case sp.Arch == "amd64":
		bandwidth, perCore = 10, 20
	}
	cores := math.Min(float64(sp.Cores), 16)
	e.WriteTPS = bandwidth / (m.SizeGB * 1.1)
	e.ReadTPS = perCore * cores / m.Params
	// A usual answer in skills mode: about 600 new prompt tokens (the note, a skill,
	// an action's result) and 60 written (an action call and a short reply), over
	// two rounds. The first after a start also reads the system prompt (about 900).
	e.TypicalS = 600/e.ReadTPS + 60/e.WriteTPS + 0.5
	e.FirstS = e.TypicalS + 900/e.ReadTPS
	switch {
	case e.TypicalS < 6:
		e.Verdict = "quick"
	case e.TypicalS < 15:
		e.Verdict = "fine"
	case e.TypicalS < 40:
		e.Verdict = "slow"
	default:
		e.Verdict = "too slow"
	}
	return e
}

// ---- downloading and running

type brainState struct {
	mu          sync.Mutex
	downloading string // model id
	got, total  int64  // bytes
	dlErr       string
	cancel      context.CancelFunc
	cmd         *exec.Cmd
	running     string // model id
	runErr      string
	wantRunning string // what should be running (restarted if it stops)
	started     time.Time
}

var brain brainState

const brainPort = "18090"

func brainURL() string { return "http://127.0.0.1:" + brainPort + "/v1" }

func (s *Server) modelPath(m *brainModel) string { return filepath.Join(s.modelsDir(), m.File) }

// installedModels: the catalogue's models that are downloaded.
func (s *Server) installedModels() []string {
	out := []string{}
	for i := range brainModels {
		if fi, err := os.Stat(s.modelPath(&brainModels[i])); err == nil && fi.Size() > 0 {
			out = append(out, brainModels[i].ID)
		}
	}
	return out
}

// downloadModel fetches a model (resuming a partial download), then checks it.
func (s *Server) downloadModel(m *brainModel) error {
	brain.mu.Lock()
	if brain.downloading != "" {
		brain.mu.Unlock()
		return errors.New("another model is downloading")
	}
	ctx, cancel := context.WithCancel(context.Background())
	brain.downloading, brain.got, brain.total, brain.dlErr, brain.cancel = m.ID, 0, int64(m.SizeGB*1e9), "", cancel
	brain.mu.Unlock()
	go func() {
		err := s.fetchModel(ctx, m)
		brain.mu.Lock()
		brain.downloading, brain.cancel = "", nil
		if err != nil && !errors.Is(err, context.Canceled) {
			brain.dlErr = err.Error()
			log.Printf("brain: downloading %s: %v", m.ID, err)
		}
		brain.mu.Unlock()
	}()
	return nil
}

func (s *Server) fetchModel(ctx context.Context, m *brainModel) error {
	if err := os.MkdirAll(s.modelsDir(), 0o755); err != nil {
		return err
	}
	want := hfChecksum(ctx, m)
	part := s.modelPath(m) + ".part"
	f, err := os.OpenFile(part, os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()
	have, _ := f.Seek(0, io.SeekEnd)
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://huggingface.co/"+m.Repo+"/resolve/main/"+m.File, nil)
	if have > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", have))
	}
	req.Header.Set("User-Agent", "FamLogic")
	res, err := (&http.Client{}).Do(req)
	if err != nil {
		return fmt.Errorf("couldn't reach Hugging Face: %w", err)
	}
	defer res.Body.Close()
	switch res.StatusCode {
	case http.StatusOK:
		have = 0
		f.Truncate(0)
		f.Seek(0, io.SeekStart)
	case http.StatusPartialContent:
	default:
		return fmt.Errorf("Hugging Face answered %d", res.StatusCode)
	}
	brain.mu.Lock()
	brain.got = have
	if res.ContentLength > 0 {
		brain.total = have + res.ContentLength
	}
	brain.mu.Unlock()
	buf := make([]byte, 1<<20)
	for {
		n, rerr := res.Body.Read(buf)
		if n > 0 {
			if _, err := f.Write(buf[:n]); err != nil {
				return err
			}
			brain.mu.Lock()
			brain.got += int64(n)
			brain.mu.Unlock()
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}
	f.Close()
	if want != "" {
		got, err := fileSHA256(part)
		if err != nil {
			return err
		}
		if got != want {
			os.Remove(part)
			return errors.New("the download was damaged (its checksum didn't match); try again")
		}
	}
	return os.Rename(part, s.modelPath(m))
}

// hfChecksum asks Hugging Face for the file's SHA-256 ("" if it can't say).
func hfChecksum(ctx context.Context, m *brainModel) string {
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "https://huggingface.co/api/models/"+m.Repo+"/tree/main", nil)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return ""
	}
	defer res.Body.Close()
	var files []struct {
		Path string `json:"path"`
		LFS  struct {
			OID string `json:"oid"`
		} `json:"lfs"`
	}
	if json.NewDecoder(res.Body).Decode(&files) != nil {
		return ""
	}
	for _, f := range files {
		if f.Path == m.File {
			return f.LFS.OID
		}
	}
	return ""
}

func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// startBrain runs llama-server with a model (stopping whatever ran before), and
// keeps it running.
func (s *Server) startBrain(m *brainModel) error {
	if _, err := os.Stat(brainBin()); err != nil {
		return errors.New("this FamLogic doesn't have the built-in engine (use the published image, or a model server)")
	}
	if _, err := os.Stat(s.modelPath(m)); err != nil {
		return errors.New("download the model first")
	}
	s.stopBrain()
	brain.mu.Lock()
	brain.wantRunning = m.ID
	brain.mu.Unlock()
	go s.superviseBrain(m)
	return nil
}

func (s *Server) superviseBrain(m *brainModel) {
	backoff := 5 * time.Second
	for {
		brain.mu.Lock()
		if brain.wantRunning != m.ID {
			brain.mu.Unlock()
			return
		}
		threads := runtime.NumCPU()
		if threads > 4 {
			threads-- // leave a core for FamLogic itself
		}
		cmd := exec.Command(brainBin(), "-m", s.modelPath(m), "--host", "127.0.0.1", "--port", brainPort,
			"--jinja", "-c", "12288", "-np", "1", "-t", strconv.Itoa(threads), "--no-webui", "--alias", m.ID)
		cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+filepath.Dir(brainBin()))
		logf, _ := os.OpenFile(filepath.Join(s.modelsDir(), "brain.log"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
		cmd.Stdout, cmd.Stderr = logf, logf
		if err := cmd.Start(); err != nil {
			brain.runErr = "couldn't start: " + err.Error()
			brain.mu.Unlock()
			if logf != nil {
				logf.Close()
			}
			return
		}
		brain.cmd, brain.running, brain.runErr, brain.started = cmd, m.ID, "", time.Now()
		brain.mu.Unlock()
		err := cmd.Wait()
		if logf != nil {
			logf.Close()
		}
		brain.mu.Lock()
		brain.cmd, brain.running = nil, ""
		stopped := brain.wantRunning != m.ID
		if !stopped {
			brain.runErr = "it stopped (" + lastLogLine(filepath.Join(s.modelsDir(), "brain.log"), err) + "); starting it again"
		}
		ranFor := time.Since(brain.started)
		brain.mu.Unlock()
		if stopped {
			return
		}
		if ranFor > 10*time.Minute {
			backoff = 5 * time.Second
		}
		time.Sleep(backoff)
		backoff = min(backoff*2, 5*time.Minute)
	}
}

func lastLogLine(path string, err error) string {
	f, ferr := os.Open(path)
	if ferr != nil {
		return fmt.Sprint(err)
	}
	defer f.Close()
	last := ""
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if t := strings.TrimSpace(sc.Text()); t != "" {
			last = t
		}
	}
	if len(last) > 160 {
		last = last[:160]
	}
	if last == "" {
		return fmt.Sprint(err)
	}
	return last
}

func (s *Server) stopBrain() {
	brain.mu.Lock()
	brain.wantRunning = ""
	cmd := brain.cmd
	brain.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(syscall.SIGTERM)
		done := make(chan struct{})
		go func() { cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			_ = cmd.Process.Kill()
		}
	}
}

// brainReady: llama-server answers its health check (it's loaded the model).
func brainReady() bool {
	c := &http.Client{Timeout: 2 * time.Second}
	res, err := c.Get("http://127.0.0.1:" + brainPort + "/health")
	if err != nil {
		return false
	}
	res.Body.Close()
	return res.StatusCode == http.StatusOK
}

// startBrainIfChosen: at start-up, run the built-in model if Steward uses one.
func (s *Server) startBrainIfChosen() {
	c := s.store.Steward()
	if c.Source != "builtin" {
		return
	}
	if m := brainModelByID(c.Model); m != nil {
		if err := s.startBrain(m); err != nil {
			log.Printf("brain: %v", err)
		}
	}
}

// ---- HTTP (admin)

// brainStatus is the AI page's view of the built-in brain and this computer.
func (s *Server) brainStatus() map[string]any {
	sp := specCheck(s.modelsDir())
	models := []map[string]any{}
	for _, m := range brainModels {
		models = append(models, map[string]any{"model": m, "estimate": estimateFor(sp, m)})
	}
	brain.mu.Lock()
	st := map[string]any{"downloading": brain.downloading, "got": brain.got, "total": brain.total, "downloadError": brain.dlErr,
		"running": brain.running, "runError": brain.runErr}
	brain.mu.Unlock()
	st["ready"] = st["running"] != "" && brainReady()
	recentTurnsMu.Lock()
	turns := append([]turnStats(nil), recentTurns...)
	recentTurnsMu.Unlock()
	evalMu.Lock()
	var ev any
	if evalLatest != nil {
		ev = *evalLatest
	}
	evalMu.Unlock()
	return map[string]any{"spec": sp, "models": models, "installed": s.installedModels(), "brain": st, "turns": turns, "eval": ev}
}

// POST /api/admin/steward/brain {action: download|cancel|use|stop|delete, model}
func (s *Server) handleAdminBrain(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Action string `json:"action"`
		Model  string `json:"model"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	m := brainModelByID(body.Model)
	if m == nil && body.Action != "cancel" && body.Action != "stop" {
		writeError(w, http.StatusBadRequest, "unknown model")
		return
	}
	var err error
	switch body.Action {
	case "download":
		err = s.downloadModel(m)
	case "cancel":
		brain.mu.Lock()
		if brain.cancel != nil {
			brain.cancel()
		}
		brain.mu.Unlock()
	case "use":
		// Steward thinks with this model, here, from now on.
		if err = s.startBrain(m); err == nil {
			c := s.store.Steward()
			c.URL, c.Model, c.Key, c.Source, c.Enabled = brainURL(), m.ID, "", "builtin", true
			if c.Mode == "" {
				c.Mode = "skills"
			}
			err = s.store.SetSteward(c)
		}
	case "stop":
		s.stopBrain()
	case "delete":
		brain.mu.Lock()
		inUse := brain.wantRunning == m.ID
		brain.mu.Unlock()
		if inUse {
			err = errors.New("Steward is using this model; choose another first")
		} else {
			os.Remove(s.modelPath(m))
			os.Remove(s.modelPath(m) + ".part")
		}
	default:
		err = errors.New("unknown action")
	}
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, s.brainStatus())
}

// POST /api/admin/steward/eval {levels}: run the test set on Steward's model (in the
// background, on a throwaway family); the AI page shows it as it goes.
func (s *Server) handleAdminEval(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Levels []string `json:"levels"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	c := s.store.Steward()
	if c.URL == "" || c.Model == "" {
		writeError(w, http.StatusBadRequest, "choose a model first")
		return
	}
	if !evalRunning.TryLock() {
		writeError(w, http.StatusConflict, "a test is already running")
		return
	}
	if len(body.Levels) == 0 {
		body.Levels = []string{"quick"}
	}
	setEvalLatest(evalReport{Model: c.Model, Mode: c.Mode, When: time.Now().UnixMilli()})
	go func() {
		defer evalRunning.Unlock()
		rep := runEval(context.Background(), c.main(), c.Mode, body.Levels, setEvalLatest)
		setEvalLatest(rep)
	}()
	writeJSON(w, http.StatusAccepted, map[string]any{"started": true})
}

// GET /api/admin/steward/models?url=&key=: the models a model server offers (to pick one).
func (s *Server) handleAdminServerModels(w http.ResponseWriter, r *http.Request) {
	u := strings.TrimRight(strings.TrimSpace(r.URL.Query().Get("url")), "/")
	if u == "" {
		u = strings.TrimRight(s.store.Steward().URL, "/")
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, u+"/models", nil)
	if err != nil || !(strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://")) {
		writeError(w, http.StatusBadRequest, "the address starts with http:// or https://")
		return
	}
	key := r.URL.Query().Get("key")
	if key == "" && u == strings.TrimRight(s.store.Steward().URL, "/") {
		key = s.store.Steward().Key
	}
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	res, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		writeError(w, http.StatusBadGateway, "couldn't reach it: "+err.Error())
		return
	}
	defer res.Body.Close()
	var out struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if res.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(res.Body, 2<<20)).Decode(&out) != nil {
		writeError(w, http.StatusBadGateway, fmt.Sprintf("it answered %d, not a list of models", res.StatusCode))
		return
	}
	ids := []string{}
	for _, d := range out.Data {
		ids = append(ids, d.ID)
	}
	writeJSON(w, http.StatusOK, map[string]any{"models": ids})
}
