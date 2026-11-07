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
}
