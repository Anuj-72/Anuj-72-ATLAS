package main

import (
	"fmt"
	"regexp"
	"strings"
)

// Fenced-payload framing.
//
// "@fenced" routes a file body around the JSON channel, and the parent then
// has to answer one question about whatever came back: is this a whole file?
//
// It used to answer that from a single signal -- an opening fence with no
// closing fence -- and treat everything else as complete. "Everything else"
// includes a bare body with no fence at all, which proves nothing in either
// direction. The sealed Stage-A acquisition inlined 42 bodies and NOT ONE of
// them opened a fence, so the guard could not fire on any of them; one had
// stopped mid-emission and 1106 unframed bytes were written, parsed cleanly,
// and did nothing. A syntax gate cannot catch that, because a body cut in the
// middle of a comment is valid in most languages.
//
// So framing is the only evidence used here. A payload is a file when its
// protocol framing says the emission finished: an opening fence and a closing
// fence. Nothing below inspects the body -- not its language, syntax, final
// line, comments, indentation, prose, or length. A completed emission of
// something that looks unfinished is a file; an unterminated emission of
// something that looks finished is not.
type fenceFraming int

const (
	// fenceFramingComplete: opening and closing fences both present. The
	// body between them is the file.
	fenceFramingComplete fenceFraming = iota
	// fenceFramingUnterminated: fence markers are present but do not close a
	// block. The emission was cut, or never framed what followed.
	fenceFramingUnterminated
	// fenceFramingAbsent: no fence markers at all. There is no framing
	// evidence, so completion cannot be established either way.
	fenceFramingAbsent
	// fenceFramingAmbiguous: an interior line would itself close the outer
	// fence, so the reply is two blocks (a file plus a "run it with" snippet)
	// or a file whose own ``` lines sit inside a ``` wrapper. Which bytes are
	// the file cannot be known; guessing wrote the second block as the file.
	fenceFramingAmbiguous
)

func (f fenceFraming) String() string {
	switch f {
	case fenceFramingComplete:
		return "complete"
	case fenceFramingUnterminated:
		return "fence opened, never closed"
	case fenceFramingAmbiguous:
		return "more than one fence closes the block"
	default:
		return "no fence at all"
	}
}

// classifyFencedPayload is the ONE framing decision. The inline path and the
// sub-call path both route through it, so the two cannot drift into
// disagreeing about what counts as a finished emission.
//
// A body is returned only for fenceFramingComplete. Every other outcome
// returns "" so that no caller can accidentally use bytes whose framing did
// not prove they are whole.
func classifyFencedPayload(payload string) (fenceFraming, string) {
	return parseFencedReply(payload)
}

// fenceOpenRe / fenceCloseRe follow CommonMark fence lines: up to three spaces
// of indentation, a run of at least three backticks, and for an opener an
// optional info string (no backticks). A closer carries no info string. The
// trailing \r? keeps CRLF replies working without touching body bytes.
var (
	fenceOpenRe  = regexp.MustCompile("^ {0,3}(`{3,})[^`\\r\\n]*\\r?$")
	fenceCloseRe = regexp.MustCompile("^ {0,3}(`{3,})[ \\t]*\\r?$")
)

// parseFencedReply finds the file body in a fenced reply by fence LINES, not a
// lazy or greedy regex over the whole reply.
//
// The body is every line between the first opening fence and the LAST line
// that closes it (at least as many backticks), joined with the newline that
// ended each line, so its bytes -- CRLF included -- are exactly what the model
// wrote, ending in the line break before the closing fence. Prose before the
// opener or after the closer is not part of the file.
//
// Measured on c5927b3, the regex form did three wrong things with valid
// replies: trailing prose after the closing fence made it fall back to a lazy
// match that cut the file at its first interior ``` (a docstring example, a
// Markdown code block, a YAML block scalar); a four-backtick wrapper, the
// Markdown way to carry ``` inside a block, never matched at all; and a reply
// holding the file plus a separate "run it with" block was captured from the
// first opener to the last closer, so the bash snippet was written as a.py.
//
// A line inside the block that would itself close it is the one case where the
// file cannot be told apart from a second block. That is refused as ambiguous,
// with a retry path (wrap the file in four backticks), never guessed.
func parseFencedReply(reply string) (fenceFraming, string) {
	lines := strings.Split(reply, "\n")
	open, width := -1, 0
	for i, l := range lines {
		if m := fenceOpenRe.FindStringSubmatch(l); m != nil {
			open, width = i, len(m[1])
			break
		}
	}
	if open < 0 {
		if strings.Contains(reply, "```") {
			return fenceFramingUnterminated, ""
		}
		return fenceFramingAbsent, ""
	}
	closeAt := -1
	for i := len(lines) - 1; i > open; i-- {
		if m := fenceCloseRe.FindStringSubmatch(lines[i]); m != nil && len(m[1]) >= width {
			closeAt = i
			break
		}
	}
	if closeAt < 0 {
		return fenceFramingUnterminated, ""
	}
	for i := open + 1; i < closeAt; i++ {
		if m := fenceCloseRe.FindStringSubmatch(lines[i]); m != nil && len(m[1]) >= width {
			return fenceFramingAmbiguous, ""
		}
	}
	body := strings.Join(lines[open+1:closeAt], "\n")
	if strings.TrimSpace(body) == "" {
		return fenceFramingUnterminated, ""
	}
	return fenceFramingComplete, body + "\n"
}

// extractFencedContent returns the file body of a fenced reply, or "" unless
// the framing proved a single complete block.
func extractFencedContent(reply string) string {
	if framing, body := parseFencedReply(reply); framing == fenceFramingComplete {
		return body
	}
	return ""
}

// fencedRetryNote is what the sub-call is told after a reply that did not
// yield a file, naming the actual framing problem.
func fencedRetryNote(framing fenceFraming, tag string) string {
	if framing == fenceFramingAmbiguous {
		return fmt.Sprintf("[system note]: That reply had more than one fence closing the block, so "+
			"which lines are the file is ambiguous. Reply with exactly ONE fenced block containing the "+
			"complete file and nothing else. If the file itself contains ``` lines, open and close the "+
			"block with four backticks (````%s ... ````).", tag)
	}
	return fmt.Sprintf("[system note]: That had no fenced block. Reply with ONE ```%s fenced block "+
		"containing the complete file, nothing else.", tag)
}

// resolveInlineFencedBody decides whether the bytes the model inlined after
// the "@fenced" sentinel may be used as the file.
//
// ok is true only when framing proved the emission finished. Otherwise the
// caller falls back to the sub-call -- the channel built to carry a file
// body -- and why names the framing truthfully for the log.
func resolveInlineFencedBody(inline string) (body string, ok bool, why fenceFraming) {
	framing, resolved := classifyFencedPayload(inline)
	if framing == fenceFramingComplete {
		return resolved, true, framing
	}
	return "", false, framing
}

// isFencedSentinel reports whether write_file content is the "@fenced"
// sentinel: exactly "@fenced", or "@fenced" followed by a line break that
// introduces an inline fenced body. Content that merely begins with those
// characters is file content -- measured: `@fenced_route("/x")`, the first line
// of a Python module, opened a fenced sub-call and the file was never written.
func isFencedSentinel(content string) bool {
	t := strings.TrimSpace(content)
	if !strings.HasPrefix(t, "@fenced") {
		return false
	}
	rest := strings.TrimLeft(t[len("@fenced"):], " \t")
	return rest == "" || strings.HasPrefix(rest, "\n") || strings.HasPrefix(rest, "\r\n")
}
