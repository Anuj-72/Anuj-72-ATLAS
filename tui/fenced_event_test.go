package main

import (
	"strings"
	"testing"
)

// Each fenced attempt (#254) is one line: how it went, and what the wire
// showed when a watchdog cut it.
func TestFencedFetchEventsAreShown(t *testing.T) {
	m := &tuiModel{}
	for _, ev := range []map[string]interface{}{
		{"path": "store.py", "attempt": 1, "grammar": "fence", "outcome": "unusable",
			"elapsed_ms": 38600, "content_chars": 709, "cut": "idle"},
		{"path": "store.py", "attempt": 2, "grammar": "raw", "outcome": "stalled",
			"elapsed_ms": 25100, "content_chars": 0, "reasoning_chars": 1840, "cut": "stalled"},
		{"path": "app.py", "attempt": 1, "grammar": "fence", "outcome": "used",
			"elapsed_ms": 4200, "content_chars": 512},
	} {
		m.appendChatEvent(mkChatEvent("fenced_fetch", ev))
	}
	var got []string
	for _, c := range m.chat {
		if c.Meta == "fenced" {
			got = append(got, c.Body)
		}
	}
	want := []string{
		"fenced store.py, attempt 1 (fence): unusable, 709 chars in 38.6s, cut by the idle watchdog",
		"fenced store.py, attempt 2 (raw): stalled, 0 chars in 25.1s, cut by the stalled watchdog, 1840 reasoning chars",
		"fenced app.py, attempt 1 (fence): used, 512 chars in 4.2s",
	}
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Errorf("fenced lines:\n%s\nwant:\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}
