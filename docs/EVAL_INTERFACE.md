# The held-out evaluation interface

The capability and reliability proofs (#242, #243) run a held-out suite. A
separate evaluation session writes the suite and runs it. The development
side builds only the generic pieces in `scripts/eval/` and never sees the
tasks. This page is the contract between the two sides. The decisions behind
it are recorded on #238 (the suite) and #242 (the baseline).

## The suite

The suite lives in a private repository and never enters this one.

```
suite.json             suite id, version, and per task: its id and the SHA-256 of every file
tasks/<id>/
  task.json            {"id", "mode", "runtime", "network", "grader_timeout_s"}
  prompt.md            the request, sent verbatim as the user's message
  seed/                the workspace before the run (may be empty)
  grade                executable: grades a finished workspace
  grader/              optional: data the grader reads, mounted read-only at /grader
  controls/pass/       overlay on seed/ that the grader must pass
  controls/fail/       overlay on seed/ that the grader must fail
```

- `mode` is `work` or `question`: the task contract's `task_mode`.
- `runtime` is `python3.13`, `go` or `node`: a runtime the sandbox image has.
- The driver refuses a suite whose files differ from `suite.json`, including
  a file added after the freeze.

## The grader

- It runs on a copy of the finished workspace, in a throwaway container from
  the sandbox image. The container has no network, runs as the caller's uid,
  and is stopped after `grader_timeout_s`.
- It is called as `/grade /w`, with the copy at `/w` as its cwd, and reads
  only the workspace.
- Exit 0 means pass and exit 1 means fail. Any other exit, or a timeout, is a
  grader error, counted apart. Its first line of output is kept as the reason.
- `driver.py check` refuses the suite unless every grader passes
  `controls/pass` and fails `controls/fail`.

## The runs

- **The arms.**
  - `atlas`: a session through `POST /v1/agent`, as the TUI sends it.
  - `baseline`: the same model and steering through a minimal read, write and
    run loop, with ATLAS's sampling, per-turn ceiling and command limits, and
    no V3, lens, gates or grammar. The baseline's full specification is on #242.
- **The budget.** The budget is one per suite: the eval stack's session
  timeout (600 s by default). ATLAS takes no per-request budget, so a per-task
  budget could not apply to both arms. `--budget-s` must equal the stack's
  setting, and the baseline gets the same.
- **A stack of its own.** A run refuses the development project (`atlas`),
  and a stack whose v3-service writes V3 pool captures. Session files, event
  logs and V3 telemetry stay in the eval stack's own volumes.
- **One commit.** A run refuses a stack whose five images are not the ones
  its gated deploy recorded for the stated commit (#241). The commit, the
  images, the grammar mode and the lens and steering state go into every
  record.
- **Who runs it.** The evaluation session runs the driver and keeps the
  records, outside anything the development side reads. The maintainer
  schedules GPU time, so the two sides never share the GPU.

## Commands

```
scripts/eval/driver.py check SUITE --image SANDBOX_IMAGE
scripts/eval/driver.py run SUITE --arm atlas --out atlas.jsonl --image SANDBOX_IMAGE \
    --compose-project atlaseval --workspace-root /path/the/stack/mounts --tasks t01,t02,...
scripts/eval/driver.py run SUITE --arm baseline --out baseline.jsonl ...same options...
scripts/eval/driver.py report atlas.jsonl --against baseline.jsonl
```

`report` prints aggregates only, per arm:
- the pass rate with a Wilson 95% interval;
- completed-but-failed over completed (false completion);
- passed-but-not-completed;
- failure endings by status and reason;
- grader errors;
- the number of tasks whose repeats disagree;
- ATLAS's pass rate minus the baseline's, with Newcombe's interval.

It names no task and quotes no task text, so only this output goes back to
development.
