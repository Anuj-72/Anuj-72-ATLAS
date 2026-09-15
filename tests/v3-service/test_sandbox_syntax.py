import importlib.util
import sys
from pathlib import Path

import pytest

from tests.conftest import run_shell_sync
from fastapi import HTTPException

SANDBOX_DIR = Path(__file__).parents[2] / "sandbox"


def _load_sandbox_module():
    # executor_server imports its sibling `structured_log`, which only
    # resolves when sandbox/ is importable. In the container that holds
    # because the module runs from its own directory; loading it by path
    # from the test suite does not, so put the directory on sys.path
    # first. Without this every test here fails at import with
    # ModuleNotFoundError before reaching an assertion.
    if str(SANDBOX_DIR) not in sys.path:
        sys.path.insert(0, str(SANDBOX_DIR))
    module_path = SANDBOX_DIR / "executor_server.py"
    spec = importlib.util.spec_from_file_location("atlas_sandbox_executor", module_path)
    module = importlib.util.module_from_spec(spec)
    # Register before exec: executor_server defers annotation evaluation, so
    # pydantic resolves model field types by looking the module up in
    # sys.modules. Loading by path without registering leaves it absent and
    # every model raises "is not fully defined". In the container the module
    # runs as __main__ and is registered, so this only bites by-path loads.
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except Exception:
        sys.modules.pop(spec.name, None)
        raise
    return module


def test_json_syntax_check_accepts_valid_document(tmp_path):
    sandbox = _load_sandbox_module()

    assert sandbox._syntax_check_impl("json", '{"ready": true}', tmp_path) == []


def test_json_syntax_check_rejects_invalid_document(tmp_path):
    sandbox = _load_sandbox_module()

    errors = sandbox._syntax_check_impl("json", '{"ready": }', tmp_path)

    assert errors


def test_xml_syntax_check_rejects_invalid_document(tmp_path):
    sandbox = _load_sandbox_module()

    errors = sandbox._syntax_check_impl("xml", "<root>", tmp_path)

    assert errors


def test_xml_syntax_check_rejects_entity_expansion(tmp_path):
    sandbox = _load_sandbox_module()
    document = """<!DOCTYPE bomb [
      <!ENTITY a "1234567890">
      <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
    ]><root>&b;</root>"""

    errors = sandbox._syntax_check_impl("xml", document, tmp_path)

    assert errors


def test_overlay_write_rejects_symlink_leaf(tmp_path):
    sandbox = _load_sandbox_module()
    root = tmp_path / "snapshot"
    root.mkdir()
    outside = tmp_path / "outside.txt"
    outside.write_text("do not overwrite")
    (root / "candidate.py").symlink_to(outside)

    with pytest.raises(HTTPException):
        sandbox._write_overlay_files(root, {"candidate.py": "attacker content"})

    assert outside.read_text() == "do not overwrite"


def test_overlay_write_rejects_symlink_parent(tmp_path):
    sandbox = _load_sandbox_module()
    root = tmp_path / "snapshot"
    root.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    (root / "src").symlink_to(outside, target_is_directory=True)

    with pytest.raises(HTTPException):
        sandbox._write_overlay_files(root, {"src/candidate.py": "attacker content"})

    assert not (outside / "candidate.py").exists()


def test_shell_overlay_runs_without_mutating_workspace(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    original = workspace / "app.py"
    original.write_text("raise RuntimeError('real workspace should not run')\n")

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        response = run_shell_sync(sandbox, sandbox.ShellRequest(
                command="python3 -m py_compile app.py",
                cwd=str(workspace),
                files={"app.py": "print('candidate overlay')\n"},
            )
        )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base

    assert response.success is True
    assert original.read_text() == "raise RuntimeError('real workspace should not run')\n"


def test_shell_overlay_translates_absolute_workspace_paths(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    original = workspace / "app.py"
    original.write_text("def broken(:\n")

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        response = run_shell_sync(sandbox, sandbox.ShellRequest(
                command=f"python3 -m py_compile {workspace}/app.py",
                cwd=str(workspace),
                files={"app.py": "print('candidate overlay')\n"},
            )
        )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base

    assert response.success is True
    assert original.read_text() == "def broken(:\n"


def test_shell_overlay_rejects_path_traversal(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        with pytest.raises(HTTPException):
            run_shell_sync(sandbox, sandbox.ShellRequest(
                    command="true",
                    cwd=str(workspace),
                    files={"../escape.py": "print('nope')\n"},
                )
            )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base


def test_shell_snapshot_skips_external_symlinks(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    outside = tmp_path / "outside.txt"
    outside.write_text("secret")
    (workspace / "link.txt").symlink_to(outside)

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        response = run_shell_sync(sandbox, sandbox.ShellRequest(
                command="test ! -e link.txt",
                cwd=str(workspace),
                files={"candidate.py": "print('ok')\n"},
            )
        )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base

    assert response.success is True


def test_shell_snapshot_preserves_safe_internal_symlinks(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "real.txt").write_text("inside")
    (workspace / "link.txt").symlink_to("real.txt")

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        response = run_shell_sync(sandbox, sandbox.ShellRequest(
                command="test -L link.txt && test \"$(cat link.txt)\" = inside",
                cwd=str(workspace),
                files={"candidate.py": "print('ok')\n"},
            )
        )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base

    assert response.success is True


def test_shell_snapshot_keeps_small_node_modules(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    package_dir = workspace / "node_modules" / "tiny"
    package_dir.mkdir(parents=True)
    (package_dir / "index.js").write_text("module.exports = 1;\n")

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        response = run_shell_sync(sandbox, sandbox.ShellRequest(
                command="test -f node_modules/tiny/index.js",
                cwd=str(workspace),
                files={"candidate.js": "console.log('ok')\n"},
            )
        )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base

    assert response.success is True


def test_shell_snapshot_skips_large_artifacts(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "model.gguf").write_text("large model placeholder")

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    try:
        response = run_shell_sync(sandbox, sandbox.ShellRequest(
                command="test ! -e model.gguf",
                cwd=str(workspace),
                files={"candidate.py": "print('ok')\n"},
            )
        )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base

    assert response.success is True


def test_shell_snapshot_fails_when_byte_limit_is_exceeded(tmp_path):
    sandbox = _load_sandbox_module()
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "small.txt").write_text("too many bytes for this test")

    previous_root = sandbox.WORKSPACE_ROOT
    previous_base = sandbox.WORKSPACE_BASE
    previous_limit = sandbox.SHELL_SNAPSHOT_MAX_BYTES
    sandbox.WORKSPACE_ROOT = workspace
    sandbox.WORKSPACE_BASE = tmp_path
    sandbox.SHELL_SNAPSHOT_MAX_BYTES = 4
    try:
        with pytest.raises(HTTPException) as exc:
            run_shell_sync(sandbox, sandbox.ShellRequest(
                    command="true",
                    cwd=str(workspace),
                    files={"candidate.py": "print('ok')\n"},
                )
            )
    finally:
        sandbox.WORKSPACE_ROOT = previous_root
        sandbox.WORKSPACE_BASE = previous_base
        sandbox.SHELL_SNAPSHOT_MAX_BYTES = previous_limit

    assert exc.value.status_code == 413


# --- Jinja template syntax (scenario C, 2026-09-15) -------------------------
#
# A Flask template `templates/index.html` shipped `{% for p in people %)` —
# `%)` instead of `%}`. html.parser passes it (all text to it), the server
# starts and the file imports, and GET / returns 500 with a jinja
# TemplateSyntaxError. The html branch now runs a Jinja parse, scoped to files
# that are actually templates so non-Jinja frameworks are never judged.

# The delivered line 58, verbatim (minus the leading whitespace).
C_BROKEN_ROW = (
    "<td> {% for p in people %){% if p.id == expense.paid_by_id %}"
    "{{ p.name }}{% endif %}{% endfor %}{% endfor %} </td>"
)
C_FIXED_ROW = C_BROKEN_ROW.replace("%)", "%}", 1)


def test_jinja_typo_in_a_template_html_is_reported(tmp_path):
    sandbox = _load_sandbox_module()
    errors = sandbox._syntax_check_impl(
        "html", "<table>" + C_BROKEN_ROW + "</table>", tmp_path,
        filename="templates/index.html")
    assert errors, "a `%)` tag typo in a template must be reported"
    assert any("TemplateSyntaxError" in e for e in errors), errors


def test_jinja_correct_template_passes(tmp_path):
    sandbox = _load_sandbox_module()
    errors = sandbox._syntax_check_impl(
        "html", "<table>" + C_FIXED_ROW + "</table>", tmp_path,
        filename="templates/index.html")
    assert errors == [], errors


def test_the_same_broken_bytes_outside_templates_are_not_judged(tmp_path):
    # Identical bytes under src/ (a Vue/Angular/component tree, not Jinja) must
    # NOT be handed to a Jinja parser.
    sandbox = _load_sandbox_module()
    errors = sandbox._syntax_check_impl(
        "html", "<table>" + C_BROKEN_ROW + "</table>", tmp_path,
        filename="src/components/list.html")
    assert errors == [], errors


def test_vue_interpolation_under_templates_is_not_judged(tmp_path):
    # `{{ a || b }}` is valid Vue and invalid Jinja, but with no `{%` statement
    # tag it is not attributed to Jinja.
    sandbox = _load_sandbox_module()
    errors = sandbox._syntax_check_impl(
        "html", "<p>{{ user?.name || 'x' }}</p>", tmp_path,
        filename="templates/widget.html")
    assert errors == [], errors


def test_unknown_jinja_extension_tag_is_not_a_false_positive(tmp_path):
    # A third-party extension tag this parser hasn't loaded is not a typo.
    sandbox = _load_sandbox_module()
    errors = sandbox._syntax_check_impl(
        "html", "{% cache 60 %}<p>hi</p>{% endcache %}", tmp_path,
        filename="templates/page.html")
    assert errors == [], errors


def test_jinja_extension_named_file_is_checked(tmp_path):
    sandbox = _load_sandbox_module()
    errors = sandbox._syntax_check_impl(
        "html", "{% for x in y %) {% endfor %}", tmp_path,
        filename="emails/welcome.jinja2")
    assert any("TemplateSyntaxError" in e for e in errors), errors


# --- subdirectory source files (audit finding, 2026-09-15) ------------------
#
# Once the proxy started sending the real file path, a gated source file in a
# subdirectory (src/app.py, static/app.js) hit a language branch that writes
# the check file to disk WITHOUT creating the parent dir -> FileNotFoundError,
# reported as an unparseable file, refusing a legitimate write.

def test_python_syntax_check_handles_a_subdirectory_path(tmp_path):
    sandbox = _load_sandbox_module()
    # Valid Python in a subdirectory must check clean, not FileNotFoundError.
    assert sandbox._syntax_check_impl(
        "python", "x = 1\n", tmp_path, filename="src/pkg/app.py") == []
    # And a real error is still reported (not masked by a write failure).
    errs = sandbox._syntax_check_impl(
        "python", "def f(\n", tmp_path, filename="src/pkg/app.py")
    assert errs and any("line" in e.lower() or "syntax" in e.lower() for e in errs), errs


def test_javascript_syntax_check_handles_a_subdirectory_path(tmp_path):
    sandbox = _load_sandbox_module()
    assert sandbox._syntax_check_impl(
        "javascript", "const x = 1;\n", tmp_path, filename="static/js/app.js") == []


# --- the Jinja checker's tool dependency must be baked (audit, 2026-09-15) ---
#
# _jinja_template_errors does `import jinja2`; jinja2 is NOT a user app lib the
# sandbox installs per project, so it must be a baked CHECKER tool. Without it
# the check fails open and every broken template ships. This ties the code's
# import to the image's requirements so the gate cannot silently go inert.

def test_jinja2_is_a_baked_sandbox_verify_dependency():
    req = (SANDBOX_DIR / "requirements-verify.txt").read_text().lower()
    assert "jinja2" in req, (
        "jinja2 must be in sandbox/requirements-verify.txt — the Jinja template "
        "syntax check imports it, and without it the check fails open")
