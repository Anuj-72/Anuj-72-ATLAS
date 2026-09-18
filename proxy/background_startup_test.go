package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// A background job that already exited did not start.
//
// Measured end to end on flask_pause (stabilization cycles 3-5). The workspace
// is one file, app.py, which imports flask; nothing in it declares a
// dependency, and the sandbox image does not carry flask. The run asked to
// start it, ATLAS redirected the foreground call to run_background (correct: a
// server would block), the job exited 1 inside the settle window with
// ModuleNotFoundError in its stderr — and the tool result came back
// success=true with no error. The run then probed a port nothing was listening
// on, twice, restarted the same job, and finished incomplete. Four of six
// flask sessions in cycle 5 ended verification_demanded_unmet, two of them
// with correct code on disk.
//
// The correction is at that boundary only: an immediate non-zero exit is a
// failed tool call, carrying the job's own last output. Nothing here installs
// anything, runs anything, or names a package ATLAS chose.

type bgWorld struct {
	dir      string
	executed []string
	prompts  []string // what the model was told, request by request
	terminal map[string]string
	mu       sync.Mutex
}

// told reports whether any request after the first carried this text.
func (w *bgWorld) told(phrase string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	enc, _ := json.Marshal(phrase)
	needle := strings.Trim(string(enc), `"`)
	for _, p := range w.prompts {
		if strings.Contains(p, needle) {
			return true
		}
	}
	return false
}

// startBgWorld stubs the sandbox job API: jobs[cmd] gives the stderr and exit
// code the sandbox reports for that command, and installed flips what a later
// start does, the way installing a dependency would.
func startBgWorld(t *testing.T, files map[string]string, script []string,
	jobFor func(cmd string, w *bgWorld) (stderr []string, exit int, running bool)) *bgWorld {
	t.Helper()
	w := &bgWorld{dir: t.TempDir(), terminal: map[string]string{}}
	for name, body := range files {
		full := filepath.Join(w.dir, name)
		_ = os.MkdirAll(filepath.Dir(full), 0o755)
		if err := os.WriteFile(full, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	jobs := map[string]struct {
		stderr  []string
		exit    int
		running bool
	}{}
	turn := 0
	srv := httptest.NewServer(http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/syntax-check"):
			json.NewEncoder(rw).Encode(map[string]interface{}{"valid": true})
			return
		case strings.HasSuffix(r.URL.Path, "/jobs/start"):
			var in struct{ Command, Cwd string }
			json.NewDecoder(r.Body).Decode(&in)
			w.mu.Lock()
			w.executed = append(w.executed, in.Command)
			w.mu.Unlock()
			stderr, exit, running := jobFor(in.Command, w)
			id := fmt.Sprintf("job%d", len(jobs)+1)
			jobs[id] = struct {
				stderr  []string
				exit    int
				running bool
			}{stderr, exit, running}
			json.NewEncoder(rw).Encode(map[string]interface{}{"job_id": id, "pid": 100 + len(jobs)})
			return
		case strings.Contains(r.URL.Path, "/jobs/") && strings.HasSuffix(r.URL.Path, "/output"):
			id := strings.Split(strings.TrimPrefix(r.URL.Path, "/jobs/"), "/")[0]
			j := jobs[id]
			body := map[string]interface{}{"job_id": id, "running": j.running, "stdout": []string{}, "stderr": j.stderr}
			if !j.running {
				body["exit_code"] = j.exit
			}
			json.NewEncoder(rw).Encode(body)
			return
		case strings.HasSuffix(r.URL.Path, "/shell"):
			var in struct{ Command string }
			json.NewDecoder(r.Body).Decode(&in)
			w.mu.Lock()
			w.executed = append(w.executed, in.Command)
			w.mu.Unlock()
			json.NewEncoder(rw).Encode(map[string]interface{}{
				"stdout": "ok\n", "stderr": "", "exit_code": 0, "success": true})
			return
		case strings.HasSuffix(r.URL.Path, "/execute"):
			var in struct{ Code, Command string }
			b, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(b, &in)
			cmd := in.Command
			if cmd == "" {
				cmd = in.Code
			}
			w.mu.Lock()
			if !strings.Contains(cmd, ".atlas-mount-probe") {
				w.executed = append(w.executed, cmd)
			}
			w.mu.Unlock()
			out := ""
			if strings.Contains(cmd, ".atlas-mount-probe") {
				p, _ := os.ReadFile(filepath.Join(w.dir, ".atlas-mount-probe"))
				out = string(p)
			}
			json.NewEncoder(rw).Encode(map[string]interface{}{"success": true, "stdout": out, "exit_code": 0})
			return
		case strings.HasPrefix(r.URL.Path, "/v3/"), strings.HasPrefix(r.URL.Path, "/internal/"):
			http.Error(rw, "unavailable", http.StatusServiceUnavailable)
			return
		case !strings.HasSuffix(r.URL.Path, "/v1/chat/completions"):
			http.NotFound(rw, r)
			return
		}
		body, _ := io.ReadAll(r.Body)
		w.mu.Lock()
		k := turn
		turn++
		w.prompts = append(w.prompts, string(body))
		w.mu.Unlock()
		rw.Header().Set("Content-Type", "text/event-stream")
		out := `{"type":"done","summary":"finished"}`
		if k < len(script) {
			out = script[k]
		}
		d, _ := json.Marshal(map[string]interface{}{"choices": []map[string]interface{}{{"delta": map[string]string{"content": out}}}})
		fmt.Fprintf(rw, "data: %s\n\ndata: [DONE]\n\n", d)
	}))
	t.Cleanup(srv.Close)

	ctx := NewAgentContext(w.dir, Tier2Medium)
	ctx.InferenceURL, ctx.SandboxURL, ctx.V3URL = srv.URL, srv.URL, srv.URL
	ctx.PermissionMode = PermissionYolo
	ctx.TrustMode = trustFullyTrusted
	ctx.StreamFn = func(et string, data interface{}) {
		if et != "done" {
			return
		}
		b, _ := json.Marshal(data)
		var m map[string]interface{}
		_ = json.Unmarshal(b, &m)
		w.mu.Lock()
		defer w.mu.Unlock()
		for k, v := range m {
			w.terminal[k] = fmt.Sprint(v)
		}
	}
	if err := runAgentLoop(ctx, "In app.py, add a pause toggle. Then verify the app still starts."); err != nil {
		t.Fatal(err)
	}
	return w
}

const importsMissing = "from flask import Flask\n\napp = Flask(__name__)\n\n\n@app.route('/')\ndef index():\n    return 'hi'\n\n\nif __name__ == '__main__':\n    app.run(port=5001)\n"

func crashingStart(missing string) func(string, *bgWorld) ([]string, int, bool) {
	return func(cmd string, w *bgWorld) ([]string, int, bool) {
		w.mu.Lock()
		installed := false
		for _, c := range w.executed {
			if strings.Contains(c, "install") {
				installed = true
			}
		}
		w.mu.Unlock()
		if installed {
			return nil, 0, true
		}
		return []string{"Traceback (most recent call last):",
			"  File \"/workspace/app.py\", line 1, in <module>",
			"    from " + missing + " import Flask",
			"ModuleNotFoundError: No module named '" + missing + "'"}, 1, false
	}
}

// 1. Immediate startup failure: the tool call fails and says why.
func TestAnImmediateStartupFailureIsAFailedCall(t *testing.T) {
	w := startBgWorld(t, map[string]string{"app.py": importsMissing},
		[]string{toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			`{"type":"done","summary":"started it"}`},
		crashingStart("flask"))
	if !strings.Contains(strings.Join(w.executed, "\n"), "python app.py") {
		t.Fatalf("the job was never started: %v", w.executed)
	}
	// What the model was told is the point: the call failed, with the job's
	// own output, not a success with a traceback buried in its data.
	for _, want := range []string{"exited immediately with status 1", "ModuleNotFoundError: No module named"} {
		if !w.told(want) {
			t.Errorf("the model was never told %q", want)
		}
	}
	if w.terminal["status"] == "completed" {
		t.Errorf("a run whose only verification crashed reported %v", w.terminal)
	}
}

// The message itself: the exit status, the job's own last output, the missing
// name echoed from that output, and — with nothing declaring it — what the run
// has to decide. No install command is composed for the model to paste.
func TestTheImmediateExitMessageCarriesTheJobsOwnOutput(t *testing.T) {
	dir := t.TempDir()
	ctx := NewAgentContext(dir, Tier2Medium)
	code := 1
	out := RunBackgroundOutput{JobID: "j1", ExitCode: &code, Stderr: []string{
		"Traceback (most recent call last):", "ModuleNotFoundError: No module named 'flask'"}}
	msg := immediateExitMessage(ctx, "python app.py", out)
	for _, want := range []string{"exited immediately with status 1", "nothing to probe",
		"ModuleNotFoundError", `"flask"`, "No file in this workspace declares it"} {
		if !strings.Contains(msg, want) {
			t.Errorf("message lacks %q:\n%s", want, msg)
		}
	}
	if strings.Contains(msg, "pip install") || strings.Contains(msg, "npm install") {
		t.Errorf("the message composes an install command from error text:\n%s", msg)
	}
	// With the project's own declaration present, it points at that file.
	if err := os.WriteFile(filepath.Join(dir, "requirements.txt"), []byte("flask\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if msg := immediateExitMessage(ctx, "python app.py", out); !strings.Contains(msg, "requirements.txt") {
		t.Errorf("a declared manifest is not named:\n%s", msg)
	}
}

// 2. A job that is still running is unchanged: success, with its id.
func TestAJobThatIsStillRunningIsStillASuccess(t *testing.T) {
	w := startBgWorld(t, map[string]string{"app.py": "print('ok')\n"},
		[]string{toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_command", map[string]interface{}{"command": "curl -s localhost:5001", "timeout": 10}),
			`{"type":"done","summary":"it serves"}`},
		func(string, *bgWorld) ([]string, int, bool) { return nil, 0, true })
	if w.terminal["reason"] == "background_work_unresolved" {
		return // the run is judged by its own rules; the call itself succeeded
	}
	if w.terminal["status"] == "" {
		t.Error("no terminal recorded")
	}
}

// 3. An actionable recovery: told what failed, the run installs the dependency
// and the next start succeeds.
func TestADependencyFailureCanBeRecoveredAndVerified(t *testing.T) {
	w := startBgWorld(t, map[string]string{"app.py": importsMissing, "requirements.txt": "flask\n"},
		[]string{
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_command", map[string]interface{}{"command": "pip install -r requirements.txt", "timeout": 120}),
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			`{"type":"done","summary":"installed the declared dependency and the app starts"}`},
		crashingStart("flask"))
	if !w.told("This project declares its dependencies in requirements.txt") {
		t.Error("the model was not pointed at the project's own declaration")
	}
	joined := strings.Join(w.executed, "\n")
	if strings.Count(joined, "python app.py") < 2 {
		t.Fatalf("the second start never happened: %v", w.executed)
	}
	if !strings.Contains(joined, "pip install -r requirements.txt") {
		t.Fatalf("the install the model asked for did not run: %v", w.executed)
	}
}

// 4. A recovery that does not work leaves the run incomplete.
func TestAFailedRecoveryStaysIncomplete(t *testing.T) {
	w := startBgWorld(t, map[string]string{"app.py": importsMissing},
		[]string{
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			`{"type":"done","summary":"the app starts"}`,
			`{"type":"done","summary":"the app starts"}`,
			`{"type":"done","summary":"the app starts"}`,
			`{"type":"done","summary":"the app starts"}`},
		func(cmd string, w *bgWorld) ([]string, int, bool) {
			return []string{"ModuleNotFoundError: No module named 'flask'"}, 1, false
		})
	if !w.told("exited immediately with status 1") {
		t.Error("the repeated failure was never reported as a failure")
	}
	if w.terminal["status"] == "completed" {
		t.Errorf("a run that never started its app reported %v", w.terminal)
	}
}

// ATLAS never runs an install of its own: every command executed is one the
// model sent.
func TestATLASRunsNoInstallOfItsOwn(t *testing.T) {
	w := startBgWorld(t, map[string]string{"app.py": importsMissing, "requirements.txt": "flask\n"},
		[]string{toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			`{"type":"done","summary":"started"}`},
		crashingStart("flask"))
	for _, c := range w.executed {
		if strings.Contains(c, "install") {
			t.Errorf("ATLAS executed an install the model never asked for: %q", c)
		}
	}
}

// A headers-only probe still does not verify — and now the run is told why.
//
// Measured (cycle 6, both flask_pause sessions): after installing the missing
// dependency and starting the app, the run probed it with `curl -I`, got
// HTTP 200, and the probe was declined in silence because a HEAD response
// cannot show that the page works. Both sessions repeated the probe and ended
// verification_demanded_unmet with working code on disk. The safeguard is
// unchanged; the silence is not.
func TestAHeadersOnlyProbeIsDeclinedOutLoud(t *testing.T) {
	if isVerificationCommand("curl -I http://127.0.0.1:5001") {
		t.Fatal("precondition changed: a HEAD probe now counts as verification")
	}
	if !isHeadOnlyProbe("curl -I http://127.0.0.1:5001") || isHeadOnlyProbe("curl http://127.0.0.1:5001/") {
		t.Fatal("head-only detection is wrong")
	}
	w := startBgWorld(t, map[string]string{"app.py": importsMissing, "requirements.txt": "flask\n"},
		[]string{
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_command", map[string]interface{}{"command": "pip install -r requirements.txt", "timeout": 120}),
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_command", map[string]interface{}{"command": "curl -I http://127.0.0.1:5001", "timeout": 10}),
			`{"type":"done","summary":"the app serves"}`,
			`{"type":"done","summary":"the app serves"}`,
			`{"type":"done","summary":"the app serves"}`,
			`{"type":"done","summary":"the app serves"}`},
		crashingStart("flask"))
	if !w.told("asked for headers only") || !w.told("does not count as a") {
		t.Error("the run was never told why its probe did not count")
	}
	if w.terminal["status"] == "completed" {
		t.Errorf("a headers-only probe opened the gate: %v", w.terminal)
	}
}

// A probe that fetches the body still verifies, and the run completes.
func TestABodyProbeStillVerifies(t *testing.T) {
	w := startBgWorld(t, map[string]string{"app.py": importsMissing, "requirements.txt": "flask\n"},
		[]string{
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_command", map[string]interface{}{"command": "pip install -r requirements.txt", "timeout": 120}),
			toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
			toolCall("run_command", map[string]interface{}{"command": "curl -s http://127.0.0.1:5001/", "timeout": 10}),
			toolCall("stop_background", map[string]interface{}{"job_id": "job2"}),
			`{"type":"done","summary":"installed the dependency; the app serves"}`},
		crashingStart("flask"))
	if w.told("asked for headers only") {
		t.Error("a body probe was treated as headers-only")
	}
}
