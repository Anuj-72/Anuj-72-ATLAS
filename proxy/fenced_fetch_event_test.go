package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// Each fenced attempt is recorded in a fenced_fetch event (#254): what the
// wire showed and what became of the attempt. In the smoke runs on 4403ae8
// and fc8321d, 34 of 84 sessions lost the fenced channel the same way: the
// first attempt streamed the file and could not close, the raw retry sent no
// content, and that silent retry turned the channel off. Nothing in the event
// stream showed it.

type fencedEvents struct {
	mu  sync.Mutex
	evs []map[string]interface{}
}

func (f *fencedEvents) record(et string, data interface{}) {
	if et != "fenced_fetch" {
		return
	}
	b, _ := json.Marshal(data)
	var m map[string]interface{}
	json.Unmarshal(b, &m)
	f.mu.Lock()
	defer f.mu.Unlock()
	f.evs = append(f.evs, m)
}

func (f *fencedEvents) all() []map[string]interface{} {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]map[string]interface{}{}, f.evs...)
}

func fencedEventCtx(t *testing.T, url string) (*AgentContext, *fencedEvents) {
	t.Helper()
	ctx := NewAgentContext(t.TempDir(), Tier2Medium)
	ctx.InferenceURL = url
	ctx.Ctx = context.Background()
	fe := &fencedEvents{}
	ctx.StreamFn = fe.record
	return ctx, fe
}

const fencedEventCall = `{"type":"tool_call","name":"write_file","args":{"path":"app.py","content":"@fenced"}}`

// isChatCompletion reports a model request. Every other path, the prompt
// progress poller's /slots among them, must answer at once: the call waits
// for the poller when it returns.
func isChatCompletion(r *http.Request) bool {
	return r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/chat/completions")
}

// serveReasoningOnly streams reasoning_content frames and no content until
// the client gives up.
func serveReasoningOnly(w http.ResponseWriter, r *http.Request, stop <-chan struct{}) {
	w.Header().Set("Content-Type", "text/event-stream")
	fl, _ := w.(http.Flusher)
	for {
		select {
		case <-r.Context().Done():
			return
		case <-stop:
			return
		default:
		}
		d, _ := json.Marshal(map[string]interface{}{
			"choices": []map[string]interface{}{{"delta": map[string]string{"reasoning_content": "thinking "}}},
		})
		fmt.Fprintf(w, "data: %s\n\n", d)
		if fl != nil {
			fl.Flush()
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestAFencedAttemptThatIsUsedIsRecorded(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		serveFencedBlock(w, "````python\nprint(1)\n````")
	}))
	defer srv.Close()
	ctx, fe := fencedEventCtx(t, srv.URL)
	if _, err := fetchFencedContent(ctx, fencedEventCall, "app.py"); err != nil {
		t.Fatal(err)
	}
	evs := fe.all()
	if len(evs) != 1 {
		t.Fatalf("%d fenced_fetch events, want 1: %v", len(evs), evs)
	}
	e := evs[0]
	if e["outcome"] != "used" || e["grammar"] != "fence" || e["attempt"] != float64(1) || e["cut"] != nil {
		t.Errorf("event = %v, want attempt 1, fence, used, no cut", e)
	}
	if n, _ := e["content_chars"].(float64); n == 0 {
		t.Errorf("no content recorded: %v", e)
	}
}

// The smoke-run shape: the fence attempt streams the file and cannot close,
// and the raw retry reasons without sending content.
func TestASilentRetryIsRecordedWithWhatTheWireShowed(t *testing.T) {
	t.Setenv("ATLAS_FENCED_FIRST_CONTENT_SEC", "5")
	t.Setenv("ATLAS_FENCED_IDLE_SEC", "1")
	t.Setenv("ATLAS_FENCED_STALL_SEC", "1")
	stop := make(chan struct{})
	defer close(stop)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !isChatCompletion(r) {
			http.NotFound(w, r)
			return
		}
		var body map[string]interface{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if _, withGrammar := body["grammar"]; withGrammar {
			// The file, a closer the grammar did not end on, and the model's
			// next call on the same line: nothing the fetch can salvage.
			serveStuckStream(w, r, stop, "````python\n", "print(1)\n", "```{\"type\":\"done\"}\n")
			return
		}
		serveReasoningOnly(w, r, stop)
	}))
	defer srv.Close()
	ctx, fe := fencedEventCtx(t, srv.URL)
	if _, err := fetchFencedContent(ctx, fencedEventCall, "app.py"); err == nil {
		t.Fatal("the fetch succeeded")
	}
	evs := fe.all()
	if len(evs) != 2 {
		t.Fatalf("%d fenced_fetch events, want 2: %v", len(evs), evs)
	}
	first, retry := evs[0], evs[1]
	if n, _ := first["content_chars"].(float64); first["grammar"] != "fence" ||
		first["outcome"] != "unusable" || first["cut"] != "idle" || n == 0 {
		t.Errorf("attempt 1 = %v, want fence, unusable, cut idle, with content", first)
	}
	content, _ := retry["content_chars"].(float64)
	reasoning, _ := retry["reasoning_chars"].(float64)
	if retry["grammar"] != "raw" || retry["outcome"] != "stalled" || retry["cut"] != "stalled" ||
		content != 0 || reasoning == 0 {
		t.Errorf("attempt 2 = %v, want raw, stalled, cut stalled, no content, some reasoning", retry)
	}
	if ctx.FencedStalls != 1 {
		t.Errorf("FencedStalls = %d, want 1", ctx.FencedStalls)
	}
}

// A server that never answers is cut by the first-content watchdog, and the
// event says no frame came.
func TestAFetchWithNoResponseIsRecorded(t *testing.T) {
	t.Setenv("ATLAS_FENCED_FIRST_CONTENT_SEC", "1")
	stop := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !isChatCompletion(r) {
			http.NotFound(w, r)
			return
		}
		// The server sees a client leave only once the body is read.
		io.Copy(io.Discard, r.Body)
		select {
		case <-r.Context().Done():
		case <-stop:
		}
	}))
	defer srv.Close()
	defer close(stop)
	ctx, fe := fencedEventCtx(t, srv.URL)
	fetchFencedContent(ctx, fencedEventCall, "app.py")
	evs := fe.all()
	if len(evs) != 1 {
		t.Fatalf("%d events, want 1 (the stall turns the channel off): %v", len(evs), evs)
	}
	if e := evs[0]; e["outcome"] != "stalled" || e["cut"] != "first_content" || e["wire_lines"] != float64(0) {
		t.Errorf("event = %v, want stalled, cut first_content, no wire lines", e)
	}
}

// The refusal that turns the channel off says where the stall was: this
// file's own fetch, or an earlier one. It said "earlier in this run" for
// both.
func TestTheChannelOffRefusalSaysWhereTheStallWas(t *testing.T) {
	t.Setenv("ATLAS_FENCED_FIRST_CONTENT_SEC", "5")
	t.Setenv("ATLAS_FENCED_STALL_SEC", "1")
	stop := make(chan struct{})
	defer close(stop)
	srv := fencedLoopStub(t, stop)
	defer srv.Close()
	ctx := NewAgentContext(t.TempDir(), Tier2Medium)
	ctx.InferenceURL, ctx.SandboxURL, ctx.V3URL = srv.URL, srv.URL, srv.URL
	ctx.PermissionMode = PermissionYolo
	ctx.MaxTurns = 2
	ctx.Ctx = context.Background()
	var mu sync.Mutex
	var errs []string
	ctx.StreamFn = func(et string, data interface{}) {
		m, ok := data.(map[string]interface{})
		if et != "tool_result" || !ok || m["tool"] != "write_file" {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		e, _ := m["error"].(string)
		errs = append(errs, e)
	}
	runAgentLoop(ctx, "Create solve.py.")
	mu.Lock()
	defer mu.Unlock()
	if len(errs) < 2 {
		t.Fatalf("%d write results, want 2: %q", len(errs), errs)
	}
	if !strings.Contains(errs[0], "stalled on this file just now") {
		t.Errorf("the stall in this call is not named as this call's: %q", errs[0])
	}
	if !strings.Contains(errs[1], "stalled earlier in this run") {
		t.Errorf("a later write does not say the stall was earlier: %q", errs[1])
	}
}

// A request that fails before any response (here, a refused connection) has
// no stream of its own: its event must not carry the stats of the call
// before it.
func TestAFailedRequestCarriesNoStaleStats(t *testing.T) {
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close()
	ctx, fe := fencedEventCtx(t, url)
	ctx.LastFencedStream = fencedStreamStats{WireLines: 7, ReasoningChars: 90, Cut: "idle"}
	fetchFencedContent(ctx, fencedEventCall, "app.py")
	evs := fe.all()
	if len(evs) == 0 {
		t.Fatal("no fenced_fetch event")
	}
	if e := evs[0]; e["wire_lines"] != float64(0) || e["reasoning_chars"] != float64(0) || e["cut"] != nil {
		t.Errorf("event = %v, carries the previous call's stream", e)
	}
}
