#!/usr/bin/env bash
# Smoke test: run the atom the way the runner does and read the file back from disk.
#
#   ./tools/smoke.sh
#
# The deep suite (34 behaviour checks, each expectation recomputed from its own inputs) is tools/check.py; this file is
# the quick pass -- the three write options, the separator boundaries, and the two refusals -- so a broken build is
# visible in one screen.
set -uo pipefail
cd "$(dirname "$0")/.."
bash build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# expect_bytes <file> <printf format> [args...]
expect_bytes() {
  local file="$1" format="$2"
  shift 2
  printf "$format" "$@" >"$(dirname "$file")/.expected"
  if ! cmp -s "$file" "$(dirname "$file")/.expected"; then
    fail "$file holds $(od -An -c "$file" | head -3 | tr -s ' '), wanted $(od -An -c "$(dirname "$file")/.expected" | head -3 | tr -s ' ')"
  fi
  rm -f "$(dirname "$file")/.expected"
}

# 1. a new file is created with the content, whatever the option says
dir="$(mktemp -d)"
printf '{"path": "out/notes.txt", "content": "a\\nb", "option": "appendToEnd"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "create failed: $(cat "$dir/output.json")"
expect_bytes "$dir/out/notes.txt" 'a\nb'
[ "$(output "$dir" created)" = "true" ] || fail "created should be true"
[ "$(output "$dir" verified)" = "true" ] || fail "readback should be verified"

# 2. append adds a separator only when the file does not end with one
dir="$(mktemp -d)"; printf 'first' >"$dir/log.txt"
printf '{"path": "log.txt", "content": "second"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "append failed"
expect_bytes "$dir/log.txt" 'first\nsecond'

dir="$(mktemp -d)"; printf 'first\n' >"$dir/log.txt"
printf '{"path": "log.txt", "content": "second"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "append failed"
expect_bytes "$dir/log.txt" 'first\nsecond'

# 3. insert: no separator when the old body already starts with one
dir="$(mktemp -d)"; printf '\nbody' >"$dir/f.txt"
printf '{"path": "f.txt", "content": "head", "option": "insertAtStart"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "insert failed"
expect_bytes "$dir/f.txt" 'head\nbody'

# 4. overWrite keeps only the new content, and can empty the file
dir="$(mktemp -d)"; printf 'old\nlines\n' >"$dir/f.txt"
printf '{"path": "f.txt", "content": "", "option": "overWrite"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "overWrite failed"
expect_bytes "$dir/f.txt" ''

# 5. crlf is an input, not whatever the agent happens to be
dir="$(mktemp -d)"; printf 'one\ntwo' >"$dir/win.bat"
printf '{"path": "win.bat", "content": "three", "lineSeparator": "crlf"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "crlf append failed"
expect_bytes "$dir/win.bat" 'one\r\ntwo\r\nthree'

# 6. a path that leaves the workspace is refused, and nothing is written
dir="$(mktemp -d)"
outside="../escaped-$$.txt"
printf '{"path": "%s", "content": "x"}' "$outside" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a path outside the workspace must be refused"
[ ! -e "$(dirname "$dir")/escaped-$$.txt" ] || fail "a file was written outside the workspace"

# 7. replace/delete/regex are not options of this plugin and must be named as such
dir="$(mktemp -d)"
printf '{"path": "f.txt", "content": "x", "option": "replace"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "an unknown option must be refused"
grep -q "insertAtStart" "$dir/output.json" || fail "the refusal should list the three options that exist"

echo "smoke ok: create, append (both boundaries), insert, overWrite, crlf, workspace escape, unknown option"
