package main

import (
	"strings"
	"testing"
)

// A file the session left unparseable (#214) is shown when it opens, when an
// attempt changes it and it still fails, when it is fixed, and when it is
// handed back. A refused attempt changed nothing and is not shown.
func TestRepairEventsAreShown(t *testing.T) {
	m := &tuiModel{}
	for _, ev := range []map[string]interface{}{
		{"event": "opened", "path": "mod.py", "tool": "write_file", "landed": true,
			"error": "SyntaxError: invalid syntax (line 4)"},
		{"event": "attempt", "path": "mod.py", "tool": "edit_file", "landed": false,
			"error": "old_str not found in mod.py"},
		{"event": "attempt", "path": "mod.py", "tool": "replace_lines", "landed": true,
			"error": "SyntaxError: invalid syntax (line 5)"},
		{"event": "closed", "path": "mod.py", "how": "parses", "attempts": 3},
		{"event": "opened", "path": "b.py", "tool": "write_file", "landed": true,
			"error": "SyntaxError: '(' was never closed (line 2)"},
		{"event": "handoff", "path": "b.py", "attempts": 1},
	} {
		m.appendChatEvent(mkChatEvent("repair", ev))
	}
	var got []string
	for _, c := range m.chat {
		if c.Meta == "repair" {
			got = append(got, c.Body)
		}
	}
	want := []string{
		"mod.py does not parse: SyntaxError: invalid syntax (line 4)",
		"mod.py still does not parse after replace_lines: SyntaxError: invalid syntax (line 5)",
		"mod.py parses again (3 attempts)",
		"b.py does not parse: SyntaxError: '(' was never closed (line 2)",
		"b.py still does not parse after 1 attempt: handed back to you",
	}
	if strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Errorf("repair lines:\n%s\nwant:\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}
