# jenkins-atom-text-file

Create/update text file -- port of **text-file-operations** (1,644 installs, plugin 1.3.2) as an atom.

The plugin is one build step, "Create/Update Text File": three radio buttons, two text boxes, one checkbox. It reads the
file it is pointed at, combines the old text with the new text according to the option, and writes the result as UTF-8.
Nothing else. (The queue entry that sent me here promised append/prepend/replace/delete/regex; there is no replace, no
delete and no regular expression anywhere in `text-file-operations.jar` -- 3 classes, `CreateFileBuilder`,
`CreateFileTask`, `CreateFileBuilder$DescriptorImpl`. `option=replace` is refused with 40001 and the refusal names the
three options that exist.)

## What the atom copies from the plugin

Read out of `CreateFileTask.call()` and `CreateFileBuilder.perform()` (javap, 1.3.2):

- **options** `insertAtStart`, `appendToEnd`, `overWrite`, compared with `equalsIgnoreCase`.
- **a file that does not exist is written with the content, whatever the option says** -- and `created=true`.
- **append boundary**: `existing.endsWith(separator) ? existing + content : existing + separator + content`. So a file
  ending in CRLF does not get another separator: `"a\r\n"` ends with `"\n"`.
- **insert boundary**: `existing.startsWith(separator) ? content + existing : content + separator + existing`.
- **newline normalisation over the whole result**: the plugin calls `replaceAll("\n", System.lineSeparator())` on the
  combined text, so `\n` typed in the form becomes the agent's line ending -- and an existing bare LF is rewritten too.
- **`${VAR}` expansion** in both the path and the content, from the build environment (job parameters win over what the
  agent inherited); anything unresolvable is left as written, the way `EnvVars.expand` behaves.
- **`useWorkspace`**: with the checkbox ticked the path is prefixed with `${WORKSPACE}/` unless it already starts with
  `${WORKSPACE}` or `$WORKSPACE`; parent directories are created (`FilePath$Write` mkdirs the parent, then opens the
  file for writing, which truncates in place -- the plugin calls `deleteContents()` first).

## Differences (deliberate, all in `tools/check.py`)

1. **Readback.** Every write is read back and compared byte for byte with what was written; a mismatch fails with 40006
   and names the offset and the two lengths. The plugin never looks at the file again. `verifyAfterWrite=false`
   switches it off, and then `verified=false` says so instead of claiming it happened.
2. **Only bare LF is normalised.** The plugin's `replaceAll("\n", separator)` also rewrites the second half of an
   existing `\r\n`, so on a Windows agent appending to a CRLF file produces `\r\r\n` on every existing line. The
   atom rewrites `(?<!\r)\n` only.
3. **`lineSeparator` is an input** (`platform` default, or `lf`, `crlf`, `cr`). The plugin's separator is whatever OS the
   agent runs, so the same job writes LF on Linux and CRLF on Windows; here the job says which it wants and the output
   reports what was used.
4. **A path that leaves the workspace is refused** (40003), and an absolute path with `useWorkspace=true` is refused
   too instead of being blindly prefixed into `${WORKSPACE}//abs/path`. With `useWorkspace=false` an absolute path is
   written as given; a relative one resolves against the workspace, not against the remoting JVM's process directory.
5. **A file that is not valid UTF-8 fails** (40007) instead of being decoded with the JVM default charset and written
   back mangled. A file larger than `maxFileSizeBytes` (16 MiB default) fails (40008) before it is read into memory.
6. **A directory target fails with 40004** by name; the plugin reads the directory, throws, and reports "Failed to
   create/update file" with the build red.
7. **Empty content is refused for append/insert** (40009): the plugin's form rejects it too, but its model does not, and
   an append of `""` to a file without a trailing separator silently adds a line break. `overWrite` with empty content is
   the way to empty a file.

Outputs: `path` (absolute), `option`, `created`, `bytes`, `sha256`, `lineSeparator`, `verified`.

## Build and test

```bash
./build.sh
./tools/smoke.sh          # build + the three options, both boundaries, crlf, two refusals
python3 tools/check.py    # 34 behaviour checks, every expectation recomputed from its own inputs
python3 tools/mutations.py # 24 planted bugs, each must turn a named check red
```

`tools/check.py` never trusts the exit code: it re-reads the file from disk with Python, and recomputes the separator
rules, the `${VAR}` expansion and the sha256 from the check's own inputs. `tools/mutations.py` copies the repository,
patches one line, rebuilds, and requires the named check to fail -- 24/24 caught.

Note what is *not* verifiable here: nothing needs a real Jenkins, because the whole step is local file work. The plugin
itself was never run in a Jenkins instance; its behaviour comes from the bytecode above (`CreateFileTask.call`,
`CreateFileBuilder.perform`, `FilePath$Write.invoke` from jenkins-core 2.479.3).
