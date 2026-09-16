package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// A reply is an answer only if its closing does not hand off to work that
// never happened. Every bugfind_tiebreak session across four benchmark runs
// (8 of 8) ended on such a closing and was reported completed; two of those
// closings are used verbatim below.

func TestReplyClosingThatDefersWorkIsDetected(t *testing.T) {
	for _, reply := range []string{
		// verbatim, 84296fd bugfind_tiebreak rep1/rep2
		"I have analyzed the code in `planning.py` and `scoring.py`.   The issue is in `planning.py` within the `_score_plan` function.   I will now check the `generate_plan` function in `planning.py` to see the sorting key.",
		// verbatim, f9c89df bugfind_tiebreak rep1/rep2
		"I have analyzed the code in `planning.py`. The issue is located in the `generate_plan` function (which I will now read to confirm the exact line). Based on the logic described, the tie-breaking comparison is likely using a 'greater than' instead of a 'less than' for the number of steps. Please wait while I verify the exact line.",
		"The cache keys look inconsistent. Let me verify this by reading orders.py.",
		"Next, I will locate where the cache key is built.",
		"I found the loop in scoring.py. Now I need to check how planning.py consumes it.",
		"That narrows it to two functions. I'll run the tests to confirm which one.",
	} {
		if !replyDefersWork(reply) {
			t.Errorf("a reply that closes by deferring work was not detected:\n  %q", reply)
		}
	}
}

func TestSubstantiveAnswersAreNotTreatedAsDeferrals(t *testing.T) {
	for _, reply := range []string{
		"The bug is on line 536 of planning.py: `n_steps > best_steps` should be `n_steps < best_steps`.",
		"Line 536 of planning.py inverts the tie-break. If you'd like, I can fix it.",
		"read_key in keys.py builds the key from the tier while write_key uses the id, so every lookup misses. Let me know if you want me to look at the other modules.",
		"I'll explain the flow first. cache.py stores entries under keys from keys.py; pricing.py reads with read_key and writes with write_key, which disagree, so nothing is ever found.",
		"I checked planning.py and scoring.py. The comparison on line 536 is inverted: ties go to the plan with more steps.",
		"I read the file and traced the call from orders.py into pricing.py. The miss happens because the two key functions use different customer fields.",
		"I couldn't run the tests here, but the fix is to change `>` to `>=` on line 6 of filters.py.",
		"Would you like me to check the other modules as well? The cause in keys.py is that read_key and write_key use different fields.",
	} {
		if replyDefersWork(reply) {
			t.Errorf("a substantive answer was treated as deferring work:\n  %q", reply)
		}
	}
}

func TestReplyDeclaringInabilityIsDetected(t *testing.T) {
	yes := []string{
		"I could not determine the cause from these files.",
		"I read all three modules. I wasn't able to find where the cache key is built.",
		"I'm unable to identify which comparison is wrong without the missing module.",
	}
	no := []string{
		"The function is unable to handle an empty list, which is the bug.",
		"I couldn't run the tests here, but the fix is to change `>` to `>=` on line 6.",
		"The cause is in keys.py: read_key uses the tier and write_key uses the id.",
	}
	for _, r := range yes {
		if !replyDeclaresInability(r) {
			t.Errorf("an explicit first-person inability was not detected: %q", r)
		}
	}
	for _, r := range no {
		if replyDeclaresInability(r) {
			t.Errorf("an answer was treated as declaring inability: %q", r)
		}
	}
}

// --- through the real loop -------------------------------------------------

// Same shape as the benchmark prompt ("Tell me which file and which comparison
// is wrong -- do not change any code"), which production classifies read-only.
const unfinishedQ = "Tell me which comparison in plans.py decides ties between plans — do not change any code."

func plansFixture(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	src := "def pick(plans):\n    best = None\n    for p in plans:\n        if best is None or p.steps > best.steps:\n            best = p\n    return best\n"
	if err := os.WriteFile(filepath.Join(dir, "plans.py"), []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func readPlans() map[string]interface{} {
	return map[string]interface{}{"type": "tool_call", "name": "read_file",
		"args": map[string]interface{}{"path": "plans.py"}}
}

func textReply(s string) map[string]interface{} {
	return map[string]interface{}{"type": "text", "content": s}
}

const deferral = "plans.py decides ties in pick(). I will now check the comparison on line 4. Please wait while I verify the exact line."
const answer = "In plans.py, line 4 of pick() compares `p.steps > best.steps`, so a tie on score keeps the plan with MORE steps; it should be `<`."

// Unfinished promise after work -> sent back once -> acts -> answers -> completed.
func TestAReplyThatDefersWorkAfterAToolIsContinuedNotCompleted(t *testing.T) {
	dir := plansFixture(t)
	ctx, turns, census, terminal := termFixture(t, dir, unfinishedQ, termCeiling,
		func(i int, _ string) map[string]interface{} {
			switch i {
			case 0:
				return readPlans()
			case 1:
				return textReply(deferral)
			case 2:
				return readPlans()
			default:
				return textReply(answer)
			}
		})
	if err := runAgentLoop(ctx, unfinishedQ); err != nil {
		t.Fatal(err)
	}
	if census["gate"] < 1 {
		t.Errorf("the deferring reply was not sent back (gate events=%d)", census["gate"])
	}
	if terminal["status"] != string(TerminalCompleted) || terminal["reason"] != "text_reply" {
		t.Errorf("after acting and answering, want completed/text_reply, got %s/%s", terminal["status"], terminal["reason"])
	}
	if *turns != 4 {
		t.Errorf("want 4 model turns (read, defer, read, answer), got %d", *turns)
	}
}

// Keeps deferring -> bounces spent -> incomplete, never completed.
func TestAReplyThatKeepsDeferringEndsIncompleteWhenBouncesAreSpent(t *testing.T) {
	dir := plansFixture(t)
	ctx, turns, census, terminal := termFixture(t, dir, unfinishedQ, termCeiling,
		func(i int, _ string) map[string]interface{} {
			if i == 0 {
				return readPlans()
			}
			return textReply(deferral)
		})
	if err := runAgentLoop(ctx, unfinishedQ); err != nil {
		t.Fatal(err)
	}
	if terminal["status"] == string(TerminalCompleted) {
		t.Fatalf("a reply that never stopped deferring was reported completed: %v", terminal)
	}
	if terminal["reason"] != "reply_left_work_outstanding" {
		t.Errorf("want reason reply_left_work_outstanding, got %s/%s", terminal["status"], terminal["reason"])
	}
	if census["gate"] != maxGateBounces {
		t.Errorf("continuation must be bounded by maxGateBounces=%d, got %d gate events", maxGateBounces, census["gate"])
	}
	if *turns != 1+maxGateBounces+1 {
		t.Errorf("want %d turns (read + %d bounced + final), got %d", 2+maxGateBounces, maxGateBounces, *turns)
	}
}

// A substantive answer, even one offering follow-up, completes with no extra work.
func TestASubstantiveAnswerAfterWorkCompletesWithoutContinuation(t *testing.T) {
	dir := plansFixture(t)
	ctx, turns, census, terminal := termFixture(t, dir, unfinishedQ, termCeiling,
		func(i int, _ string) map[string]interface{} {
			if i == 0 {
				return readPlans()
			}
			return textReply(answer + " If you'd like, I can fix it.")
		})
	if err := runAgentLoop(ctx, unfinishedQ); err != nil {
		t.Fatal(err)
	}
	if terminal["status"] != string(TerminalCompleted) || terminal["reason"] != "text_reply" {
		t.Errorf("want completed/text_reply, got %s/%s", terminal["status"], terminal["reason"])
	}
	if census["gate"] != 0 || *turns != 2 {
		t.Errorf("a complete answer must not be sent back: gate events=%d turns=%d", census["gate"], *turns)
	}
}

// A question that can be answered directly needs no tool call.
func TestADirectAnswerNeedsNoToolUse(t *testing.T) {
	dir := t.TempDir()
	const q = "What does a tie-break comparison do?"
	ctx, turns, census, terminal := termFixture(t, dir, q, termCeiling,
		func(i int, _ string) map[string]interface{} {
			return textReply("It decides between two candidates that score equally, usually by a secondary key such as length. Let me know if you want an example.")
		})
	if err := runAgentLoop(ctx, q); err != nil {
		t.Fatal(err)
	}
	if terminal["status"] != string(TerminalCompleted) || census["gate"] != 0 || *turns != 1 {
		t.Errorf("a direct answer must complete in one turn with no gate: %s/%s gates=%d turns=%d",
			terminal["status"], terminal["reason"], census["gate"], *turns)
	}
}

// Honest inability ends incomplete at once -- not completed, not sent back.
func TestHonestInabilityEndsIncompleteWithoutBeingSentBack(t *testing.T) {
	dir := plansFixture(t)
	ctx, turns, census, terminal := termFixture(t, dir, unfinishedQ, termCeiling,
		func(i int, _ string) map[string]interface{} {
			if i == 0 {
				return readPlans()
			}
			return textReply("I read plans.py. I could not determine which comparison decides ties from this file alone.")
		})
	if err := runAgentLoop(ctx, unfinishedQ); err != nil {
		t.Fatal(err)
	}
	if terminal["status"] != string(TerminalIncomplete) || terminal["reason"] != "reply_declared_incomplete" {
		t.Errorf("want incomplete/reply_declared_incomplete, got %s/%s", terminal["status"], terminal["reason"])
	}
	if census["gate"] != 0 || *turns != 2 {
		t.Errorf("an honest inability must not be sent back: gate events=%d turns=%d", census["gate"], *turns)
	}
}

// Too little budget to act on a deferral: end incomplete now, spend no bounce.
func TestADeferringReplyWithNoBudgetLeftEndsIncompleteImmediately(t *testing.T) {
	dir := plansFixture(t)
	ctx, turns, census, terminal := termFixture(t, dir, unfinishedQ, termCeiling,
		func(i int, _ string) map[string]interface{} {
			if i == 0 {
				return readPlans()
			}
			return textReply(deferral)
		})
	work, cancel := context.WithTimeout(context.Background(), replyContinuationFloor/2)
	defer cancel()
	ctx.Ctx = work
	if err := runAgentLoop(ctx, unfinishedQ); err != nil {
		t.Fatal(err)
	}
	if terminal["status"] != string(TerminalIncomplete) || terminal["reason"] != "reply_left_work_outstanding" {
		t.Errorf("want incomplete/reply_left_work_outstanding, got %s/%s", terminal["status"], terminal["reason"])
	}
	if census["gate"] != 0 || *turns != 2 {
		t.Errorf("with %s left no continuation may start: gate events=%d turns=%d",
			replyContinuationFloor/2, census["gate"], *turns)
	}
	_ = time.Second
}
