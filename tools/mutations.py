#!/usr/bin/env python3
"""Plant one bug at a time in the atom and prove the checks catch it.

A green suite means nothing if it stays green for a broken atom. Each mutation is one textual change to the real
source in a throwaway copy of the repository, rebuilt into its own jar; the named check must then fail. A survivor is a
hole in the suite.

    python3 tools/mutations.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
CODE = "text-file"
ATOM = HERE / "src" / "main" / "java" / "com" / "jenkinschina" / "atom" / "textfile" / "TextFileAtom.java"

MUTATIONS = [
    ('a new file is treated as an existing one', ATOM,
     'boolean existed = Files.exists(target);', 'boolean existed = true;', 'create.new.file'),
    ('created is reported inverted', ATOM,
     'String.valueOf(!existed)', 'String.valueOf(existed)', 'create.new.file'),
    ('parent directories are not created', ATOM,
     'Files.createDirectories(parent);', 'if (false) Files.createDirectories(parent);', 'create.parent.directories'),
    ('append always inserts a separator', ATOM,
     'existing.endsWith(lineSeparator) ? existing.concat(content)', 'existing.endsWith(lineSeparator) ? existing.concat(lineSeparator + content)',
     'append.with.trailing.separator'),
    ('append never inserts a separator', ATOM,
     ': existing + lineSeparator + content;', ': existing + content;',
     'append.without.trailing.separator'),
    ('insert checks the end of the file', ATOM,
     'existing.startsWith(lineSeparator)', 'existing.endsWith(lineSeparator)', 'insert.with.leading.separator'),
    ('insert ignores a leading separator', ATOM,
     'existing.startsWith(lineSeparator) ? content.concat(existing)', 'existing.startsWith(lineSeparator) ? content + lineSeparator + existing',
     'insert.with.leading.separator'),
    ('overWrite behaves like append', ATOM,
     'if (!existed || OVERWRITE.equals(option)) {', 'if (!existed) {', 'overwrite.replaces.all'),
    ('the separator normalisation is skipped', ATOM,
     'text = text.replaceAll("(?<!\\\\r)\\\\n", java.util.regex.Matcher.quoteReplacement(lineSeparator));', 'text = text;',
     'line.separator.crlf.rewrites.bare.lf.only'),
    ('an existing CRLF is doubled', ATOM,
     '(?<!\\\\r)\\\\n', '\\\\n', 'line.separator.crlf.rewrites.bare.lf.only'),
    ('crlf silently means the platform separator', ATOM,
     'return "\\r\\n";', 'return System.lineSeparator();', 'line.separator.crlf'),
    ('an unknown option is accepted as append', ATOM,
     'return "?" + value;', 'return APPEND;', 'option.unknown.refused'),
    ('the workspace escape check is gone', ATOM,
     'if (!resolved.startsWith(workspace) || resolved.equals(workspace))', 'if (false)', 'path.escape.refused'),
    ('empty content is accepted for append', ATOM,
     'rawContent.isEmpty() && !OVERWRITE.equals(option)', 'rawContent.isEmpty() && false', 'empty.content.refused'),
    ('a directory is written as if it were a file', ATOM,
     'if (Files.isDirectory(target))', 'if (false)', 'path.is.directory.refused'),
    ('the size limit is ignored', ATOM,
     'if (size > maxBytes)', 'if (false)', 'existing.too.large.refused'),
    ('a non-UTF-8 file is read anyway', ATOM,
     '.onMalformedInput(CodingErrorAction.REPORT)', '.onMalformedInput(CodingErrorAction.REPLACE)',
     'existing.not.utf8.refused'),
    ('the sha256 is not the written bytes', ATOM,
     'String sha256 = sha256(bytes);', 'String sha256 = sha256(new byte[0]);', 'sha256.matches.bytes'),
    ('bytes counts characters', ATOM,
     'data("bytes", String.valueOf(bytes.length))', 'data("bytes", String.valueOf(text.length()))', 'content.utf8.bytes'),
    ('the content is never expanded', ATOM,
     'String content = expand(rawContent, env);', 'String content = rawContent;', 'content.env.expanded'),
    ('$NAME is not expanded, only ${NAME}', ATOM,
     'if (end > start) {', 'if (false) {', 'content.env.expanded'),
    ('an unknown variable becomes empty', ATOM,
     'String value = name.isEmpty() ? null : env.get(name);', 'String value = name.isEmpty() ? "" : env.getOrDefault(name, "");',
     'content.unknown.variable.left.alone'),
    ('the readback is claimed without running', ATOM,
     'boolean verified = false;', 'boolean verified = true;', 'verify.disabled.reports.unverified'),
    ('the path is not expanded', ATOM,
     'String expandedPath = expand(rawPath, env);', 'String expandedPath = rawPath;', 'path.env.expanded'),
]


def sdk_jar():
    env = os.environ.get("SDK_JAR")
    if env:
        return env
    for root in (HERE.parent / "jenkins-atom-sdk", HERE / "jenkins-atom-sdk"):
        candidates = sorted((root / "target").glob("jenkins-atom-sdk-*.jar"))
        if candidates:
            return str(candidates[-1])
    raise SystemExit("build the SDK first: cd ../jenkins-atom-sdk && ./build.sh")


def build(tree):
    env = dict(os.environ)
    env["SDK_JAR"] = sdk_jar()
    proc = subprocess.run(["bash", "build.sh"], cwd=tree, env=env, capture_output=True, text=True)
    if proc.returncode != 0:
        return None, proc.stderr[-1500:]
    return tree / "dist" / CODE / (CODE + ".jar"), proc.stdout


def main():
    failures = 0
    for index, (name, source_file, old, new, case) in enumerate(MUTATIONS, 1):
        tree = pathlib.Path(tempfile.mkdtemp(prefix="text-file-mut-")) / "atom"
        shutil.copytree(HERE, tree, ignore=shutil.ignore_patterns("dist", ".git", "__pycache__"))
        source = tree / source_file.relative_to(HERE)
        text = source.read_text(encoding="utf-8")
        if old not in text:
            print("SKIP  %-2d %s: the mutation no longer matches the source" % (index, name))
            failures += 1
            continue
        source.write_text(text.replace(old, new, 1), encoding="utf-8")
        jar, output = build(tree)
        if jar is None:
            print("SKIP  %-2d %s: the mutated atom does not build: %s" % (index, name, output))
            failures += 1
            continue
        proc = subprocess.run([sys.executable, "tools/check.py", case, "--jar", str(jar)],
                              cwd=tree, capture_output=True, text=True)
        if proc.returncode == 0:
            print("SURVIVED %-2d %s (case '%s' stayed green)" % (index, name, case))
            failures += 1
        elif "0/0 checks" in proc.stdout:
            print("SURVIVED %-2d %s (no check matched '%s')" % (index, name, case))
            failures += 1
        else:
            first = [line for line in proc.stdout.splitlines() if line.startswith("FAIL")][:1]
            print("caught   %-2d %s%s" % (index, name, ("  <- " + first[0][:110]) if first else ""))
    print("\n%d/%d mutations caught" % (len(MUTATIONS) - failures, len(MUTATIONS)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
