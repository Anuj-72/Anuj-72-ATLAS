package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// G-tool-parity#1: what a shell command wrote, changed or removed never
// reached the ledger, so a broken module written with a heredoc, or the
// removal of a user's file, ended completed.

// shellWorld is a workspace with the files a user had, a session that
// started over it, and run_command executing on this host.
func shellWorld(t *testing.T, userFiles map[string]string) (*AgentContext, string) {
	t.Helper()
	ctx, dir := sepCtx(t, nil)
	for name, body := range userFiles {
		p := filepath.Join(dir, name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	srv := fakeSyntaxSandbox(t, "]]")
	t.Cleanup(srv.Close)
	ctx.SandboxURL = srv.URL
	ctx.VerifyOnHost = true
	ctx.TrustMode = trustFullyTrusted
	ctx.InitialWorkspace = snapshotWorkspace(dir) // as runAgentLoop takes it
	return ctx, dir
}

func shell(t *testing.T, ctx *AgentContext, command string) {
	t.Helper()
	timeout := 20
	args, _ := json.Marshal(RunCommandInput{Command: command, Timeout: &timeout})
	if res := executeToolCall("run_command", args, ctx); res == nil || !res.Success {
		t.Fatalf("%q did not run: %+v", command, res)
	}
}

func TestAModuleTheShellWroteIsJudged(t *testing.T) {
	ctx, dir := shellWorld(t, nil)
	sepWrite(t, ctx, dir, "main.py", "import tool\nprint(tool.VALUES)\n")

	shell(t, ctx, "cat > tool.py <<'EOF'\nVALUES = [1, 2]]\nEOF")
	if ok, why := terminalCompletionAllowed(ctx, nil); ok {
		t.Fatalf("a broken module the shell wrote completed: %s", why)
	}

	shell(t, ctx, "printf 'VALUES = [1, 2]\\n' > tool.py")
	if ok, why := terminalCompletionAllowed(ctx, nil); !ok {
		t.Fatalf("the fixed module does not complete: %s", why)
	}
}

func TestAFileTheShellChangedIsRechecked(t *testing.T) {
	ctx, _ := shellWorld(t, map[string]string{"util.py": "X = 1\n"})
	shell(t, ctx, "printf 'Y = [1]]\\n' >> util.py")
	if ok, why := terminalCompletionAllowed(ctx, nil); ok {
		t.Fatalf("a user's file the shell broke was not judged: %s", why)
	}
}

func TestRemovingAUsersFileWithTheShellIsAnUnapprovedDeletion(t *testing.T) {
	ctx, dir := shellWorld(t, map[string]string{"util.py": "X = 1\n"})
	sepWrite(t, ctx, dir, "main.py", "print(1)\n")
	shell(t, ctx, "rm util.py")
	if ok, why := terminalCompletionAllowed(ctx, nil); ok || why != "delete_intent_unestablished" {
		t.Fatalf("terminal = (%v, %s), want the deletion refused", ok, why)
	}
}

func TestWhatTheRunMadeAndInstallsDoNotBlock(t *testing.T) {
	ctx, dir := shellWorld(t, map[string]string{"notes.csv": "a,b\n"})
	sepWrite(t, ctx, dir, "main.py", "print(1)\n")
	shell(t, ctx, "mkdir -p build node_modules/x && echo obj > build/out.o && echo 'module.exports=1' > node_modules/x/i.js && "+
		"echo 'print(2)' > scratch.py && echo log > run.log && printf 'c,d\\n' >> notes.csv")
	if !ledgerTracks(ctx, "scratch.py") {
		t.Fatal("a module the shell wrote did not become the session's")
	}
	// Removed by a later command: the run made it, so the run may remove it.
	shell(t, ctx, "rm scratch.py")
	if ok, why := terminalCompletionAllowed(ctx, nil); !ok {
		t.Fatalf("installs, build output, a log and a file the run made and removed blocked completion: %s", why)
	}
	for _, name := range []string{"build/out.o", "node_modules/x/i.js", "run.log", "notes.csv", "scratch.py"} {
		if ledgerTracks(ctx, name) {
			t.Errorf("%s entered the ledger as a deliverable", name)
		}
	}
}

// A walk that hit its cap cannot vouch for what the shell did: the run may
// finish, and says so.
func TestAnUnobservedShellEffectIsACaveat(t *testing.T) {
	ctx, dir := sepCtx(t, nil)
	withSyntaxSandbox(t, ctx)
	sepWrite(t, ctx, dir, "app.py", "print(1)\n")
	full := snapshotWorkspace(dir)
	partial := full
	partial.truncated = true
	applyShellChanges(ctx, full, partial)
	if !ctx.ShellEffectsUnobserved {
		t.Fatal("a truncated walk was taken as a complete one")
	}
	st := &runState{madeProductiveChange: true, productiveChanges: 1, toolsRun: 1}
	st.observeVerification(ctx, "", 1, "python3 app.py", ranClean("1\n"))
	if gate, _ := st.exitGates(ctx, "Write app.py.", "Finished."); gate != "" {
		t.Fatalf("bounced by %s", gate)
	}
	status, reason := finalizeCompletion(ctx, st, "Write app.py.", "")
	summary := honestTerminalSummary(ctx, st, status, reason, "Finished.")
	if status != TerminalCompleted || !strings.Contains(summary, "too large to observe") {
		t.Fatalf("terminal = %s/%s summary %q", status, reason, summary)
	}
}
