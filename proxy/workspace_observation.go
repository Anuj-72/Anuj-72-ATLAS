package main

import (
	"log"
	"os"
	"path/filepath"
	"strings"
)

// What a shell command did to the workspace.
//
// The file tools record every landing in the deliverable ledger, and the
// completion check reads the ledger. A shell command reached it only through
// invalidateTrackedValidation, which rehashes paths the ledger ALREADY
// tracked. So a module written with `cat > tool.py <<EOF`, broken, was never
// checked and the run ended completed; a user's file removed with `rm` left
// no tombstone, and the run ended completed over the deletion (audit
// G-tool-parity#1, both reproduced through the loop).
//
// The proxy reads the same workspace the sandbox runs in, so it observes the
// change itself: a stat walk before the command and one after. Created or
// changed files of a kind the completion check can judge enter the ledger as
// the session's own work. A file that was here when the session started and
// that a command removed is a deletion nobody approved, and is tombstoned.
// A file the session itself created and then removed is nobody's loss.
// Dependency, cache and build directories are not walked: pip, npm, pytest
// and a build rewrite them wholesale, and nothing in them is a deliverable.

// workspaceFile is what a stat walk sees of one file. Stat-only by design:
// two walks per command have to stay cheap. A rewrite that keeps both size
// and modification time is the one change this misses.
type workspaceFile struct {
	size  int64
	mtime int64
}

// workspaceSnapshot maps workspace-relative paths to what the walk saw.
// truncated says the walk stopped at maxObservedWorkspaceFiles and describes
// only part of the workspace.
type workspaceSnapshot struct {
	files     map[string]workspaceFile
	truncated bool
	taken     bool
}

const maxObservedWorkspaceFiles = 20000

// observeSkipDirs are rewritten wholesale by installs, test runs and builds.
var observeSkipDirs = map[string]bool{
	".git": true, "node_modules": true, ".venv": true, "venv": true,
	"__pycache__": true, ".tox": true, ".mypy_cache": true, ".pytest_cache": true,
	".ruff_cache": true, "dist": true, "build": true, "target": true, ".next": true,
	".idea": true, ".vscode": true, ".gradle": true,
}

// observeSkipFile reports files that are by-products, not work: bytecode,
// coverage data, Finder metadata, and the proxy's own markers.
func observeSkipFile(name string) bool {
	switch {
	case name == ".DS_Store", name == ".coverage", strings.HasPrefix(name, ".atlas"):
		return true
	case strings.HasSuffix(name, ".pyc"), strings.HasSuffix(name, ".pyo"):
		return true
	}
	return false
}

// snapshotWorkspace walks root and records every file's size and mtime.
func snapshotWorkspace(root string) workspaceSnapshot {
	snap := workspaceSnapshot{files: map[string]workspaceFile{}, taken: true}
	if root == "" {
		return snap
	}
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil // an unreadable entry: skip it, the walk goes on
		}
		if d.IsDir() {
			if path != root && observeSkipDirs[d.Name()] {
				return filepath.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() || observeSkipFile(d.Name()) {
			return nil
		}
		if len(snap.files) >= maxObservedWorkspaceFiles {
			snap.truncated = true
			return filepath.SkipAll
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return nil
		}
		snap.files[filepath.ToSlash(rel)] = workspaceFile{size: info.Size(), mtime: info.ModTime().UnixNano()}
		return nil
	})
	return snap
}

// shellDeliverable reports a file the completion check can judge: one the
// syntax registry covers, or a prose document. A shell command that writes
// data, logs or build output makes nothing the run has to demonstrate.
func shellDeliverable(rel string) bool {
	if isDocumentAsset(rel) {
		return true
	}
	_, gated := syntaxGateLanguages[strings.ToLower(filepath.Ext(rel))]
	return gated
}

// applyShellChanges records in the ledger what one shell command changed.
func applyShellChanges(ctx *AgentContext, before, after workspaceSnapshot) {
	if ctx == nil || ctx.WorkingDir == "" {
		return
	}
	if before.truncated || after.truncated {
		// Part of the workspace went unobserved. Not a failure of the work,
		// but completion cannot say it saw what the shell did.
		ctx.ShellEffectsUnobserved = true
	}
	for rel, now := range after.files {
		if was, existed := before.files[rel]; existed && was == now {
			continue
		}
		if !shellDeliverable(rel) || ledgerTracks(ctx, rel) {
			// Tracked paths were rehashed by invalidateTrackedValidation.
			continue
		}
		log.Printf("[ledger] %s written by a shell command — now a deliverable of this session", rel)
		observePathFromDisk(ctx, rel, ValidationKindUnknown, ValidationUnknown, "run_command")
	}
	if before.truncated || after.truncated {
		return // an absence in a partial walk is not a removal
	}
	for rel := range before.files {
		if _, still := after.files[rel]; still {
			continue
		}
		// Without a complete picture of the start, assume the user's.
		preexisting := !ctx.InitialWorkspace.taken || ctx.InitialWorkspace.truncated
		if _, ok := ctx.InitialWorkspace.files[rel]; ok {
			preexisting = true
		}
		if preexisting {
			log.Printf("[ledger] %s was here before this session and a shell command removed it", rel)
			tombstoneDeliverable(ctx, rel, "deleted:shell")
			continue
		}
		// The session made it and the session removed it: nothing of the
		// user's changed, and there is nothing left to deliver.
		key := ledgerKey(ctx, rel)
		ctx.LedgerMu.Lock()
		delete(ctx.Ledger, key)
		ctx.LedgerMu.Unlock()
	}
}
