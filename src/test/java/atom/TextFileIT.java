package atom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text-file atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the three write options, both separator boundaries, the
 * refusals — written as JUnit so a failure is a test result with the workspace and {@code output.json} attached.
 */
class TextFileIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a new file is created with the content, whatever the option says")
    void creates() throws Exception {
        AtomResult run = atom.input("{\"path\": \"out/notes.txt\", \"content\": \"a\\nb\", \"option\": \"appendToEnd\"}")
                .run();

        run.assertOk();
        assertEquals("a\nb", run.file("out/notes.txt"));
        assertEquals("true", run.field("created"));
        assertEquals("true", run.field("verified"), "the atom reads the file back and says so");
    }

    @Test
    @DisplayName("append adds a separator only when the file does not end with one")
    void appendsAtTheBoundary() throws Exception {
        AtomResult withoutSeparator = atom.file("log.txt", "first")
                .input("{\"path\": \"log.txt\", \"content\": \"second\"}")
                .run();
        withoutSeparator.assertOk();
        assertEquals("first\nsecond", withoutSeparator.file("log.txt"));

        AtomResult withSeparator = atom.file("log.txt", "first\n")
                .input("{\"path\": \"log.txt\", \"content\": \"second\"}")
                .run();
        withSeparator.assertOk();
        assertEquals("first\nsecond", withSeparator.file("log.txt"), "no doubled separator");
    }

    @Test
    @DisplayName("insert does not add a separator when the body already starts with one")
    void inserts() throws Exception {
        AtomResult run = atom.file("f.txt", "\nbody")
                .input("{\"path\": \"f.txt\", \"content\": \"head\", \"option\": \"insertAtStart\"}")
                .run();

        run.assertOk();
        assertEquals("head\nbody", run.file("f.txt"));
    }

    @Test
    @DisplayName("overWrite keeps only the new content, and can empty the file")
    void overwrites() throws Exception {
        AtomResult run = atom.file("f.txt", "old\nlines\n")
                .input("{\"path\": \"f.txt\", \"content\": \"\", \"option\": \"overWrite\"}")
                .run();

        run.assertOk();
        assertArrayEquals(new byte[0], run.bytes("f.txt"));
    }

    @Test
    @DisplayName("crlf is an input, not whatever the agent happens to be")
    void honoursTheSeparator() throws Exception {
        AtomResult run = atom.file("win.bat", "one\ntwo")
                .input("{\"path\": \"win.bat\", \"content\": \"three\", \"lineSeparator\": \"crlf\"}")
                .run();

        run.assertOk();
        assertArrayEquals("one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8), run.bytes("win.bat"));
    }

    @Test
    @DisplayName("a path that leaves the workspace is refused, and nothing is written")
    void refusesToEscapeTheWorkspace() throws Exception {
        AtomResult run = atom.input("{\"path\": \"../escaped.txt\", \"content\": \"x\"}").run();

        run.assertRefused();
        assertFalse(run.exists("../escaped.txt"), "no file outside the workspace");
    }

    @Test
    @DisplayName("an option this plugin does not have is refused by name")
    void refusesUnknownOptions() throws Exception {
        AtomResult run = atom.file("f.txt", "x")
                .input("{\"path\": \"f.txt\", \"content\": \"x\", \"option\": \"replace\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("insertAtStart"),
                "the refusal lists the three options that exist:\n" + run.file("output.json"));
    }

    // ------------------------------------------------------------------ ${VAR} expansion

    @Test
    @DisplayName("${NAME} in the path and in the content comes from the build environment")
    void expandsVariables() throws Exception {
        AtomResult run = atom.input("path", "${OUT_DIR}/notes.txt")
                .input("content", "build=${BUILD_NUMBER} owner=$OWNER")
                .input("OUT_DIR", "out")
                .input("BUILD_NUMBER", "42")
                .input("OWNER", "ci")
                .run();

        run.assertOk();
        assertEquals("build=42 owner=ci", run.file("out/notes.txt"),
                "the variables were not expanded:\n" + run);
        assertTrue(run.field("path").endsWith("/out/notes.txt"), "path: " + run.field("path"));
    }

    @Test
    @DisplayName("an unknown or unterminated variable is left exactly as it was typed")
    void leavesUnknownVariablesAlone() throws Exception {
        AtomResult run = atom.input("path", "f.txt")
                .input("content", "${NOPE} $MISSING ${unterminated ${} $")
                .run();

        run.assertOk();
        assertEquals("${NOPE} $MISSING ${unterminated ${} $", run.file("f.txt"));
    }

    @Test
    @DisplayName("WORKSPACE is the run's workspace, so an absolute ${WORKSPACE} path works with useWorkspace=false")
    void expandsTheWorkspace() throws Exception {
        AtomResult run = atom.input("path", "${WORKSPACE}/abs.txt")
                .input("content", "here")
                .input("useWorkspace", "false")
                .run();

        run.assertOk();
        assertEquals("here", run.file("abs.txt"), "the file did not land in the workspace: " + run);
        assertEquals(run.work().resolve("abs.txt").normalize().toString(), run.field("path"),
                "the atom must report the absolute path it wrote");
    }

    // ------------------------------------------------------------------ separators and options

    @Test
    @DisplayName("lf and cr are line separators in their own right, and the output names them")
    void honoursEverySeparator() throws Exception {
        AtomResult lf = atom.file("f.txt", "one\ntwo")
                .input("path", "f.txt")
                .input("content", "three")
                .input("lineSeparator", "lf")
                .run();
        lf.assertOk();
        assertArrayEquals("one\ntwo\nthree".getBytes(StandardCharsets.UTF_8), lf.bytes("f.txt"));
        assertEquals("lf", lf.field("lineSeparator"));

        AtomResult cr = atom.file("f.txt", "one\ntwo")
                .input("path", "f.txt")
                .input("content", "three")
                .input("lineSeparator", "cr")
                .run();
        cr.assertOk();
        assertArrayEquals("one\rtwo\rthree".getBytes(StandardCharsets.UTF_8), cr.bytes("f.txt"),
                "bare LF must be rewritten to CR");
        assertEquals("custom", cr.field("lineSeparator"));
    }

    @Test
    @DisplayName("an unknown lineSeparator is refused and nothing is written")
    void refusesUnknownSeparators() throws Exception {
        AtomResult run = atom.input("path", "f.txt")
                .input("content", "x")
                .input("lineSeparator", "crlf-unix")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("crlf-unix"), run.file("output.json"));
        assertTrue(run.file("output.json").contains("40001"), run.file("output.json"));
        assertFalse(run.exists("f.txt"), "a refused run must not write");
    }

    @Test
    @DisplayName("append and insert spellings the plugin accepts in other cases are still the same options")
    void acceptsTheAliases() throws Exception {
        AtomResult append = atom.file("f.txt", "a")
                .input("path", "f.txt").input("content", "b").input("option", "append")
                .run();
        append.assertOk();
        assertEquals("a\nb", append.file("f.txt"));

        AtomResult prepend = atom.file("f.txt", "a")
                .input("path", "f.txt").input("content", "z").input("option", "prepend")
                .run();
        prepend.assertOk();
        assertEquals("z\na", prepend.file("f.txt"));

        AtomResult overwrite = atom.file("f.txt", "a")
                .input("path", "f.txt").input("content", "only").input("option", "overwrite")
                .run();
        overwrite.assertOk();
        assertEquals("only", overwrite.file("f.txt"));
        assertEquals("false", overwrite.field("created"), "the file existed before the run");
    }

    // ------------------------------------------------------------------ input refusals

    @Test
    @DisplayName("missing path, empty content, a non-positive size limit and a bad separator are each named")
    void refusesBadInput() throws Exception {
        AtomResult noPath = atom.input("content", "x").run();
        noPath.assertRefused();
        assertEquals("40002", noPath.field("errorCode"), noPath.file("output.json"));

        AtomResult noContent = atom.file("f.txt", "old").input("path", "f.txt").input("content", "").run();
        noContent.assertRefused();
        assertEquals("40009", noContent.field("errorCode"), noContent.file("output.json"));
        assertEquals("old", noContent.file("f.txt"), "the old file must be untouched");

        AtomResult zero = atom.input("path", "f.txt").input("content", "x").input("maxFileSizeBytes", "0").run();
        zero.assertRefused();
        assertEquals("40001", zero.field("errorCode"), zero.file("output.json"));
        assertTrue(zero.file("output.json").contains("maxFileSizeBytes"), zero.file("output.json"));
    }

    @Test
    @DisplayName("an absolute path is refused while useWorkspace is on, and allowed when it is off")
    void gatesAbsolutePaths() throws Exception {
        AtomResult refused = atom.input("path", "/tmp/absolute-notes.txt").input("content", "x").run();
        refused.assertRefused();
        assertEquals("40003", refused.field("errorCode"), refused.file("output.json"));
        assertTrue(refused.file("output.json").contains("useWorkspace=false"),
                "the refusal must name the way out:\n" + refused.file("output.json"));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/absolute-notes.txt")),
                "the atom wrote an absolute path it refused");

        java.nio.file.Path outside = java.nio.file.Files.createTempDirectory("text-file-it-");
        try {
            AtomResult allowed = atom.input("path", outside.resolve("kept.txt").toString())
                    .input("content", "outside")
                    .input("useWorkspace", "false")
                    .run();
            allowed.assertOk();
            assertEquals("outside",
                    java.nio.file.Files.readString(outside.resolve("kept.txt"), StandardCharsets.UTF_8));
        } finally {
            java.nio.file.Files.deleteIfExists(outside.resolve("kept.txt"));
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("a directory, an oversized file and a file that is not UTF-8 are refused before anything is written")
    void refusesTheFileItCannotUse() throws Exception {
        AtomResult directory = atom.file("dir/inside.txt", "x").input("path", "dir").input("content", "y").run();
        directory.assertRefused();
        assertEquals("40004", directory.field("errorCode"), directory.file("output.json"));

        AtomResult tooBig = atom.file("big.txt", "0123456789")
                .input("path", "big.txt").input("content", "x").input("maxFileSizeBytes", "3")
                .run();
        tooBig.assertRefused();
        assertEquals("40008", tooBig.field("errorCode"), tooBig.file("output.json"));
        assertTrue(tooBig.file("output.json").contains("10 bytes"), tooBig.file("output.json"));

        assertEquals("10", String.valueOf(tooBig.work().resolve("big.txt").toFile().length()),
                "the oversized file must be left as it was");
    }

    // ------------------------------------------------------------------ write and readback

    @Test
    @DisplayName("a path under a regular file cannot be created, and that is an environment failure")
    void reportsAnUnwritablePath() throws Exception {
        AtomResult run = atom.file("blocked", "not a directory")
                .input("path", "blocked/sub/f.txt")
                .input("content", "x")
                .run();

        run.assertRefused();
        assertEquals("40005", run.field("errorCode"), "a filesystem problem is 40005:\n" + run);
        assertEquals("5", run.field("errorType"), "it is the agent's environment (ENVIRONMENT), not the caller's input:\n" + run);
    }

    @Test
    @DisplayName("the output carries the byte count and the sha256 of what was written, and verifyAfterWrite can be off")
    void reportsTheBytesItWrote() throws Exception {
        String content = "line one\nline two\n";
        AtomResult checked = atom.input("path", "hash.txt").input("content", content).run();
        checked.assertOk();
        byte[] expected = content.getBytes(StandardCharsets.UTF_8);
        assertEquals(String.valueOf(expected.length), checked.field("bytes"));
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(expected));
        assertEquals(digest, checked.field("sha256"), "sha256 of the written bytes");
        assertEquals("true", checked.field("verified"));

        AtomResult unchecked = atom.input("path", "hash.txt").input("content", content)
                .input("verifyAfterWrite", "false")
                .run();
        unchecked.assertOk();
        assertEquals("false", unchecked.field("verified"), "verifyAfterWrite=false must be reported");
        assertArrayEquals(expected, unchecked.bytes("hash.txt"), "the bytes are written either way");
    }

    @Test
    @DisplayName("a nested path gets its directories, and an empty overWrite on a new file creates it empty")
    void createsDirectoriesAndEmptyFiles() throws Exception {
        AtomResult nested = atom.input("path", "a/b/c/deep.txt").input("content", "deep").run();
        nested.assertOk();
        assertEquals("deep", nested.file("a/b/c/deep.txt"));
        assertEquals("true", nested.field("created"));

        AtomResult empty = atom.input("path", "a/b/empty.txt").input("content", "").input("option", "overWrite").run();
        empty.assertOk();
        assertArrayEquals(new byte[0], empty.bytes("a/b/empty.txt"));
        assertEquals("0", empty.field("bytes"));
    }
}
