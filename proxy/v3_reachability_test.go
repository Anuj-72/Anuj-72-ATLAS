package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Whether the candidate pipeline can contribute to delivered software for the
// request this product actually receives: a person's prose, and nothing else.
//
// The existing automatic-delivery tests enter at writeFileWithV3, which is
// BELOW the decision that turned every exposed session away. Across six
// preserved regressions -- 26 sessions -- the producer was consulted zero
// times, and 54 skips recorded the same reason:
// candidate_undeliverable_under_policy. Nothing in that path was broken; the
// generation owner asks whether a candidate could be delivered, and for a
// request that declared no outputs under the default strict policy the honest
// answer is no.
//
// So these enter where a real session does, at the tool call, and walk the
// whole path: generated, checked, selected, authorized, applied byte-exactly,
// rechecked -- with the refusals that must survive it.

// reachWorld drives executeToolCall("write_file", ...) against a producer that
// counts its consultations, so "the pipeline never ran" and "the pipeline ran
// and refused" can never be confused for one another.
type reachWorld struct {
	ctx *AgentContext
	dir string
	// v3Calls counts generation requests that reached the producer.
	v3Calls int
	// winner is what the producer returns and names as its selection.
	winner string
	// selects overrides the hash the producer claims to have selected. Empty
	// means it names the winner it returned, which is the honest case.
	selects string
	// broken is content the sandbox reports as unparseable, by exact match.
	broken map[string]bool
	// unsupported makes the producer's evidence say no adapter could measure
	// this class. Strict then has no floor it can show was met.
	unsupported bool
}

// The prose a person types. No paths, no commands, no expected outputs -- the
// request shape stabilization6/R through stabilization10/T actually sent.
const reachProse = "i need something that keeps track of the items we lend " +
	"out and tells me whats currently out and who has it"

// The model's own bytes: Tier2 by the classifier (a .py file over ten lines
// with logic indicators), parseable, and correct enough to keep.
const reachBaseline = `ITEMS = {}
OUT = {}


def add(item):
    ITEMS[item] = True


def check_out(item, who):
    if item in OUT:
        return False
    OUT[item] = who
    return True


def outstanding():
    result = []
    for item in OUT:
        result.append((item, OUT[item]))
    return result
`

// What the pipeline came back with: the same interface, a different body.
const reachWinner = `ITEMS = {}
OUT = {}


def add(item):
    ITEMS[item] = True


def check_out(item, who):
    if item in OUT:
        return False
    OUT[item] = who
    return True


def outstanding():
    return sorted(OUT.items())
`

// A candidate that does not parse. It must never reach disk, and the model's
// own working bytes must survive it.
const reachBrokenWinner = `ITEMS = {}
OUT = {}


def add(item):
    ITEMS[item] = True


def check_out(item, who
    if item in OUT:
        return False
    OUT[item] = who
    return True


def outstanding():
    return sorted(OUT.items())
`

func newReachWorld(t *testing.T, contract string) *reachWorld {
	t.Helper()
	w := &reachWorld{dir: t.TempDir(), winner: reachWinner, broken: map[string]bool{}}
	srv := httptest.NewServer(http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		switch {
		case strings.Contains(r.URL.Path, "/v3/generate"):
			w.v3Calls++
			selected := w.selects
			if selected == "" {
				selected = contentSHA256(w.winner)
			}
			body, _ := json.Marshal(map[string]interface{}{
				"code": w.winner, "passed": true, "phase_solved": "phase_one",
				"candidates_tested": 5, "winning_score": 0.9,
				"evidence": map[string]interface{}{
					"wire_version": "1.0.0", "record_schema_version": "1.1.0",
					"identity": map[string]interface{}{
						"contract_id": "c.v1", "contract_version": "1",
						"adapter_id": "python_compile", "adapter_version": "0.1.0-prototype",
						"artifact_scope": "inventory.py", "evaluation_context_hash": "ctx",
						"candidate_content_hash": selected,
					},
					"evaluation": map[string]interface{}{
						"execution_status": "ok", "supported": !w.unsupported,
						"evidence_strength": "syntax", "requirements_complete": true,
						"closure_eligible": false,
						"quality": map[string]interface{}{
							"required_coverage": 1.0, "optional_quality": 1.0, "overall": 1.0},
					},
					"coverage":  map[string]interface{}{"required": []string{}, "demonstrated": []string{}},
					"selection": map[string]interface{}{"status": "best_not_closure_eligible", "reason": "highest"},
					"delivery": map[string]interface{}{
						"delivered_content_hash": selected, "describes_delivered_candidate": true},
				},
			})
			rw.Header().Set("Content-Type", "text/event-stream")
			fl, _ := rw.(http.Flusher)
			for _, line := range []string{"event: result", "data: " + string(body), "", "data: [DONE]", ""} {
				fmt.Fprint(rw, line+"\n")
				if fl != nil {
					fl.Flush()
				}
			}
		case strings.HasSuffix(r.URL.Path, "/syntax-check"):
			var in struct {
				Code string `json:"code"`
			}
			json.NewDecoder(r.Body).Decode(&in)
			if w.broken[in.Code] {
				json.NewEncoder(rw).Encode(map[string]interface{}{
					"valid": false, "errors": []string{"SyntaxError: invalid syntax (line 9)"}})
				return
			}
			json.NewEncoder(rw).Encode(map[string]interface{}{"valid": true})
		case r.URL.Path == "/internal/structural_check":
			json.NewEncoder(rw).Encode(map[string]interface{}{"ok": true, "unresolved": []string{}})
		case r.URL.Path == "/internal/cyclomatic_complexity":
			json.NewEncoder(rw).Encode(map[string]interface{}{"functions": []interface{}{}})
		default:
			json.NewEncoder(rw).Encode(map[string]interface{}{"ok": true})
		}
	}))
	t.Cleanup(srv.Close)

	ctx := NewAgentContext(w.dir, Tier2Medium)
	ctx.PermissionMode = PermissionYolo
	ctx.Ctx = context.WithValue(context.Background(), requestIDKey, "req-reach")
	ctx.V3URL, ctx.SandboxURL = srv.URL, srv.URL
	ctx.V3Mode = V3ModeFull
	// The prose, exactly as the client sent it. It is the only thing about the
	// task this session knows.
	ctx.HumanTask = reachProse
	if contract != "" {
		ctx.TaskContract = mustContract(t, w.dir, contract)
	}
	w.ctx = ctx
	return w
}

// write makes the model's own write_file call, through the real dispatch.
func (w *reachWorld) write(t *testing.T) *ToolResult {
	t.Helper()
	args, err := json.Marshal(map[string]string{"path": "inventory.py", "content": reachBaseline})
	if err != nil {
		t.Fatal(err)
	}
	return executeToolCall("write_file", args, w.ctx)
}

func (w *reachWorld) disk(t *testing.T) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(w.dir, "inventory.py"))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// The contract a person's request carries when the client selects the
// interactive mode: work, automatic_v3, and nothing about the task.
const reachAutomatic = `{"task_mode":"work","candidate_policy":"automatic_v3"}`

// --- 1. What the product does today -----------------------------------------

// The observed live state, pinned. A prose request declares no outputs, the
// default policy is strict, and nothing the producer could return would be
// allowed to land -- so it is never asked.
//
// This is not a defect report. It is the contract, made executable, so a
// change to it cannot happen silently.
func TestAProseOnlyRequestNeverReachesTheProducer(t *testing.T) {
	for _, tc := range []struct{ name, contract string }{
		{"no task_contract at all", ""},
		{"a contract that states only its mode", `{"task_mode":"work"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			w := newReachWorld(t, tc.contract)
			var res *ToolResult
			recs := captureShadow(t, func() { res = w.write(t) })
			if res == nil || !res.Success {
				t.Fatalf("the model's own write failed: %+v", res)
			}
			if w.v3Calls != 0 {
				t.Errorf("the producer was consulted %d times for an undeliverable candidate", w.v3Calls)
			}
			if got := w.disk(t); got != reachBaseline {
				t.Error("the model's own bytes are not what landed")
			}
			var reasons []string
			for _, r := range bypassRecords(recs) {
				reasons = append(reasons, fmt.Sprint(r["reason"]))
			}
			if len(reasons) != 1 || reasons[0] != string(bypassCandidateUndeliverable) {
				t.Errorf("bypass reasons %v, want exactly [%s]", reasons, bypassCandidateUndeliverable)
			}
		})
	}
}

// --- 2. The whole path, on the same prose -----------------------------------

// Generated, checked, selected, authorized, applied byte-exactly, rechecked.
//
// The request differs from the one above in exactly one field -- the client's
// candidate_policy selection. It declares no outputs, no commands and no
// requirements, and the prose is unchanged. The target the candidate lands on
// comes from the model's own tool call, not from anything supplied here.
func TestTheSelectedCandidateIsAppliedByteExactlyAndRechecked(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	var res *ToolResult
	recs := captureShadow(t, func() { res = w.write(t) })
	if res == nil || !res.Success {
		t.Fatalf("the write failed: %+v", res)
	}
	// Generated.
	if w.v3Calls != 1 {
		t.Fatalf("the producer was consulted %d times, want 1", w.v3Calls)
	}
	for _, r := range bypassRecords(recs) {
		t.Errorf("a consulted route recorded a skip: %v", r["reason"])
	}
	// Applied, byte-exactly. Not normalised, not re-indented, no appended
	// newline: the authorized bytes or nothing.
	if got := w.disk(t); got != reachWinner {
		t.Fatalf("disk does not hold the selected candidate byte-for-byte:\n%q", got)
	}
	// Authorized, and the result names the authorization it spent.
	if res.AuthorizedDeliveryHash != contentSHA256(reachWinner) {
		t.Errorf("AuthorizedDeliveryHash %q does not name the delivered bytes",
			res.AuthorizedDeliveryHash)
	}
	if !res.V3Used {
		t.Error("a delivered candidate does not report its provenance")
	}
	if liveGrantCount(w.ctx) != 0 {
		t.Error("the grant was not spent")
	}
	// Rechecked: the bytes that landed pass their own check, asked of the
	// same owner the completion boundary asks.
	if st := fallbackSyntaxOutcomeFor(w.ctx, filepath.Join(w.dir, "inventory.py"),
		w.disk(t)).aggregate().Status; st != ValidationPassed {
		t.Errorf("the delivered bytes were not rechecked clean: %v", st)
	}
	// And the licence says which rule earned it. An automatic delivery read
	// back as a strict authorization would name a floor that was never met.
	var bases []string
	for _, r := range recordsOfKind(recs, "candidate_policy_decision") {
		bases = append(bases, fmt.Sprint(r["decision"]))
	}
	if len(bases) == 0 {
		t.Error("the delivery recorded no policy decision")
	}
	for _, b := range bases {
		if b == string(PolicyCandidateAuthorizedStrict) {
			t.Error("an automatic delivery was recorded as a strict authorization")
		}
	}
}

// --- 3. What a delivery is NOT ----------------------------------------------

// A file that parses is a file that parses.
//
// The candidate landed and was rechecked; the user asked to track lending and
// see what is out. Nothing has been run, and no requirement has been shown.
// The completion boundary must be exactly as unconvinced as it was before the
// pipeline contributed anything.
func TestADeliveryIsNotAClaimThatTheRequestIsComplete(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	if res := w.write(t); res == nil || !res.Success {
		t.Fatalf("the write failed: %+v", res)
	}
	if got := w.disk(t); got != reachWinner {
		t.Fatalf("precondition: the candidate did not land")
	}
	status, reason := finalizeCompletion(w.ctx, &runState{}, reachProse, "")
	if status == TerminalCompleted {
		t.Errorf("a delivered, parsing candidate was read as a finished request (%q)", reason)
	}
	// The deliverable gate still answers for itself rather than deferring to
	// the delivery that just happened.
	if _, why := terminalCompletionAllowed(w.ctx, nil); why == "" {
		t.Error("the deliverable gate stopped answering after a delivery")
	}
}

// --- 4. The refusals that have to survive -----------------------------------

// A candidate the service did not name as its selection does not land.
//
// The identity binding is the whole story: the proxy hashes the bytes it
// holds, and a selection naming anything else is a candidate nobody chose.
func TestACandidateTheServiceDidNotSelectIsRefused(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	w.selects = contentSHA256("some other candidate entirely\n")
	res := w.write(t)
	if res == nil || !res.Success {
		t.Fatalf("the write itself failed: %+v", res)
	}
	if w.v3Calls != 1 {
		t.Fatalf("the producer was consulted %d times, want 1", w.v3Calls)
	}
	if got := w.disk(t); got != reachBaseline {
		t.Errorf("an unselected candidate landed: %q", got)
	}
	if res.AuthorizedDeliveryHash != "" {
		t.Errorf("a refused candidate named an authorization: %q", res.AuthorizedDeliveryHash)
	}
	if liveGrantCount(w.ctx) != 0 {
		t.Error("a refusal left a live grant behind")
	}
}

// A candidate that does not parse does not land, and the model's own working
// bytes survive the attempt.
//
// This is the regression case: the pipeline is permitted to propose, and the
// proposal is checked against the same rule every other write obeys. A
// pipeline allowed to deliver broken bytes over working ones would be worse
// than not running.
func TestACandidateThatFailsItsCheckDoesNotDisplaceWorkingBytes(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	w.winner = reachBrokenWinner
	w.broken[reachBrokenWinner] = true
	res := w.write(t)
	if res == nil {
		t.Fatal("no result")
	}
	if w.v3Calls != 1 {
		t.Fatalf("the producer was consulted %d times, want 1", w.v3Calls)
	}
	if got := w.disk(t); got != reachBaseline {
		t.Errorf("a candidate that fails its check displaced working bytes: %q", got)
	}
	if res.AuthorizedDeliveryHash == contentSHA256(reachBrokenWinner) {
		t.Error("the broken candidate was named as an authorized delivery")
	}
}

// A question creates no mutation authority, whatever the policy says.
//
// automatic_v3 grounds a delivery on the model's own structured call. That
// grounding is available to work requests only: structuredMutationTargetGrounds
// refuses a question before it looks at the call at all, so there is no target
// for a candidate to bind to and the baseline stays.
func TestAQuestionRequestGroundsNoAutomaticDelivery(t *testing.T) {
	w := newReachWorld(t, `{"task_mode":"question","candidate_policy":"automatic_v3"}`)
	res := w.write(t)
	if res == nil || !res.Success {
		t.Fatalf("the write itself failed: %+v", res)
	}
	if got := w.disk(t); got != reachBaseline {
		t.Errorf("a question request delivered a candidate: %q", got)
	}
	if res.AuthorizedDeliveryHash != "" {
		t.Errorf("a question request named an authorization: %q", res.AuthorizedDeliveryHash)
	}
}

// The grounding owner refuses every request that is not this one's.
//
// Asked directly, because these are the facts the whole path above rests on
// and each has to fail for its own stated reason rather than by accident.
func TestStructuredGroundingRefusesWhatIsNotThisCall(t *testing.T) {
	dir := t.TempDir()
	ctx := NewAgentContext(dir, Tier2Medium)
	ctx.Ctx = context.WithValue(context.Background(), requestIDKey, "req-reach")
	ctx.TaskContract = mustContract(t, dir, reachAutomatic)
	target := filepath.Join(dir, "inventory.py")
	scope := testMutationScope(ctx, mintRouteEntry(ctx), target, reachBaseline)
	if ok, _ := structuredMutationTargetGrounds(ctx, CandidatePolicyAutomaticV3, scope, target); !ok {
		t.Fatal("precondition: this call does not ground its own target")
	}
	for _, tc := range []struct {
		name, want string
		mode       candidatePolicyMode
		scope      mutationScope
		target     string
	}{
		{"strict never uses it", structuredTargetNotAutomatic, CandidatePolicyStrict, scope, target},
		{"advisory never uses it", structuredTargetNotAutomatic, CandidatePolicyAdvisory, scope, target},
		{"another path in the same request", structuredTargetMismatch,
			CandidatePolicyAutomaticV3, scope, filepath.Join(dir, "other.py")},
		{"no scope at all", structuredTargetNoScope,
			CandidatePolicyAutomaticV3, mutationScope{}, target},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ok, why := structuredMutationTargetGrounds(ctx, tc.mode, tc.scope, tc.target)
			if ok {
				t.Fatalf("grounded a delivery it should have refused")
			}
			if why != tc.want {
				t.Errorf("refused as %q, want %q", why, tc.want)
			}
		})
	}
	// A scope minted for a different request grounds nothing, even for the
	// same path under the same policy.
	other := NewAgentContext(dir, Tier2Medium)
	other.Ctx = context.WithValue(context.Background(), requestIDKey, "req-other")
	other.TaskContract = ctx.TaskContract
	if ok, why := structuredMutationTargetGrounds(other, CandidatePolicyAutomaticV3,
		scope, target); ok || why != structuredTargetNotThisRequest {
		t.Errorf("another request's scope grounded a delivery: ok=%v why=%q", ok, why)
	}
}

// The structured target is not an obligation, and a delivery does not create
// one. If it ever did, the completion boundary would start counting a file the
// model happened to write as a file the user asked for.
func TestAnAutomaticDeliveryCreatesNoObligation(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	before := len(requestObligations(w.ctx))
	if res := w.write(t); res == nil || !res.Success {
		t.Fatalf("the write failed: %+v", res)
	}
	if got := w.disk(t); got != reachWinner {
		t.Fatalf("precondition: the candidate did not land")
	}
	if after := len(requestObligations(w.ctx)); after != before {
		t.Errorf("the delivery created %d obligations", after-before)
	}
	if outputKnowledgeDeclared(w.ctx) {
		t.Error("a delivery made the request look as though it declared its outputs")
	}
}

// --- 5. What the intervention still does NOT reach ---------------------------
//
// Predicted in the cycle-12 record before it was measured, and pinned here so
// the prediction is executable rather than asserted. Selecting automatic_v3
// satisfies the policy clause of candidateDeliverableUnderPolicy. It does not
// touch the two rules that turn the producer away in exactly the situation
// cycle 11 wanted help with: a file that does not parse.

// Bytes that do not parse are never sent to the producer, whatever the policy.
//
// The rule is a cost rule with a stated reason -- "V3 improves a working
// candidate; it does not exist to guess what a malformed one meant" -- and it
// fires before generation. So the pipeline cannot repair a broken first draft:
// the draft is the one input it will not look at.
func TestBrokenBytesStillNeverReachTheProducer(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	w.broken[reachBrokenWinner] = true
	args, _ := json.Marshal(map[string]string{"path": "inventory.py", "content": reachBrokenWinner})
	var res *ToolResult
	recs := captureShadow(t, func() { res = executeToolCall("write_file", args, w.ctx) })
	if res == nil || !res.Success {
		t.Fatalf("a new broken file should land with a warning: %+v", res)
	}
	if w.v3Calls != 0 {
		t.Errorf("the producer was consulted %d times on bytes that do not parse", w.v3Calls)
	}
	var out WriteFileOutput
	if err := json.Unmarshal(res.Data, &out); err != nil {
		t.Fatalf("the write reported no decodable payload: %v", err)
	}
	if out.Warning == "" {
		t.Error("broken bytes landed without a warning")
	}
	if res.ValidationStatus != ValidationFailed {
		t.Errorf("the failing check was reported as %v", res.ValidationStatus)
	}
	var reasons []string
	for _, r := range bypassRecords(recs) {
		reasons = append(reasons, fmt.Sprint(r["reason"]))
	}
	found := false
	for _, r := range reasons {
		if r == string(bypassProposalFailedSyntaxGuard) {
			found = true
		}
		if r == string(bypassCandidateUndeliverable) {
			t.Error("automatic_v3 did not satisfy the policy clause")
		}
	}
	if !found {
		t.Errorf("bypass reasons %v, want %s", reasons, bypassProposalFailedSyntaxGuard)
	}
}

// The repair loop still takes the fast path.
//
// A file this session wrote, whose most recent tool action was a failing run
// naming it, is being debugged -- and execution is the feedback. The producer
// is not consulted there either, under any policy.
func TestTheRepairLoopStillTakesTheFastPath(t *testing.T) {
	w := newReachWorld(t, reachAutomatic)
	if err := os.WriteFile(filepath.Join(w.dir, "inventory.py"), []byte(reachBaseline), 0o644); err != nil {
		t.Fatal(err)
	}
	w.ctx.SessionWrites["inventory.py"] = true
	w.ctx.Messages = []AgentMessage{
		{Role: "user", Content: reachProse},
		{Role: "assistant", Content: "running it"},
		{Role: "tool", ToolName: "run_command", Content: `{"success":false,"data":{"stderr":` +
			`"Traceback (most recent call last):\n  File \"./inventory.py\", line 9\nNameError"}}`},
	}
	if !isActiveDebugIteration(w.ctx, "inventory.py") {
		t.Fatal("precondition: the fixture is not an active debug iteration")
	}
	// The repair attempt itself: different bytes from what is on disk, which is
	// what a session mid-debug actually sends.
	args, _ := json.Marshal(map[string]string{"path": "inventory.py", "content": reachWinner})
	var res *ToolResult
	recs := captureShadow(t, func() { res = executeToolCall("write_file", args, w.ctx) })
	if res == nil || !res.Success {
		t.Fatalf("the direct write failed: %+v", res)
	}
	if w.v3Calls != 0 {
		t.Errorf("the producer was consulted %d times inside a repair loop", w.v3Calls)
	}
	var reasons []string
	for _, r := range bypassRecords(recs) {
		reasons = append(reasons, fmt.Sprint(r["reason"]))
	}
	if len(reasons) != 1 || reasons[0] != string(bypassActiveDebugIteration) {
		t.Errorf("bypass reasons %v, want exactly [%s]", reasons, bypassActiveDebugIteration)
	}
}

// A question request generates candidates it can never deliver.
//
// Recorded as a finding, not fixed here. candidateDeliverableUnderPolicy
// documents itself as mirroring the delivery owners and erring toward "could";
// for automatic_v3 it returns true without asking whether the request declares
// work, while structuredMutationTargetGrounds refuses a question outright. With
// no declared outputs that is a delivery which is impossible rather than
// merely uncertain, so the generation budget is spent for nothing. The
// smallest correction would be to ask requestDeclaresWork in that branch and
// fall through to outputKnowledgeDeclared otherwise, which changes no
// delivery that happens today. It is specified here and deliberately left
// unimplemented: it is a cost defect in a mode this cycle does not measure,
// and shipping it would not change any decision in front of us.
func TestAQuestionUnderAutomaticV3GeneratesWhatItCannotDeliver(t *testing.T) {
	w := newReachWorld(t, `{"task_mode":"question","candidate_policy":"automatic_v3"}`)
	if res := w.write(t); res == nil || !res.Success {
		t.Fatalf("the write failed: %+v", res)
	}
	if w.v3Calls != 1 {
		t.Fatalf("the producer was consulted %d times, want 1 -- the finding has changed", w.v3Calls)
	}
	if got := w.disk(t); got != reachBaseline {
		t.Fatal("a question request delivered a candidate")
	}
	// Both halves of the finding, so a later correction fails here loudly
	// rather than silently changing what this documents.
	if !candidateDeliverableUnderPolicy(w.ctx) {
		t.Error("generation is no longer permitted here; update this finding")
	}
	scope := testMutationScope(w.ctx, mintRouteEntry(w.ctx),
		filepath.Join(w.dir, "inventory.py"), reachBaseline)
	if ok, why := structuredMutationTargetGrounds(w.ctx, CandidatePolicyAutomaticV3,
		scope, filepath.Join(w.dir, "inventory.py")); ok || why != structuredTargetNotWork {
		t.Errorf("delivery grounding: ok=%v why=%q, want the question refusal", ok, why)
	}
}

// --- 6. What the two policies each refuse -----------------------------------

// Strict refuses what it cannot show. Automatic delivers it, and says so.
//
// Same producer, same candidate, same prose, same target -- and an evidence
// envelope reporting that no adapter could measure this class. Strict has no
// floor it can demonstrate was met, so the baseline stays: that is the
// inadequate-evidence refusal, taken through the real tool call rather than
// asserted about the authorization owner.
//
// Under automatic_v3 the same candidate lands, because automatic is not a
// lower bar for the same question -- it is a different question, and the
// safety requirements are what it answers. The pair is tested together so the
// record can never describe the automatic delivery as evidence it is not.
func TestStrictRefusesUnmeasurableEvidenceWhereAutomaticDelivers(t *testing.T) {
	const declared = `{"task_mode":"work","output_knowledge":"declared",` +
		`"expected_outputs":["inventory.py"]}`
	strict := newReachWorld(t, declared)
	strict.unsupported = true
	res := strict.write(t)
	if res == nil || !res.Success {
		t.Fatalf("the write failed: %+v", res)
	}
	if strict.v3Calls != 1 {
		t.Fatalf("the producer was consulted %d times under strict, want 1", strict.v3Calls)
	}
	if got := strict.disk(t); got != reachBaseline {
		t.Errorf("strict delivered on evidence it could not measure: %q", got)
	}
	if res.AuthorizedDeliveryHash != "" {
		t.Errorf("strict named an authorization it did not have: %q", res.AuthorizedDeliveryHash)
	}

	auto := newReachWorld(t, reachAutomatic)
	auto.unsupported = true
	var autoRes *ToolResult
	recs := captureShadow(t, func() { autoRes = auto.write(t) })
	if autoRes == nil || !autoRes.Success {
		t.Fatalf("the automatic write failed: %+v", autoRes)
	}
	if got := auto.disk(t); got != reachWinner {
		t.Fatalf("automatic did not deliver the selected candidate: %q", got)
	}
	// And the record names the rule that earned it. This is the disclosure the
	// honesty of the whole arrangement rests on: a reader must be able to see
	// that no declared floor was met here, because there was none to meet.
	sawAutomatic := false
	for _, r := range recordsOfKind(recs, "candidate_policy_decision") {
		switch fmt.Sprint(r["decision"]) {
		case string(PolicyCandidateAutomaticV3):
			sawAutomatic = true
		case string(PolicyCandidateAuthorizedStrict):
			t.Error("an unmeasured candidate was recorded as strictly authorized")
		}
	}
	if !sawAutomatic {
		t.Error("the automatic delivery did not record the rule it used")
	}
}
