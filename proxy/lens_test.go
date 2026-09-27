package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func lensHealthServer(t *testing.T, lens map[string]any) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"status":     "healthy",
			"subsystems": map[string]any{"lens": lens},
		})
	}))
}

func compatibleLensHealth() map[string]any {
	return map[string]any{
		"enabled":           true,
		"cost_field_loaded": true,
		"cost_field_dim":    3840,
		"embed_dim":         3840,
		"gx_loaded":         true,
		"cx_calibrated":     true,
		"gx_calibrated":     true,
		"self_test_pass":    true,
	}
}

func TestProbeLensStatusRequiresSelectedModelCalibration(t *testing.T) {
	health := compatibleLensHealth()
	health["cx_calibrated"] = false
	srv := lensHealthServer(t, health)
	defer srv.Close()

	got := probeLensStatus(context.Background(), srv.URL)
	if got.Verdict != "uncalibrated" {
		t.Fatalf("verdict = %q, want uncalibrated", got.Verdict)
	}
}

func TestProbeLensStatusSurfacesArtifactIdentityMismatch(t *testing.T) {
	health := compatibleLensHealth()
	health["cost_field_loaded"] = false
	health["self_test_error"] = "artifacts are for model-a, selected model is model-b"
	srv := lensHealthServer(t, health)
	defer srv.Close()

	got := probeLensStatus(context.Background(), srv.URL)
	if got.Hint != health["self_test_error"] {
		t.Fatalf("hint = %q, want identity mismatch", got.Hint)
	}
}

func TestProbeLensStatusRequiresCompleteArtifacts(t *testing.T) {
	health := compatibleLensHealth()
	health["gx_loaded"] = false
	srv := lensHealthServer(t, health)
	defer srv.Close()

	got := probeLensStatus(context.Background(), srv.URL)
	if got.Verdict != "incomplete-artifacts" {
		t.Fatalf("verdict = %q, want incomplete-artifacts", got.Verdict)
	}
}

func TestProbeLensStatusSupportsCalibratedMatchingArtifacts(t *testing.T) {
	srv := lensHealthServer(t, compatibleLensHealth())
	defer srv.Close()

	got := probeLensStatus(context.Background(), srv.URL)
	if got.Verdict != "supported" {
		t.Fatalf("verdict = %q, want supported (%s)", got.Verdict, got.Hint)
	}
}

func TestProbeASAStatusRequiresMatchingModelMarker(t *testing.T) {
	dir := t.TempDir()
	vector := filepath.Join(dir, "ast_edit_steering.gguf")
	if err := os.WriteFile(vector, []byte("vector"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("ATLAS_CONTROL_VECTOR", vector)
	t.Setenv("ATLAS_MODEL_NAME", "selected-model")

	if got := probeASAStatus(); got.Verdict != "unverified" {
		t.Fatalf("without marker verdict = %q, want unverified", got.Verdict)
	}
	if err := os.WriteFile(vector+".model", []byte("other-model\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := probeASAStatus(); got.Verdict != "incompatible" {
		t.Fatalf("wrong marker verdict = %q, want incompatible", got.Verdict)
	}
	if err := os.WriteFile(vector+".model", []byte("selected-model\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := probeASAStatus(); got.Verdict != "active" {
		t.Fatalf("matching marker verdict = %q, want active", got.Verdict)
	}
}

// Threshold resolution is fail-closed: only the selected model's calibrated
// values may drive interventions.
func TestLensThresholdResolution(t *testing.T) {
	var bare lensPerStepResult
	if _, _, ok := bare.calibratedThresholds(); ok {
		t.Fatal("missing calibration must not produce intervention thresholds")
	}
	withT := lensPerStepResult{Thresholds: &lensThresholds{OffRails: 0.6, Low: 0.45, Severe: 0.3}}
	low, severe, ok := withT.calibratedThresholds()
	if !ok || low != 0.45 || severe != 0.3 {
		t.Fatalf("calibrated thresholds = (%v, %v, %v)", low, severe, ok)
	}
	invalid := lensPerStepResult{Thresholds: &lensThresholds{Low: 0.2, Severe: 0.3}}
	if _, _, ok := invalid.calibratedThresholds(); ok {
		t.Fatal("severe > low must be rejected")
	}
}

// The same scores are interpreted only against the selected model's own
// calibration.
func TestAgentLensRegressionUsesPerModelThresholds(t *testing.T) {
	scores := []float64{0.40, 0.39}
	if _, fired := agentLensRegression(scores, 0.45, 0.30); !fired {
		t.Errorf("model-calibrated thresholds should fire on a 0.40/0.39 run")
	}
}

// Severe single-write short-circuit also honors the per-model severe value.
func TestAgentLensRegressionSevereIsPerModel(t *testing.T) {
	// One write at 0.32. Below a model severe of 0.35 → immediate fire.
	if _, fired := agentLensRegression([]float64{0.32}, 0.45, 0.35); !fired {
		t.Errorf("single write below per-model severe should fire")
	}
	// Same score under another valid calibration does not fire immediately.
	if _, fired := agentLensRegression([]float64{0.32}, 0.2, 0.1); fired {
		t.Errorf("single 0.32 write should not fire above calibrated severe=0.1")
	}
}

func dimByName(dims []StatusDimension, name string) StatusDimension {
	for _, d := range dims {
		if d.Name == name {
			return d
		}
	}
	return StatusDimension{}
}

func TestBuildDimensionsSevenRows(t *testing.T) {
	dims := buildDimensions(LensStatus{}, ASAStatus{Verdict: "missing"})
	want := []string{"model_runtime", "direct_agent", "lens_identity",
		"lens_scoring", "lens_calibration", "lens_intervention", "asa"}
	if len(dims) != len(want) {
		t.Fatalf("expected %d dimensions, got %d", len(want), len(dims))
	}
	for i, n := range want {
		if dims[i].Name != n {
			t.Errorf("dimension %d = %q, want %q", i, dims[i].Name, n)
		}
	}
}

func TestDirectAgentAlwaysSupported(t *testing.T) {
	// Even with a fully disabled lens, the direct agent is model-agnostic.
	dims := buildDimensions(LensStatus{Verdict: "unreachable"},
		ASAStatus{Verdict: "missing"})
	if d := dimByName(dims, "direct_agent"); d.Status != "supported" {
		t.Fatalf("direct_agent should always be supported, got %q", d.Status)
	}
}

func TestInterventionNeutralWhenUncalibrated(t *testing.T) {
	// Loaded + scoring available but NOT calibrated → intervention must
	// be "neutral" (never "active"), matching the runtime guarantee.
	lens := LensStatus{
		Verdict: "uncalibrated", CostFieldLoaded: true, GxLoaded: true,
		CostFieldDim: 3840, EmbedDim: 3840,
		CxCalibrated: false, GxCalibrated: false,
	}
	dims := buildDimensions(lens, ASAStatus{Verdict: "missing"})
	if d := dimByName(dims, "lens_calibration"); d.Status != "uncalibrated" {
		t.Errorf("calibration = %q, want uncalibrated", d.Status)
	}
	if d := dimByName(dims, "lens_intervention"); d.Status != "neutral" {
		t.Fatalf("intervention = %q, want neutral when uncalibrated", d.Status)
	}
}

func TestInterventionActiveOnlyWhenCalibrated(t *testing.T) {
	lens := LensStatus{
		Verdict: "supported", CostFieldLoaded: true, GxLoaded: true,
		CostFieldDim: 3840, EmbedDim: 3840,
		CxCalibrated: true, GxCalibrated: true,
	}
	dims := buildDimensions(lens, ASAStatus{Verdict: "supported"})
	if d := dimByName(dims, "lens_intervention"); d.Status != "active" {
		t.Fatalf("intervention = %q, want active when calibrated", d.Status)
	}
}

func TestInterventionDisabledWhenNoArtifacts(t *testing.T) {
	dims := buildDimensions(LensStatus{Verdict: "no-artifacts"},
		ASAStatus{Verdict: "missing"})
	if d := dimByName(dims, "lens_intervention"); d.Status != "disabled" {
		t.Fatalf("intervention = %q, want disabled with no artifacts", d.Status)
	}
}

func TestDimMismatchSurfaced(t *testing.T) {
	lens := LensStatus{
		Verdict: "dim-mismatch", CostFieldLoaded: true,
		CostFieldDim: 4096, EmbedDim: 3840,
	}
	dims := buildDimensions(lens, ASAStatus{Verdict: "missing"})
	if d := dimByName(dims, "lens_identity"); d.Status != "dim-mismatch" {
		t.Fatalf("identity = %q, want dim-mismatch", d.Status)
	}
}
