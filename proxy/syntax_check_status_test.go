package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// A checker the sandbox stopped before a verdict (a wall-clock or memory
// ceiling, or it never started) is not a pass and not a syntax error, and it
// is not the sandbox being down either: that flag feeds producer-unavailable
// reasons elsewhere (audit S-sandbox/INTEGRITY#1).
func TestAStoppedSyntaxCheckerIsNotRun(t *testing.T) {
	replies := map[string]map[string]interface{}{
		"stopped": {"valid": false, "status": "not_run", "outcome": "timed_out",
			"errors": []string{"syntax verification unavailable: the checker ended timed_out"}},
		"broken": {"valid": false, "status": "checked", "outcome": "completed",
			"errors": []string{"SyntaxError: invalid syntax (line 2)"}},
		"clean": {"valid": true, "status": "checked", "outcome": "completed", "errors": []string{}},
		// A sandbox that predates the field answers as before.
		"legacy": {"valid": true, "errors": []string{}},
	}
	for name, reply := range replies {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_ = json.NewEncoder(w).Encode(reply)
		}))
		ctx := NewAgentContext(t.TempDir(), Tier2Medium)
		ctx.SandboxURL = srv.URL
		got := sandboxSyntaxOutcome(ctx, "app.py", "x = 1\n")
		srv.Close()
		switch name {
		case "stopped":
			if got.Status != ValidationNotRun || got.ProducerUnavailable {
				t.Errorf("stopped checker: %+v, want not_run without producer-unavailable", got)
			}
		case "broken":
			if got.Status != ValidationFailed {
				t.Errorf("syntax error: %+v, want failed", got)
			}
		default:
			if got.Status != ValidationPassed {
				t.Errorf("%s: %+v, want passed", name, got)
			}
		}
	}
}

// P-gates/INTEGRITY#1: completion read only the whole-file parse, so a Flask
// app whose embedded <script> the harness had just found broken was
// "demonstrated", and a clean run of the server then overwrote that verdict.
func TestABrokenEmbeddedScriptIsNotDemonstrated(t *testing.T) {
	app := flaskWithScript(strayParenLine)
	for _, reachable := range []bool{true, false} {
		dir := t.TempDir()
		if err := os.WriteFile(filepath.Join(dir, "app.py"), []byte(app), 0o644); err != nil {
			t.Fatal(err)
		}
		sbx := fakeSyntaxSandbox(t, "")
		v3 := fakeV3Embedded(t, "'DOWN');", nil)
		ctx := NewAgentContext(dir, Tier2Medium)
		ctx.SandboxURL = sbx.URL
		ctx.V3URL = v3.URL
		if !reachable {
			ctx.V3URL = "http://127.0.0.1:9"
		}
		ok, why := terminalCompletionAllowed(ctx, []string{"app.py"})
		sbx.Close()
		v3.Close()
		if reachable && ok {
			t.Fatalf("a demonstrated embedded-script failure completed: %s", why)
		}
		// Fail-soft: an embedded check that could not run is no finding.
		if !reachable && !ok {
			t.Fatalf("an unreachable embedded check blocked completion: %s", why)
		}
	}
}

func TestACleanRunDoesNotSettleAnEmbeddedScriptFailure(t *testing.T) {
	dir := t.TempDir()
	app := flaskWithScript(strayParenLine)
	if err := os.WriteFile(filepath.Join(dir, "app.py"), []byte(app), 0o644); err != nil {
		t.Fatal(err)
	}
	ctx := NewAgentContext(dir, Tier2Medium)
	key := ledgerKey(ctx, "app.py")
	observeDeliverable(ctx, key, []byte(app), ValidationKindSyntax, ValidationFailed,
		embeddedScriptErrPrefix+"app.py line 7: unexpected `)`")
	st := &runState{mutationDebt: map[string]*mutationDebtEntry{key: {Rel: "app.py", Kind: debtContent}}}

	settleDebtByExecution(ctx, st, "python3 app.py", true)

	if len(st.mutationDebt) != 1 {
		t.Fatal("a clean server run settled the debt of a broken embedded script")
	}
	if d := ctx.Ledger[key]; d.ValidationKind == ValidationKindExecution {
		t.Fatal("the embedded-script failure was overwritten with execution/passed")
	}
}
