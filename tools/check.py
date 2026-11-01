#!/usr/bin/env python3
"""Behaviour checks for the text-file atom.

Each check drives the atom the way the runner does -- input.json in, output.json out, the workspace as the process
cwd -- and then reads the file back from disk with Python, byte for byte. Every expectation is recomputed here from
the check's own inputs (the separator rules, the ${VAR} expansion, the sha256), so a check cannot pass because the atom
and the test share an assumption.

    python3 tools/check.py                     # every check
    python3 tools/check.py overwrite           # only checks whose name contains that
    python3 tools/check.py --jar X             # run a mutated build
    python3 tools/check.py --list
"""
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAR = os.path.join(REPO, "dist", "text-file", "text-file.jar")
JAVA = os.path.join(os.environ.get("JAVA_HOME", "/Users/tangming/tools/jdk-21.0.12.1+1/Contents/Home"), "bin", "java")
TASK = json.load(open(os.path.join(REPO, "task.json"), encoding="utf-8"))

# the JVM's own default; the atom uses System.lineSeparator() when lineSeparator=platform
PLATFORM = os.linesep

CHECKS = []


def check(name):
    def register(fn):
        CHECKS.append((name, fn))
        return fn
    return register


class Failed(AssertionError):
    pass


def expect(condition, message):
    if not condition:
        raise Failed(message)


def base_input(**overrides):
    data = {name: spec.get("default", "") for name, spec in TASK["input"].items()}
    data.update({"JOB_NAME": "textfile", "BUILD_NUMBER": "7"})
    data.update(overrides)
    return data


def run(overrides, files=None, env_extra=None, drop=()):
    """Run the atom in a fresh workspace; return (workspace, output dict, process)."""
    workspace = tempfile.mkdtemp(prefix="text-file-check-")
    for relative, payload in (files or {}).items():
        path = os.path.join(workspace, relative)
        parent = os.path.dirname(path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(path, "wb") as handle:
            handle.write(payload)
    data = base_input(**overrides)
    for name in drop:
        data.pop(name, None)
    with open(os.path.join(workspace, "input.json"), "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False)
    env = dict(os.environ)
    env.update({"atom_data_dir": workspace, "atom_data_input": "input.json",
                "atom_data_output": "output.json", "atom_workspace": workspace})
    env.update(env_extra or {})
    proc = subprocess.run([JAVA, "-cp", JAR, "com.jenkinschina.atom.sdk.AtomRunner"],
                          cwd=workspace, env=env, capture_output=True, text=True, timeout=180)
    output_path = os.path.join(workspace, "output.json")
    if not os.path.exists(output_path):
        raise Failed("the atom wrote no output.json (exit %s)\nstdout:\n%s\nstderr:\n%s"
                     % (proc.returncode, proc.stdout, proc.stderr))
    with open(output_path, encoding="utf-8") as handle:
        return workspace, json.load(handle), proc


def execute(workspace, overrides, env_extra=None):
    """Write input.json into an existing workspace and run the atom there."""
    data = base_input(**overrides)
    with open(os.path.join(workspace, "input.json"), "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False)
    env = dict(os.environ)
    env.update({"atom_data_dir": workspace, "atom_data_input": "input.json",
                "atom_data_output": "output.json", "atom_workspace": workspace})
    env.update(env_extra or {})
    proc = subprocess.run([JAVA, "-cp", JAR, "com.jenkinschina.atom.sdk.AtomRunner"],
                          cwd=workspace, env=env, capture_output=True, text=True, timeout=180)
    with open(os.path.join(workspace, "output.json"), encoding="utf-8") as handle:
        return json.load(handle), proc


def field(output, name):
    node = output.get("data", {}).get(name)
    if isinstance(node, dict):
        return node.get("value")
    return node


def read(workspace, relative):
    with open(os.path.join(workspace, relative), "rb") as handle:
        return handle.read()


def expect_ok(output, proc):
    expect(output.get("status") == "success",
           "expected success, got %s / %s: %s\n%s" % (output.get("status"), output.get("errorCode"),
                                                      output.get("message"), proc.stdout[-2000:]))


def expect_error(output, code):
    expect(output.get("status") == "error", "expected an error with %s, got %s (%s)"
           % (code, output.get("status"), output.get("message")))
    expect(output.get("errorCode") == code, "expected errorCode %s, got %s (%s)"
           % (code, output.get("errorCode"), output.get("message")))


def expect_bytes(workspace, relative, expected, label):
    actual = read(workspace, relative)
    if actual != expected:
        raise Failed("%s: expected %r, file holds %r" % (label, expected, actual))


# --------------------------------------------------------------------------- the three write options

@check("create.new.file")
def create_new_file():
    workspace, out, proc = run({"path": "out/new.txt", "content": "hello\nworld", "option": "appendToEnd"})
    expect_ok(out, proc)
    expect_bytes(workspace, "out/new.txt", b"hello\nworld", "a new file is written with the content as given")
    expect(field(out, "created") == "true", "created must be true for a new file, got %r" % field(out, "created"))
    expect(field(out, "path") == os.path.join(workspace, "out/new.txt"),
           "path output must be absolute, got %r" % field(out, "path"))


@check("create.parent.directories")
def create_parent_directories():
    workspace, out, proc = run({"path": "a/b/c/deep.txt", "content": "x"})
    expect_ok(out, proc)
    expect_bytes(workspace, "a/b/c/deep.txt", b"x", "missing parent directories are created (FilePath.write mkdirs)")


@check("append.without.trailing.separator")
def append_without_trailing_separator():
    workspace, out, proc = run({"path": "log.txt", "content": "second", "option": "appendToEnd"},
                               files={"log.txt": b"first"})
    expect_ok(out, proc)
    expect_bytes(workspace, "log.txt", "first".encode() + PLATFORM.encode() + b"second",
                 "the separator is inserted when the old file does not end with one")


@check("append.with.trailing.separator")
def append_with_trailing_separator():
    workspace, out, proc = run({"path": "log.txt", "content": "second", "option": "appendToEnd"},
                               files={"log.txt": b"first\n"})
    expect_ok(out, proc)
    expect_bytes(workspace, "log.txt", b"first\nsecond",
                 "no second separator when the old file already ends with one")


@check("append.crlf.old.file.keeps.crlf")
def append_crlf_old_file():
    workspace, out, proc = run({"path": "log.txt", "content": "second", "option": "appendToEnd"},
                               files={"log.txt": b"a\r\nb\r\n"})
    expect_ok(out, proc)
    # endsWith("\n") is true for a CRLF ending, so nothing is inserted; and CRLF is not doubled
    expect_bytes(workspace, "log.txt", b"a\r\nb\r\nsecond",
                 "a CRLF ending counts as ending with the separator, and CR stays single")


@check("append.crlf.old.file.bare.lf")
def append_crlf_old_bare_lf():
    workspace, out, proc = run({"path": "log.txt", "content": "line3", "option": "appendToEnd"},
                               files={"log.txt": b"line1\nline2"})
    expect_ok(out, proc)
    expect_bytes(workspace, "log.txt", b"line1\nline2" + PLATFORM.encode() + b"line3",
                 "bare LF is rewritten to the platform separator only when the separator is chosen explicitly")


@check("insert.without.leading.separator")
def insert_without_leading_separator():
    workspace, out, proc = run({"path": "f.txt", "content": "head", "option": "insertAtStart"},
                               files={"f.txt": b"body"})
    expect_ok(out, proc)
    expect_bytes(workspace, "f.txt", b"head" + PLATFORM.encode() + b"body",
                 "the separator goes between the new content and the old body")


@check("insert.with.leading.separator")
def insert_with_leading_separator():
    workspace, out, proc = run({"path": "f.txt", "content": "head", "option": "insertAtStart"},
                               files={"f.txt": b"\nbody\nmore"})
    expect_ok(out, proc)
    expect_bytes(workspace, "f.txt", b"head\nbody\nmore",
                 "nothing is inserted when the old file already starts with a separator")


@check("overwrite.replaces.all")
def overwrite_replaces_all():
    workspace, out, proc = run({"path": "f.txt", "content": "new", "option": "overWrite"},
                               files={"f.txt": b"a much longer old body\nwith lines\n"})
    expect_ok(out, proc)
    expect_bytes(workspace, "f.txt", b"new", "overWrite keeps only the new content")
    expect(field(out, "created") == "false", "created must be false when the file existed")


@check("overwrite.accepts.empty.content")
def overwrite_empty():
    workspace, out, proc = run({"path": "f.txt", "content": "", "option": "overWrite"},
                               files={"f.txt": b"stuff"})
    expect_ok(out, proc)
    expect_bytes(workspace, "f.txt", b"", "empty content with overWrite empties the file")
    expect(out.get("message") is None, "success must not carry an error message")


@check("empty.content.refused.for.append")
def empty_content_refused():
    workspace, out, proc = run({"path": "f.txt", "content": "", "option": "appendToEnd"},
                               files={"f.txt": b"keep"})
    expect_error(out, 40009)
    expect_bytes(workspace, "f.txt", b"keep", "a refused step must not touch the file")


# --------------------------------------------------------------------------- separators as an input

@check("line.separator.crlf.appends.crlf")
def separator_crlf():
    workspace, out, proc = run({"path": "win.bat", "content": "call b.bat", "lineSeparator": "crlf"},
                               files={"win.bat": b"@echo off"})
    expect_ok(out, proc)
    expect_bytes(workspace, "win.bat", b"@echo off\r\ncall b.bat", "crlf is used between the old body and the content")
    expect(field(out, "lineSeparator") == "crlf", "the output names the separator actually used")


@check("line.separator.crlf.rewrites.bare.lf.only")
def separator_crlf_bare_lf():
    workspace, out, proc = run({"path": "mixed.txt", "content": "end", "lineSeparator": "crlf"},
                               files={"mixed.txt": b"one\ntwo\r\nthree"})
    expect_ok(out, proc)
    # the plugin's replaceAll over the whole result doubles the CR of every existing CRLF; bare LF alone is rewritten
    expect_bytes(workspace, "mixed.txt", b"one\r\ntwo\r\nthree\r\nend",
                 "bare LF becomes CRLF, an existing CRLF stays CRLF")


@check("line.separator.lf.on.crlf.file")
def separator_lf():
    workspace, out, proc = run({"path": "f.txt", "content": "b", "lineSeparator": "lf"},
                               files={"f.txt": b"a\r\n"})
    expect_ok(out, proc)
    expect_bytes(workspace, "f.txt", b"a\r\nb", "lf is a no-op on a file that already ends with CRLF")
    expect(field(out, "lineSeparator") == "lf", "output must say lf")


@check("line.separator.unknown")
def separator_unknown():
    workspace, out, proc = run({"path": "f.txt", "content": "x", "lineSeparator": "crlf-crlf"})
    expect_error(out, 40001)
    expect("lineSeparator" in (out.get("message") or ""), "the message names the input at fault")
    expect(not os.path.exists(os.path.join(workspace, "f.txt")), "nothing is written on an input error")


# --------------------------------------------------------------------------- inputs, paths, expansion

@check("option.unknown.refused")
def option_unknown():
    workspace, out, proc = run({"path": "f.txt", "content": "x", "option": "replace"})
    expect_error(out, 40001)
    message = out.get("message") or ""
    expect("insertAtStart" in message and "appendToEnd" in message and "overWrite" in message,
           "the message lists the three options that exist, got %r" % message)
    expect(not os.path.exists(os.path.join(workspace, "f.txt")), "nothing is written on an input error")


@check("option.case.insensitive")
def option_case():
    workspace, out, proc = run({"path": "f.txt", "content": "x", "option": "APPENDTOEND"})
    expect_ok(out, proc)
    expect(field(out, "option") == "appendToEnd", "the canonical spelling is reported, got %r" % field(out, "option"))


@check("path.missing.refused")
def path_missing():
    workspace, out, proc = run({"path": "", "content": "x"}, drop=("path",))
    expect_error(out, 40002)


@check("path.escape.refused")
def path_escape():
    name = "escaped-%d.txt" % os.getpid()
    workspace, out, proc = run({"path": "../" + name, "content": "x"})
    expect_error(out, 40003)
    expect(not os.path.exists(os.path.join(os.path.dirname(workspace), name)),
           "nothing may be written outside the workspace")


@check("path.absolute.with.workspace.refused")
def path_absolute_with_workspace():
    target = os.path.join(tempfile.mkdtemp(prefix="outside-"), "abs.txt")
    workspace, out, proc = run({"path": target, "content": "x"})
    expect_error(out, 40003)
    expect(not os.path.exists(target), "the absolute path must not be written")


@check("path.absolute.without.workspace")
def path_absolute_without_workspace():
    target = os.path.join(tempfile.mkdtemp(prefix="outside-"), "abs.txt")
    workspace, out, proc = run({"path": target, "content": "x", "useWorkspace": "false"})
    expect_ok(out, proc)
    expect(open(target, "rb").read() == b"x", "the absolute path is written when useWorkspace is off")
    expect(field(out, "path") == target, "output path is the absolute target")


@check("path.relative.without.workspace.uses.workspace")
def path_relative_without_workspace():
    workspace, out, proc = run({"path": "rel.txt", "content": "x", "useWorkspace": "false"})
    expect_ok(out, proc)
    expect_bytes(workspace, "rel.txt", b"x", "a relative path resolves against the workspace, not the process cwd")
    expect(field(out, "path") == os.path.join(workspace, "rel.txt"), "output is absolute")


@check("path.env.expanded")
def path_env_expanded():
    workspace, out, proc = run({"path": "${OUT_DIR}/${NAME}.txt", "content": "x"},
                               env_extra={"OUT_DIR": "target", "NAME": "notes"})
    expect_ok(out, proc)
    expect_bytes(workspace, "target/notes.txt", b"x", "${VAR} is expanded in the path")


@check("content.env.expanded.both.forms")
def content_env_expanded():
    workspace, out, proc = run({"path": "f.txt", "content": "build ${BUILD_NUMBER} on $JOB_NAME ${WORKSPACE}"},
                               env_extra={})
    expect_ok(out, proc)
    expected = "build 7 on textfile %s" % workspace
    expect_bytes(workspace, "f.txt", expected.encode(), "both $NAME and ${NAME} are expanded, WORKSPACE included")


@check("content.unknown.variable.left.alone")
def content_unknown_variable():
    workspace, out, proc = run({"path": "f.txt", "content": "cost $5 and ${NOT_SET} stay"})
    expect_ok(out, proc)
    expect_bytes(workspace, "f.txt", b"cost $5 and ${NOT_SET} stay",
                 "EnvVars.expand leaves what it cannot resolve untouched")


@check("content.utf8.bytes")
def content_utf8():
    text = "中文 ✓ emoji 🚀\ttab\nsecond"
    workspace, out, proc = run({"path": "u.txt", "content": text})
    expect_ok(out, proc)
    expect_bytes(workspace, "u.txt", text.encode("utf-8"), "content is written as UTF-8, byte for byte")
    expect(field(out, "bytes") == str(len(text.encode("utf-8"))),
           "bytes output counts UTF-8 bytes, got %r for %d" % (field(out, "bytes"), len(text.encode("utf-8"))))


@check("sha256.matches.bytes")
def sha256_matches():
    text = "checksum me\nsecond line"
    workspace, out, proc = run({"path": "s.txt", "content": text})
    expect_ok(out, proc)
    expected = hashlib.sha256(text.encode("utf-8")).hexdigest()
    expect(field(out, "sha256") == expected, "sha256 of the written bytes, got %r want %r"
           % (field(out, "sha256"), expected))
    expect(field(out, "verified") == "true", "readback must be reported as verified")


@check("existing.not.utf8.refused")
def existing_not_utf8():
    workspace, out, proc = run({"path": "bin.txt", "content": "x"},
                               files={"bin.txt": b"caf\xe9 latin-1"})
    expect_error(out, 40007)
    expect_bytes(workspace, "bin.txt", b"caf\xe9 latin-1", "a file that cannot be decoded is left untouched")


@check("existing.too.large.refused")
def existing_too_large():
    workspace, out, proc = run({"path": "big.txt", "content": "x", "maxFileSizeBytes": "10"},
                               files={"big.txt": b"0123456789abc"})
    expect_error(out, 40008)
    expect_bytes(workspace, "big.txt", b"0123456789abc", "an oversized file is left untouched")


@check("path.is.directory.refused")
def path_is_directory():
    workspace = tempfile.mkdtemp(prefix="text-file-check-")
    os.makedirs(os.path.join(workspace, "adir"))
    out, proc = execute(workspace, {"path": "adir", "content": "x"})
    expect_error(out, 40004)
    expect(os.path.isdir(os.path.join(workspace, "adir")), "the directory is still a directory")


@check("existing.permissions.kept")
def existing_permissions_kept():
    workspace = tempfile.mkdtemp(prefix="text-file-check-")
    path = os.path.join(workspace, "secret.txt")
    with open(path, "wb") as handle:
        handle.write(b"a")
    os.chmod(path, 0o600)
    out, proc = execute(workspace, {"path": "secret.txt", "content": "b", "option": "appendToEnd"})
    expect_ok(out, proc)
    mode = stat.S_IMODE(os.stat(path).st_mode)
    expect(mode == 0o600, "writing in place keeps the file mode (the plugin truncates, it does not replace): %o" % mode)
    expect_bytes(workspace, "secret.txt", b"a" + PLATFORM.encode() + b"b", "content is appended")


@check("existing.symlink.followed")
def existing_symlink_followed():
    workspace = tempfile.mkdtemp(prefix="text-file-check-")
    real = os.path.join(workspace, "real.txt")
    with open(real, "wb") as handle:
        handle.write(b"a")
    os.symlink(real, os.path.join(workspace, "link.txt"))
    out, proc = execute(workspace, {"path": "link.txt", "content": "b"})
    expect_ok(out, proc)
    expect(os.path.islink(os.path.join(workspace, "link.txt")), "the symlink must survive the write")
    expect(open(real, "rb").read() == b"a" + PLATFORM.encode() + b"b", "the link target holds the new text")


@check("verify.disabled.reports.unverified")
def verify_disabled():
    workspace, out, proc = run({"path": "f.txt", "content": "x", "verifyAfterWrite": "false"})
    expect_ok(out, proc)
    expect(field(out, "verified") == "false", "with the readback switched off the output must not claim it happened")
    expect_bytes(workspace, "f.txt", b"x", "the file is still written")


@check("build.number.in.path")
def build_number_in_path():
    workspace, out, proc = run({"path": "reports/v${BUILD_NUMBER}.txt", "content": "x", "BUILD_NUMBER": "42"})
    expect_ok(out, proc)
    expect_bytes(workspace, "reports/v42.txt", b"x", "the build number from the environment lands in the path")


def main(argv):
    global JAR
    only = ""
    args = list(argv)
    if "--list" in args:
        for name, _ in CHECKS:
            print(name)
        return 0
    while args:
        arg = args.pop(0)
        if arg == "--jar":
            JAR = args.pop(0)
        elif arg.startswith("--"):
            raise SystemExit("unknown option " + arg)
        else:
            only = arg
    if not os.path.exists(JAR):
        raise SystemExit("build the atom first: ./build.sh (missing %s)" % JAR)
    failures = 0
    selected = [(n, f) for n, f in CHECKS if only in n]
    for name, fn in selected:
        try:
            fn()
        except Failed as error:
            failures += 1
            print("FAIL %s: %s" % (name, error))
        except Exception as error:  # noqa: BLE001 - a broken check is a failure, not a crash
            failures += 1
            print("FAIL %s: %s: %s" % (name, type(error).__name__, error))
        else:
            print("ok   %s" % name)
    print("%d/%d checks green" % (len(selected) - failures, len(selected)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
