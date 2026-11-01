package com.jenkinschina.atom.textfile;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * text-file-operations as an atom: create or update one text file, in place.
 *
 * <p>The plugin is two classes plus a task object -- {@code CreateFileBuilder.perform} expands {@code ${VAR}} in the
 * path and in the content, prefixes {@code ${WORKSPACE}/} when "Create at Workspace" is ticked, and hands both strings
 * to {@code CreateFileTask}, which reads the old file, builds the new text from one of three write options and writes
 * it as UTF-8. This atom keeps that contract, including the two boundary rules that only show up when the old file
 * already starts or ends with a separator, and adds the readback the plugin never did.
 *
 * <p>What the plugin does <em>not</em> have, despite the queue entry that sent me here: there is no replace, no delete
 * and no regular expression. The form offers exactly insertAtStart, appendToEnd and overWrite.
 */
public class TextFileAtom implements AtomTask {

    /** Options exactly as the plugin's radio buttons spell them; matching is case-insensitive, like equalsIgnoreCase. */
    static final String INSERT = "insertAtStart";
    static final String APPEND = "appendToEnd";
    static final String OVERWRITE = "overWrite";

    static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace().toAbsolutePath().normalize();

        String option = canonicalOption(context.getParam("option", APPEND));
        if (option.startsWith("?")) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "unknown option '" + option.substring(1) + "'; use " + INSERT + ", " + APPEND + " or " + OVERWRITE);
        }
        String lineSeparator = lineSeparator(context.getParam("lineSeparator", "platform"));
        if (lineSeparator.startsWith("?")) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "unknown lineSeparator '" + lineSeparator.substring(1) + "'; use platform, lf, crlf or cr");
        }
        boolean useWorkspace = context.getBooleanParam("useWorkspace", true);
        boolean verifyAfterWrite = context.getBooleanParam("verifyAfterWrite", true);
        int maxBytes = context.getIntParam("maxFileSizeBytes", DEFAULT_MAX_BYTES);
        if (maxBytes <= 0) {
            return AtomResult.error(ErrorType.USER, 40001, "maxFileSizeBytes must be positive: " + maxBytes);
        }

        String rawPath = context.getParam("path", "");
        String rawContent = context.getParam("content", "");
        if (rawPath.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40002, "path is required");
        }
        if (rawContent.isEmpty() && !OVERWRITE.equals(option)) {
            return AtomResult.error(ErrorType.USER, 40009,
                    "content is empty; only " + OVERWRITE + " accepts empty content (it empties the file)");
        }

        // EnvVars.expand reads the build environment: the job's parameters win over what the agent inherited. The
        // runner hands the parameters over as input.json, so both sources are merged here.
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        for (Map.Entry<String, Object> param : context.getParams().entrySet()) {
            Object value = param.getValue();
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                env.put(param.getKey(), String.valueOf(value));
            }
        }
        env.put("WORKSPACE", workspace.toString());

        String expandedPath = expand(rawPath, env);
        String content = expand(rawContent, env);

        Path target;
        if (useWorkspace) {
            if (Path.of(rawPath).isAbsolute()) {
                return AtomResult.error(ErrorType.USER, 40003,
                        "path is absolute but useWorkspace is on: '" + rawPath
                                + "'; set useWorkspace=false to write outside the workspace");
            }
            Path resolved = workspace.resolve(expandedPath).normalize();
            if (!resolved.startsWith(workspace) || resolved.equals(workspace)) {
                return AtomResult.error(ErrorType.USER, 40003,
                        "path escapes the workspace: '" + rawPath + "' expands to " + resolved);
            }
            target = resolved;
        } else {
            Path candidate = Path.of(expandedPath);
            target = (candidate.isAbsolute() ? candidate : workspace.resolve(candidate)).normalize();
        }

        if (Files.isDirectory(target)) {
            return AtomResult.error(ErrorType.USER, 40004, "path is a directory, not a file: " + target);
        }

        boolean existed = Files.exists(target);
        String existing = "";
        if (existed) {
            long size = Files.size(target);
            if (size > maxBytes) {
                return AtomResult.error(ErrorType.USER, 40008,
                        "existing file is " + size + " bytes, over maxFileSizeBytes=" + maxBytes + ": " + target);
            }
            try {
                existing = decode(Files.readAllBytes(target));
            } catch (CharacterCodingException e) {
                return AtomResult.error(ErrorType.USER, 40007,
                        "existing file is not valid UTF-8: " + target);
            }
        }

        // CreateFileTask: a file that does not exist is written with the content, whatever the option says.
        String text;
        if (!existed || OVERWRITE.equals(option)) {
            text = content;
        } else if (APPEND.equals(option)) {
            text = existing.endsWith(lineSeparator) ? existing.concat(content)
                    : existing + lineSeparator + content;
        } else {
            text = existing.startsWith(lineSeparator) ? content.concat(existing)
                    : content + lineSeparator + existing;
        }
        // The plugin calls replaceAll("\n", separator) on the whole result, so on a Windows agent appending to a file
        // that already has CRLF endings turns every "\r\n" into "\r\r\n". Only bare LF is rewritten here; a line
        // that already ends in CR is left alone.
        text = text.replaceAll("(?<!\\r)\\n", java.util.regex.Matcher.quoteReplacement(lineSeparator));

        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40005,
                    "cannot write " + target + ": " + e.getMessage());
        }

        String sha256 = sha256(bytes);
        boolean verified = false;
        if (verifyAfterWrite) {
            byte[] written;
            try {
                written = Files.readAllBytes(target);
            } catch (IOException e) {
                return AtomResult.error(ErrorType.ENVIRONMENT, 40005,
                        "cannot read back " + target + ": " + e.getMessage());
            }
            if (!Arrays.equals(written, bytes)) {
                int at = firstDifference(written, bytes);
                return AtomResult.error(ErrorType.PLUGIN, 40006,
                        "readback differs from what was written at byte " + at + " of " + target
                                + " (wrote " + bytes.length + " bytes, read " + written.length + ")");
            }
            verified = true;
        }

        context.log("Writing file at '" + target + "', option '" + option + "', "
                + (existed ? "file already exists" : "new file created"));

        return AtomResult.success()
                .data("path", target.toString())
                .data("option", option)
                .data("created", String.valueOf(!existed))
                .data("bytes", String.valueOf(bytes.length))
                .data("sha256", sha256)
                .data("lineSeparator", lineSeparator.equals("\r\n") ? "crlf" : lineSeparator.equals("\n") ? "lf" : "custom")
                .data("verified", String.valueOf(verified));
    }

    private static String canonicalOption(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.equalsIgnoreCase(INSERT) || value.equalsIgnoreCase("prepend") || value.equalsIgnoreCase("insertAtStartOfFile")) {
            return INSERT;
        }
        if (value.equalsIgnoreCase(APPEND) || value.equalsIgnoreCase("append") || value.equalsIgnoreCase("appendToEndOfFile")) {
            return APPEND;
        }
        if (value.equalsIgnoreCase(OVERWRITE) || value.equalsIgnoreCase("overwrite")) {
            return OVERWRITE;
        }
        return "?" + value;
    }

    private static String lineSeparator(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("platform")) {
            return System.lineSeparator();
        }
        if (value.equalsIgnoreCase("lf")) {
            return "\n";
        }
        if (value.equalsIgnoreCase("crlf")) {
            return "\r\n";
        }
        if (value.equalsIgnoreCase("cr")) {
            return "\r";
        }
        return "?" + value;
    }

    /** Jenkins' EnvVars.expand: ${NAME} and $NAME from the build environment, anything unknown left untouched. */
    static String expand(String text, Map<String, String> env) {
        if (text == null || text.isEmpty() || text.indexOf('$') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int close = text.indexOf('}', i + 2);
                if (close > i + 2) {
                    String name = text.substring(i + 2, close);
                    String value = name.isEmpty() ? null : env.get(name);
                    if (value != null) {
                        out.append(value);
                        i = close + 1;
                        continue;
                    }
                }
                out.append(c);
                i++;
                continue;
            }
            int start = i + 1;
            int end = start;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                end++;
            }
            if (end > start) {
                String value = env.get(text.substring(start, end));
                if (value != null) {
                    out.append(value);
                    i = end;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static int firstDifference(byte[] a, byte[] b) {
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return limit;
    }
}
