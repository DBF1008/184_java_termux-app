package com.termux.app.api.file;

import com.termux.app.api.file.FileReceiverActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void testIsSharedTextAnUrl() {
        List<String> validUrls = new ArrayList<>();
        validUrls.add("http://example.com");
        validUrls.add("https://example.com");
        validUrls.add("https://example.com/path/parameter=foo");
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80");
        for (String url : validUrls) {
            Assert.assertTrue(FileReceiverActivity.isSharedTextAnUrl(url));
        }

        List<String> invalidUrls = new ArrayList<>();
        invalidUrls.add("a test with example.com");
        invalidUrls.add("");
        invalidUrls.add(null);
        for (String url : invalidUrls) {
            Assert.assertFalse(FileReceiverActivity.isSharedTextAnUrl(url));
        }
    }

    /** The single sanitizer that unifies the file name source across text, file uri and content uri. */
    @Test
    public void testSanitizeFileName() {
        // Plain names are kept verbatim.
        Assert.assertEquals("report.pdf", FileReceiverActivity.sanitizeFileName("report.pdf"));
        Assert.assertEquals("notes", FileReceiverActivity.sanitizeFileName("notes"));

        // Path separators are stripped so the file cannot escape the downloads directory.
        Assert.assertEquals("c.txt", FileReceiverActivity.sanitizeFileName("a/b/c.txt"));
        Assert.assertEquals("passwd", FileReceiverActivity.sanitizeFileName("../../etc/passwd"));
        Assert.assertEquals("file.txt", FileReceiverActivity.sanitizeFileName("dir\\file.txt"));

        // Surrounding whitespace is trimmed.
        Assert.assertEquals("spaced.txt", FileReceiverActivity.sanitizeFileName("  spaced.txt  "));

        // Candidates that cannot yield a valid basename return null.
        Assert.assertNull(FileReceiverActivity.sanitizeFileName(null));
        Assert.assertNull(FileReceiverActivity.sanitizeFileName(""));
        Assert.assertNull(FileReceiverActivity.sanitizeFileName("   "));
        Assert.assertNull(FileReceiverActivity.sanitizeFileName("/"));
        Assert.assertNull(FileReceiverActivity.sanitizeFileName("dir/"));
        Assert.assertNull(FileReceiverActivity.sanitizeFileName("."));
        Assert.assertNull(FileReceiverActivity.sanitizeFileName(".."));
    }

    /** Text-share scenario: file name derivation from the subject/title. */
    @Test
    public void testGetReceivedTextFileName() {
        // Shared text gets a .txt extension when the subject has none.
        Assert.assertEquals("My Notes.txt", FileReceiverActivity.getReceivedTextFileName("My Notes"));
        // A subject that already has an extension is left untouched (no double extension).
        Assert.assertEquals("notes.md", FileReceiverActivity.getReceivedTextFileName("notes.md"));
        // Path separators in the subject are stripped to a basename.
        Assert.assertEquals("b.txt", FileReceiverActivity.getReceivedTextFileName("a/b"));
        // Missing/blank subjects fall back to a default name.
        Assert.assertEquals("received_text.txt", FileReceiverActivity.getReceivedTextFileName(null));
        Assert.assertEquals("received_text.txt", FileReceiverActivity.getReceivedTextFileName(""));
        Assert.assertEquals("received_text.txt", FileReceiverActivity.getReceivedTextFileName("   "));
    }

    /** File-uri and content-uri scenario: first valid candidate wins, otherwise fall back. */
    @Test
    public void testGetReceivedFileName() {
        // First valid candidate wins (e.g. content uri DISPLAY_NAME over title over uri basename).
        Assert.assertEquals("a.bin", FileReceiverActivity.getReceivedFileName("a.bin", "b.bin"));
        // Invalid candidates are skipped.
        Assert.assertEquals("title.bin", FileReceiverActivity.getReceivedFileName(null, "title.bin"));
        Assert.assertEquals("real.bin", FileReceiverActivity.getReceivedFileName("dir/", "real.bin"));
        // Path separators in a candidate are reduced to the basename.
        Assert.assertEquals("p.bin", FileReceiverActivity.getReceivedFileName("../../p.bin"));
        // All-invalid candidates fall back to the default.
        Assert.assertEquals("received_file", FileReceiverActivity.getReceivedFileName(null, "", "/"));
        Assert.assertEquals("received_file", FileReceiverActivity.getReceivedFileName());
    }

    /** Duplicate names (e.g. two files / two text shares with the same title) must not overwrite. */
    @Test
    public void testSaveStreamDoesNotOverwriteOnNameConflict() throws IOException {
        File dir = tempFolder.getRoot();

        File first = FileReceiverActivity.saveStreamToFile(stream("first"), dir, "report.pdf");
        File second = FileReceiverActivity.saveStreamToFile(stream("second"), dir, "report.pdf");
        File third = FileReceiverActivity.saveStreamToFile(stream("third"), dir, "report.pdf");

        // Each save produces a distinct file; the counter is inserted before the extension.
        Assert.assertEquals("report.pdf", first.getName());
        Assert.assertEquals("report (1).pdf", second.getName());
        Assert.assertEquals("report (2).pdf", third.getName());

        // The originals are preserved with their own content.
        Assert.assertEquals("first", read(first));
        Assert.assertEquals("second", read(second));
        Assert.assertEquals("third", read(third));
    }

    /** Names without an extension must also get a conflict-free variant rather than overwriting. */
    @Test
    public void testSaveStreamHandlesNamesWithoutExtensionOnConflict() throws IOException {
        File dir = tempFolder.getRoot();

        File first = FileReceiverActivity.saveStreamToFile(stream("a"), dir, "data");
        File second = FileReceiverActivity.saveStreamToFile(stream("b"), dir, "data");

        Assert.assertEquals("data", first.getName());
        Assert.assertEquals("data (1)", second.getName());
        Assert.assertEquals("a", read(first));
        Assert.assertEquals("b", read(second));
    }

    /** A malicious display name / subject with path separators must not escape the target dir. */
    @Test
    public void testSaveStreamSanitizesPathSeparatorsAndStaysInDir() throws IOException {
        File dir = tempFolder.getRoot();

        File outFile = FileReceiverActivity.saveStreamToFile(stream("payload"), dir, "../escape.txt");

        Assert.assertEquals("escape.txt", outFile.getName());
        Assert.assertEquals(dir.getCanonicalFile(), outFile.getCanonicalFile().getParentFile());
        Assert.assertTrue(outFile.exists());
        Assert.assertEquals("payload", read(outFile));
        // Nothing leaked into the parent directory.
        Assert.assertFalse(new File(dir.getParentFile(), "escape.txt").exists());
    }

    /** The returned file (used to open the editor and the directory) is the one actually written. */
    @Test
    public void testSaveStreamReturnsActualWrittenFile() throws IOException {
        File dir = tempFolder.getRoot();

        FileReceiverActivity.saveStreamToFile(stream("first"), dir, "doc.txt");
        File second = FileReceiverActivity.saveStreamToFile(stream("second"), dir, "doc.txt");

        // The editor/open-directory entry points at the renamed file inside the target dir.
        Assert.assertEquals("doc (1).txt", second.getName());
        Assert.assertEquals(dir.getCanonicalFile(), second.getCanonicalFile().getParentFile());
        Assert.assertEquals("second", read(second));
    }

    @Test
    public void testSaveStreamRejectsInvalidName() {
        File dir = tempFolder.getRoot();
        try {
            FileReceiverActivity.saveStreamToFile(stream("x"), dir, "/");
            Assert.fail("Expected IOException for invalid file name");
        } catch (IOException expected) {
            // expected
        }
    }

}
