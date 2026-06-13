package com.termux.app.api.file;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class FileReceiverActivityTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // ========================================================================
    // Existing tests
    // ========================================================================

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

    // ========================================================================
    // sanitizeFileName tests
    // ========================================================================

    @Test
    public void testSanitizeFileName_null() {
        Assert.assertNull(FileReceiverActivity.sanitizeFileName(null));
    }

    @Test
    public void testSanitizeFileName_normalName() {
        Assert.assertEquals("report.pdf", FileReceiverActivity.sanitizeFileName("report.pdf"));
    }

    @Test
    public void testSanitizeFileName_forwardSlash() {
        // Forward slashes (path separator) must be replaced
        Assert.assertEquals("path_to_file.txt", FileReceiverActivity.sanitizeFileName("path/to/file.txt"));
    }

    @Test
    public void testSanitizeFileName_backslash() {
        // Backslashes (Windows path separator) must be replaced
        Assert.assertEquals("path_to_file.txt", FileReceiverActivity.sanitizeFileName("path\\to\\file.txt"));
    }

    @Test
    public void testSanitizeFileName_mixedSeparators() {
        Assert.assertEquals("a_b_c_d.txt", FileReceiverActivity.sanitizeFileName("a/b\\c/d\\e.txt"));
    }

    @Test
    public void testSanitizeFileName_unsafeCharacters() {
        // All unsafe characters : * ? " < > | should be replaced
        Assert.assertEquals("a_b_c_d_e_f_g.txt",
            FileReceiverActivity.sanitizeFileName("a:b*c?d\"e<f>g|.txt"));
    }

    @Test
    public void testSanitizeFileName_preservesDots() {
        Assert.assertEquals("my.file.name.tar.gz",
            FileReceiverActivity.sanitizeFileName("my.file.name.tar.gz"));
    }

    @Test
    public void testSanitizeFileName_preservesSpaces() {
        Assert.assertEquals("my document (final).pdf",
            FileReceiverActivity.sanitizeFileName("my document (final).pdf"));
    }

    @Test
    public void testSanitizeFileName_onlySeparators() {
        // All slashes become underscores - not empty
        Assert.assertEquals("___", FileReceiverActivity.sanitizeFileName("///"));
    }

    @Test
    public void testSanitizeFileName_emptyString() {
        Assert.assertEquals("", FileReceiverActivity.sanitizeFileName(""));
    }

    @Test
    public void testSanitizeFileName_leadingDot() {
        Assert.assertEquals(".hidden", FileReceiverActivity.sanitizeFileName(".hidden"));
    }

    // ========================================================================
    // getUniqueFileName tests
    // ========================================================================

    @Test
    public void testGetUniqueFileName_noConflict() {
        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "report.pdf");
        Assert.assertEquals("report.pdf", result.getName());
        Assert.assertFalse(result.exists());
    }

    @Test
    public void testGetUniqueFileName_singleConflict() throws IOException {
        // Create the original file
        tempFolder.newFile("report.pdf");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "report.pdf");
        Assert.assertEquals("report (2).pdf", result.getName());
        Assert.assertFalse(result.exists());
    }

    @Test
    public void testGetUniqueFileName_multipleConflicts() throws IOException {
        tempFolder.newFile("report.pdf");
        tempFolder.newFile("report (2).pdf");
        tempFolder.newFile("report (3).pdf");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "report.pdf");
        Assert.assertEquals("report (4).pdf", result.getName());
        Assert.assertFalse(result.exists());
    }

    @Test
    public void testGetUniqueFileName_noExtension() throws IOException {
        tempFolder.newFile("README");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "README");
        Assert.assertEquals("README (2)", result.getName());
    }

    @Test
    public void testGetUniqueFileName_noExtension_multipleConflicts() throws IOException {
        tempFolder.newFile("README");
        tempFolder.newFile("README (2)");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "README");
        Assert.assertEquals("README (3)", result.getName());
    }

    @Test
    public void testGetUniqueFileName_dotFile() throws IOException {
        // Dotfiles (like .bashrc) where dot is at position 0 should be treated as having no extension
        tempFolder.newFile(".bashrc");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), ".bashrc");
        Assert.assertEquals(".bashrc (2)", result.getName());
    }

    @Test
    public void testGetUniqueFileName_multipleExtensions() throws IOException {
        // For "archive.tar.gz", only .gz is treated as the extension
        tempFolder.newFile("archive.tar.gz");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "archive.tar.gz");
        Assert.assertEquals("archive.tar (2).gz", result.getName());
    }

    @Test
    public void testGetUniqueFileName_preservesDirectory() {
        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "test.txt");
        Assert.assertEquals(tempFolder.getRoot().getAbsolutePath(), result.getParentFile().getAbsolutePath());
    }

    @Test
    public void testGetUniqueFileName_fileNameWithSpaces() throws IOException {
        tempFolder.newFile("my document.pdf");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "my document.pdf");
        Assert.assertEquals("my document (2).pdf", result.getName());
    }

    @Test
    public void testGetUniqueFileName_gapInNumbering() throws IOException {
        // If report.pdf and report (3).pdf exist but report (2).pdf doesn't,
        // the algorithm still increments sequentially and finds (2) first
        tempFolder.newFile("report.pdf");
        tempFolder.newFile("report (3).pdf");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "report.pdf");
        Assert.assertEquals("report (2).pdf", result.getName());
    }

    @Test
    public void testGetUniqueFileName_trailingDot() throws IOException {
        // Trailing dot: lastDot is at end, extension is empty string
        tempFolder.newFile("file.");

        File result = FileReceiverActivity.getUniqueFileName(tempFolder.getRoot(), "file.");
        // lastDot > 0, so baseName="file", extension="."
        Assert.assertEquals("file (2).", result.getName());
    }

    // ========================================================================
    // getDisplayNameForTextShare tests
    // ========================================================================

    @Test
    public void testGetDisplayNameForTextShare_null() {
        Assert.assertEquals("shared.txt", FileReceiverActivity.getDisplayNameForTextShare(null));
    }

    @Test
    public void testGetDisplayNameForTextShare_empty() {
        Assert.assertEquals("shared.txt", FileReceiverActivity.getDisplayNameForTextShare(""));
    }

    @Test
    public void testGetDisplayNameForTextShare_whitespace() {
        Assert.assertEquals("shared.txt", FileReceiverActivity.getDisplayNameForTextShare("   "));
    }

    @Test
    public void testGetDisplayNameForTextShare_plainSubject() {
        Assert.assertEquals("Notes.txt", FileReceiverActivity.getDisplayNameForTextShare("Notes"));
    }

    @Test
    public void testGetDisplayNameForTextShare_alreadyTxt() {
        // Should NOT double the .txt extension
        Assert.assertEquals("document.txt", FileReceiverActivity.getDisplayNameForTextShare("document.txt"));
    }

    @Test
    public void testGetDisplayNameForTextShare_uppercaseTxt() {
        Assert.assertEquals("document.TXT", FileReceiverActivity.getDisplayNameForTextShare("document.TXT"));
    }

    @Test
    public void testGetDisplayNameForTextShare_mixedCaseTxt() {
        Assert.assertEquals("document.Txt", FileReceiverActivity.getDisplayNameForTextShare("document.Txt"));
    }

    @Test
    public void testGetDisplayNameForTextShare_nonTxtExtension() {
        // Subject with a non-.txt extension gets .txt appended (text share always saves as .txt)
        Assert.assertEquals("report.pdf.txt", FileReceiverActivity.getDisplayNameForTextShare("report.pdf"));
    }

    @Test
    public void testGetDisplayNameForTextShare_subjectWithSpaces() {
        Assert.assertEquals("My Notes.txt", FileReceiverActivity.getDisplayNameForTextShare("My Notes"));
    }

    // ========================================================================
    // Integration tests: full save pipeline (text, file URI, content URI scenarios)
    // ========================================================================

    /**
     * A testable subclass that overrides the error dialog method to avoid
     * needing real Android UI, and redirects saves to a temp directory.
     */
    private static class TestableFileReceiverActivity extends FileReceiverActivity {
        String lastErrorMessage = null;
        File overrideReceiveDir;

        TestableFileReceiverActivity(File receiveDir) {
            this.overrideReceiveDir = receiveDir;
        }

        @Override
        void showErrorDialogAndQuit(String message) {
            lastErrorMessage = message;
        }

        /**
         * Test-friendly override that uses the temp directory instead of the
         * real (unavailable on test host) Termux receive directory.
         */
        @Override
        public File saveStreamWithName(InputStream in, String attachmentFileName) {
            if (com.termux.shared.data.DataUtils.isNullOrEmpty(attachmentFileName)) {
                showErrorDialogAndQuit("File name cannot be null or empty");
                return null;
            }

            if (!overrideReceiveDir.isDirectory() && !overrideReceiveDir.mkdirs()) {
                showErrorDialogAndQuit("Cannot create directory: " + overrideReceiveDir.getAbsolutePath());
                return null;
            }

            String sanitized = sanitizeFileName(attachmentFileName);
            if (com.termux.shared.data.DataUtils.isNullOrEmpty(sanitized)) {
                showErrorDialogAndQuit("File name is invalid after sanitization: \"" + attachmentFileName + "\"");
                return null;
            }

            File outFile = getUniqueFileName(overrideReceiveDir, sanitized);

            try {
                try (FileOutputStream f = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[4096];
                    int readBytes;
                    while ((readBytes = in.read(buffer)) > 0) {
                        f.write(buffer, 0, readBytes);
                    }
                }
                return outFile;
            } catch (IOException e) {
                showErrorDialogAndQuit("Error saving file:\n\n" + e);
                return null;
            }
        }
    }

    private byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
        }
        return baos.toByteArray();
    }

    @Test
    public void testSave_textShare_plainSubject() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        String fileName = FileReceiverActivity.getDisplayNameForTextShare("Meeting Notes");
        InputStream in = new ByteArrayInputStream("Hello world".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, fileName);

        Assert.assertNotNull(outFile);
        Assert.assertEquals("Meeting Notes.txt", outFile.getName());
        Assert.assertArrayEquals("Hello world".getBytes(StandardCharsets.UTF_8), readAll(outFile));
        Assert.assertNull(activity.lastErrorMessage);
    }

    @Test
    public void testSave_textShare_subjectAlreadyTxt() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        String fileName = FileReceiverActivity.getDisplayNameForTextShare("document.txt");
        InputStream in = new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, fileName);

        Assert.assertNotNull(outFile);
        Assert.assertEquals("document.txt", outFile.getName());
    }

    @Test
    public void testSave_textShare_nullSubject() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        String fileName = FileReceiverActivity.getDisplayNameForTextShare(null);
        InputStream in = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, fileName);

        Assert.assertNotNull(outFile);
        Assert.assertEquals("shared.txt", outFile.getName());
    }

    @Test
    public void testSave_fileUri_normalFile() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // Simulate receiving a file URI share with a normal file name
        InputStream in = new ByteArrayInputStream("file contents".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, "photo.jpg");

        Assert.assertNotNull(outFile);
        Assert.assertEquals("photo.jpg", outFile.getName());
        Assert.assertArrayEquals("file contents".getBytes(StandardCharsets.UTF_8), readAll(outFile));
    }

    @Test
    public void testSave_fileUri_sanitizesSlashes() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // A file name from a URI that contains path separators must be sanitized
        InputStream in = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, "subdir/important.pdf");

        Assert.assertNotNull(outFile);
        Assert.assertEquals("subdir_important.pdf", outFile.getName());
        // Ensure file is in the receive dir, not in a subdirectory
        Assert.assertEquals(dir.getAbsolutePath(), outFile.getParentFile().getAbsolutePath());
    }

    @Test
    public void testSave_contentUri_normalDisplayName() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // Simulate a content URI that returns a normal DISPLAY_NAME
        InputStream in = new ByteArrayInputStream("spreadsheet data".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, "budget_2024.xlsx");

        Assert.assertNotNull(outFile);
        Assert.assertEquals("budget_2024.xlsx", outFile.getName());
    }

    @Test
    public void testSave_contentUri_displayNameWithSlashes() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // Some content providers return DISPLAY_NAME with path-like components
        InputStream in = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, "Download/report.pdf");

        Assert.assertNotNull(outFile);
        Assert.assertEquals("Download_report.pdf", outFile.getName());
        Assert.assertEquals(dir.getAbsolutePath(), outFile.getParentFile().getAbsolutePath());
    }

    @Test
    public void testSave_contentUri_nullDisplayNameFallsBack() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // When DISPLAY_NAME is null and subject fallback is also null,
        // saveStreamWithName should show error
        File outFile = activity.saveStreamWithName(
            new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), null);

        Assert.assertNull(outFile);
        Assert.assertNotNull(activity.lastErrorMessage);
        Assert.assertTrue(activity.lastErrorMessage.contains("null or empty"));
    }

    @Test
    public void testSave_conflictResolution_secondShare() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // First save
        InputStream in1 = new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8));
        File file1 = activity.saveStreamWithName(in1, "report.pdf");
        Assert.assertNotNull(file1);
        Assert.assertEquals("report.pdf", file1.getName());

        // Second save with same name should not overwrite
        InputStream in2 = new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8));
        File file2 = activity.saveStreamWithName(in2, "report.pdf");
        Assert.assertNotNull(file2);
        Assert.assertEquals("report (2).pdf", file2.getName());

        // Both files should exist with correct content
        Assert.assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), readAll(file1));
        Assert.assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), readAll(file2));
    }

    @Test
    public void testSave_conflictResolution_thirdShare() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // Save three files with the same name
        for (int i = 1; i <= 3; i++) {
            InputStream in = new ByteArrayInputStream(("content " + i).getBytes(StandardCharsets.UTF_8));
            File f = activity.saveStreamWithName(in, "data.csv");
            Assert.assertNotNull(f);
            if (i == 1) {
                Assert.assertEquals("data.csv", f.getName());
            } else {
                Assert.assertEquals("data (" + i + ").csv", f.getName());
            }
        }
    }

    @Test
    public void testSave_editorReceivesCorrectPath() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // Pre-existing file causes conflict
        Assert.assertTrue(new File(dir, "notes.txt").createNewFile());

        // The returned File must point to the actual (non-conflicting) saved file
        InputStream in = new ByteArrayInputStream("new note".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, "notes.txt");

        Assert.assertNotNull(outFile);
        Assert.assertEquals("notes (2).txt", outFile.getName());
        Assert.assertTrue(outFile.exists());
        Assert.assertEquals(dir.getAbsolutePath(), outFile.getParentFile().getAbsolutePath());
        // This is the path that would be passed to the editor
        Assert.assertTrue(outFile.getAbsolutePath().endsWith("notes (2).txt"));
    }

    @Test
    public void testSave_openDirectoryPointsToCorrectDir() throws IOException {
        File dir = tempFolder.newFolder("downloads");
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        InputStream in = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        File outFile = activity.saveStreamWithName(in, "test_file.md");

        Assert.assertNotNull(outFile);
        // The "open directory" entry should use the parent of the saved file
        Assert.assertEquals(dir.getAbsolutePath(), outFile.getParentFile().getAbsolutePath());
    }

    @Test
    public void testSave_emptyFileName_returnsNull() {
        File dir = tempFolder.getRoot();
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        File outFile = activity.saveStreamWithName(
            new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), "");

        Assert.assertNull(outFile);
        Assert.assertNotNull(activity.lastErrorMessage);
    }

    @Test
    public void testSave_allUnsafeChars_returnsNull() {
        File dir = tempFolder.getRoot();
        TestableFileReceiverActivity activity = new TestableFileReceiverActivity(dir);

        // A name that is entirely unsafe characters will become all underscores, which is not empty,
        // so it should succeed with the sanitized name
        File outFile = activity.saveStreamWithName(
            new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), "///");

        Assert.assertNotNull(outFile);
        Assert.assertEquals("___", outFile.getName());
    }
}
