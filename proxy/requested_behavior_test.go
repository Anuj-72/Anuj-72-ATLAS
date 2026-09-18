package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// What a completion claim is allowed to mean.
//
// Measured (stabilization cycle 8, family O). The request asked for a standup
// page, entries visible together, still there tomorrow, and "i want to be able
// to look back at yesterdays". The run wrote three files, started the server,
// posted an entry and listed it, and finished
// `completed / deliverables_demonstrated` in 245 s. The clean room found no way
// to retrieve a past day at all: nothing in the session had ever exercised it,
// and `deliverables_demonstrated` only ever meant "the files this run wrote
// pass the syntax contract".
//
// A requested behaviour with no evidence now scopes the claim instead of
// certifying it — not a success, not a failure. The extraction is fallible in
// both directions by construction, so both directions are tested here.

const standupRequest = "our team does written standups and right now its a slack mess. id like a " +
	"little web thing where each person posts what they did yesterday and what theyre doing today, and " +
	"everyone can see the days entries together. it should still be there tomorrow, and i want to be " +
	"able to look back at yesterdays. keep the storage simple"

func serverApp() string {
	return "from flask import Flask\n\napp = Flask(__name__)\n\n\n@app.route('/standup', methods=['POST'])\n" +
		"def post_standup():\n    return {'status': 'ok'}\n\n\n@app.route('/standups')\ndef list_standups():\n" +
		"    return '<html>entries</html>'\n\n\nif __name__ == '__main__':\n    app.run(port=5001)\n"
}

//  1. A running app that lacks an explicitly requested behaviour does not claim
//     full completion — and the claim says what was not shown, not that it is
//     broken.
func TestARunningAppMissingARequestedBehaviourDoesNotCompleteFully(t *testing.T) {
	w := startBgWorldReq(t, standupRequest, map[string]string{}, []string{
		toolCall("write_file", map[string]interface{}{"path": "app.py", "content": serverApp()}),
		toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
		toolCall("run_command", map[string]interface{}{
			"command": "curl -s -X POST http://127.0.0.1:5001/standup", "timeout": 10}),
		toolCall("stop_background", map[string]interface{}{"job_id": "job1"}),
		`{"type":"done","summary":"The standup app is finished and working."}`,
		`{"type":"done","summary":"The standup app is finished and working."}`,
		`{"type":"done","summary":"The standup app is finished and working."}`,
		`{"type":"done","summary":"The standup app is finished and working."}`,
	}, func(string, *bgWorld) ([]string, int, bool) { return nil, 0, true },
		func(ctx *AgentContext) { workContract(t, ctx) })
	run := w

	if run.terminal["status"] == "completed" {
		t.Errorf("a run that never exercised the day lookback claimed %v", run.terminal)
	}
	if run.terminal["reason"] != "requirements_unverified" {
		t.Errorf("reason = %q, want requirements_unverified", run.terminal["reason"])
	}
	summary := run.terminal["summary"]
	if !strings.Contains(summary, "look back at yesterdays") {
		t.Errorf("the summary does not name what was not shown: %q", summary)
	}
	for _, forbidden := range []string{"does not work", "failed", "broken"} {
		if strings.Contains(strings.ToLower(summary), forbidden) {
			t.Errorf("the summary asserts failure rather than absence of evidence: %q", summary)
		}
	}
	if !run.told("nothing in this run exercised part of what was asked") {
		t.Error("the run was never asked for the missing evidence before the terminal")
	}
}

// 2. A genuinely satisfied request with adequate evidence completes.
func TestASatisfiedRequestWithEvidenceCompletes(t *testing.T) {
	solve := "def main():\n    print(900)\n\n\nif __name__ == '__main__':\n    main()\n"
	run := integrityLoopWith(t, "Write solve.py that reads input.txt and prints the total. Then run it "+
		"and confirm the answer.", map[string]string{"input.txt": "forward 5\n"}, []string{
		toolCall("write_file", map[string]interface{}{"path": "solve.py", "content": solve}),
		toolCall("run_command", map[string]interface{}{"command": "python3 solve.py", "timeout": 30}),
		`{"type":"done","summary":"solve.py prints 900."}`,
	}, nil, func(ctx *AgentContext) { workContract(t, ctx) })
	if run.terminal["status"] != "completed" {
		t.Errorf("a run that wrote and ran its solver reported %s / %s\n%s",
			run.terminal["status"], run.terminal["reason"], run.feedback())
	}
}

// 3. A model-authored plan cannot erase a requirement the user stated.
func TestAPlanCannotEraseARequestedBehaviour(t *testing.T) {
	run := startBgWorldReq(t, standupRequest, map[string]string{}, []string{
		toolCall("write_file", map[string]interface{}{"path": "app.py", "content": serverApp()}),
		toolCall("run_background", map[string]interface{}{"command": "python app.py"}),
		toolCall("run_command", map[string]interface{}{
			"command": "curl -s -X POST http://127.0.0.1:5001/standup", "timeout": 10}),
		toolCall("stop_background", map[string]interface{}{"job_id": "job1"}),
		`{"type":"done","summary":"All planned steps are complete."}`,
		`{"type":"done","summary":"All planned steps are complete."}`,
		`{"type":"done","summary":"All planned steps are complete."}`,
		`{"type":"done","summary":"All planned steps are complete."}`,
	}, func(string, *bgWorld) ([]string, int, bool) { return nil, 0, true },
		func(ctx *AgentContext) {
			workContract(t, ctx)
			// A plan that mentions only posting: it says nothing about looking back.
			ctx.Plan = &Plan{VerifyStep: "s2", Steps: []PlanStep{
				{ID: "s1", Action: "write_file", Target: "app.py"},
				{ID: "s2", Action: "run_command", Target: "curl -s -X POST http://127.0.0.1:5001/standup"},
			}}
		})
	if run.terminal["reason"] != "requirements_unverified" {
		t.Errorf("a plan that omitted the requirement produced %s / %s",
			run.terminal["status"], run.terminal["reason"])
	}
}

// 4. A relevant edit after the verification invalidates that evidence.
func TestAnEditAfterVerificationInvalidatesIt(t *testing.T) {
	solve := "def main():\n    print(900)\n\n\nif __name__ == '__main__':\n    main()\n"
	run := integrityLoopWith(t, "Write solve.py that reads input.txt and prints the total. Then run it "+
		"and confirm the answer.", map[string]string{"input.txt": "forward 5\n"}, []string{
		toolCall("write_file", map[string]interface{}{"path": "solve.py", "content": solve}),
		toolCall("run_command", map[string]interface{}{"command": "python3 solve.py", "timeout": 30}),
		toolCall("read_file", map[string]interface{}{"path": "solve.py"}),
		toolCall("edit_file", map[string]interface{}{"path": "solve.py",
			"old_str": "print(900)", "new_str": "print(901)"}),
		`{"type":"done","summary":"solve.py prints the total."}`,
		`{"type":"done","summary":"solve.py prints the total."}`,
		`{"type":"done","summary":"solve.py prints the total."}`,
		`{"type":"done","summary":"solve.py prints the total."}`,
	}, nil, func(ctx *AgentContext) { workContract(t, ctx) })
	if run.terminal["status"] == "completed" {
		t.Errorf("evidence from before the last edit still completed the run: %v", run.terminal)
	}
}

//  5. A requirement nothing could exercise is reported as a limitation, and the
//     extraction does not invent obligations out of chat.
func TestUnsupportedRequirementsAreReportedNotFabricated(t *testing.T) {
	reqs := requestedRequirements(standupRequest)
	var texts []string
	for _, r := range reqs {
		texts = append(texts, r.Text)
	}
	joined := strings.ToLower(strings.Join(texts, " | "))
	if !strings.Contains(joined, "look back at yesterdays") {
		t.Errorf("the stated requirement was not extracted: %v", texts)
	}
	if strings.Contains(joined, "slack mess") {
		t.Errorf("background chat became a requirement: %v", texts)
	}
	// A request with nothing exercisable yields nothing to block on.
	if got := requestedRequirements("hey, quick question about this repo"); len(got) != 0 {
		t.Errorf("a chat message produced requirements: %v", got)
	}
	// And the summary never asserts failure.
	msg := unshownRequirementSummary(reqs[:1])
	if strings.Contains(strings.ToLower(msg), "fail") || !strings.Contains(msg, "never exercised") {
		t.Errorf("limitation summary: %s", msg)
	}
}

func workContract(t *testing.T, ctx *AgentContext) {
	t.Helper()
	tc, err := validateTaskContract(&TaskContract{TaskMode: TaskModeWork}, ctx.WorkingDir)
	if err != nil {
		t.Fatal(err)
	}
	ctx.TaskContract = tc
}

func init() { _ = json.Marshal }
