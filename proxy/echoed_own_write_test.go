package main

import (
	"strings"
	"testing"
)

// add_function rep 2 (smoke run 2026-09-28): the model re-sent the test file
// it had just written, three times, and was told each time that it did not
// need to reproduce input data. The test never ran, and passing work ended
// "stopped". For the session's own file the refusal says what happened and
// what comes next.

// ownTestFile is long enough (over 200 bytes) to count as an echo.
const ownTestFile = "from stats import mean, median\n\n\ndef test_mean():\n" +
	"    assert mean([1, 2, 3]) == 2.0\n    assert mean([1, 2, 3, 4]) == 2.5\n\n\n" +
	"def test_median():\n    assert median([1, 3, 2]) == 2\n    assert median([1, 2, 3, 4]) == 2.5\n" +
	"    assert median([1]) == 1\n\n\nif __name__ == '__main__':\n    test_mean()\n    test_median()\n"

func TestAnEchoOfTheSessionsOwnFileSaysRunIt(t *testing.T) {
	ctx, _ := exactEditWorld(t, "test_stats.py", ownTestFile)
	ctx.SessionWrites["test_stats.py"] = true
	res := exactEditCall(t, ctx, "write_file", map[string]interface{}{
		"path": "test_stats.py", "content": ownTestFile})
	if res.Success {
		t.Fatal("an echoed write was applied")
	}
	for _, want := range []string{"already holds exactly this content",
		"You wrote it earlier in this session", "run it"} {
		if !strings.Contains(res.Error, want) {
			t.Errorf("refusal lacks %q:\n%s", want, res.Error)
		}
	}
	if strings.Contains(res.Error, "input or fixture data") {
		t.Errorf("the session's own file was treated as fixture data:\n%s", res.Error)
	}
}

func TestAnEchoOfAFileTheSessionDidNotWriteKeepsTheFixtureWording(t *testing.T) {
	ctx, _ := exactEditWorld(t, "test_stats.py", ownTestFile)
	res := exactEditCall(t, ctx, "write_file", map[string]interface{}{
		"path": "test_stats.py", "content": ownTestFile})
	if res.Success || !strings.Contains(res.Error, "input or fixture data") {
		t.Errorf("want the fixture refusal, got success=%v:\n%s", res.Success, res.Error)
	}
}
