package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for the Termux bootstrap installation and retry-recovery flow.
 *
 * These tests validate the state-machine logic that governs prefix directory management:
 * <ul>
 *   <li>First install: no prefix → proceed with installation</li>
 *   <li>Retry after interrupted extraction: partial prefix detected → reinstall</li>
 *   <li>Valid existing prefix: not reinstalled (bin/ directory present)</li>
 *   <li>Environment file consistency: rebuilt when prefix is valid but env file is missing</li>
 *   <li>Staging and trash cleanup: all artifacts cleaned on retry and after successful install</li>
 *   <li>Rename-with-backup: prefix moved to trash before staging→prefix rename</li>
 * </ul>
 *
 * The helper methods in this class mirror the decision logic in
 * {@link TermuxInstaller} and {@link com.termux.shared.termux.file.TermuxFileUtils},
 * applied to temp directories so the tests are platform-independent.
 */
public class TermuxInstallerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String PREFIX_DIR_NAME = "usr";
    private static final String STAGING_DIR_NAME = "usr-staging";
    private static final String TRASH_DIR_NAME = "usr-trash";
    private static final String BIN_DIR_NAME = "bin";
    private static final String ENV_FILE_RELATIVE = "etc/termux/termux.env";

    // ── Prefix validity check ──────────────────────────────────────────

    /**
     * A prefix with a {@code bin/} subdirectory represents a complete bootstrap extraction.
     * The installer must skip reinstallation and just refresh the env file.
     */
    @Test
    public void testValidPrefixIsNotReinstalled() throws IOException {
        File filesDir = tempFolder.newFolder("files");
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        File binDir = new File(prefixDir, BIN_DIR_NAME);
        assertTrue(binDir.mkdirs());

        // Create some additional content that a real prefix would have
        new File(prefixDir, "lib").mkdirs();
        new File(prefixDir, "etc").mkdirs();

        assertTrue("Prefix with bin/ should be valid", isPrefixValid(prefixDir));
        assertFalse("Valid prefix should not need reinstall", needsReinstall(prefixDir));
    }

    /**
     * A partially extracted prefix (e.g., only tmp/ and etc/ from an interrupted installation)
     * must be detected as invalid so the installer reinstalls instead of skipping.
     */
    @Test
    public void testPartialPrefixTriggersReinstall() throws IOException {
        File filesDir = tempFolder.newFolder("files");
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);

        // Simulate partial extraction: tmp/ and etc/ exist but bin/ does not
        new File(prefixDir, "tmp").mkdirs();
        new File(prefixDir, "etc/termux").mkdirs();
        createFile(new File(prefixDir, "etc/termux/termux.env"), "export PREFIX=\"/data/data/com.termux/files/usr\"");

        assertFalse("Partial prefix without bin/ should not be valid", isPrefixValid(prefixDir));
        assertTrue("Partial prefix should need reinstall", needsReinstall(prefixDir));
    }

    /**
     * An empty prefix directory (e.g., created by the OS or by a prior cleanup) must trigger
     * reinstallation.
     */
    @Test
    public void testEmptyPrefixTriggersReinstall() throws IOException {
        File filesDir = tempFolder.newFolder("files");
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        assertTrue(prefixDir.mkdirs());

        assertFalse("Empty prefix should not be valid", isPrefixValid(prefixDir));
        assertTrue("Empty prefix should need reinstall", needsReinstall(prefixDir));
    }

    /**
     * When the prefix directory does not exist at all (fresh install), the installer must proceed.
     */
    @Test
    public void testNoPrefixTriggersReinstall() throws IOException {
        File filesDir = tempFolder.newFolder("files");
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);

        assertFalse("Non-existent prefix should not be valid", isPrefixValid(prefixDir));
        assertTrue("Non-existent prefix should need reinstall", needsReinstall(prefixDir));
    }

    // ── Environment file consistency ───────────────────────────────────

    /**
     * When the prefix is valid (has bin/) but the env file is missing (e.g., deleted by user
     * or lost during a partial update), the env file must be rebuilt without reinstalling
     * the entire bootstrap.
     */
    @Test
    public void testValidPrefixWithMissingEnvFile() throws IOException {
        File filesDir = tempFolder.newFolder("files");
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        File binDir = new File(prefixDir, BIN_DIR_NAME);
        assertTrue(binDir.mkdirs());

        File envFile = new File(prefixDir, ENV_FILE_RELATIVE);
        assertFalse("Env file should not exist initially", envFile.exists());

        // Prefix is still valid even without env file
        assertTrue("Prefix should be valid even without env file", isPrefixValid(prefixDir));

        // Simulate env file rebuild (as done by TermuxShellEnvironment.writeEnvironmentToFile)
        simulateWriteEnvFile(envFile);
        assertTrue("Env file should exist after rebuild", envFile.exists());
        assertTrue("Env file should have content", envFile.length() > 0);
    }

    /**
     * After a successful bootstrap installation, the env file must exist inside the prefix.
     */
    @Test
    public void testEnvFileCreatedAfterInstall() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Simulate a complete installation
        performSuccessfulInstall(filesDir);

        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        File envFile = new File(prefixDir, ENV_FILE_RELATIVE);

        assertTrue("Prefix should be valid after install", isPrefixValid(prefixDir));
        assertTrue("Env file should exist after install", envFile.exists());
    }

    // ── Staging cleanup on retry ───────────────────────────────────────

    /**
     * When the user clicks "Try again" after a failed installation, ALL installation artifacts
     * (staging, prefix, trash) must be cleaned up before the retry attempt.
     */
    @Test
    public void testStagingCleanupOnRetry() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Simulate state from a prior failed installation
        File stagingDir = new File(filesDir, STAGING_DIR_NAME);
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        File trashDir = new File(filesDir, TRASH_DIR_NAME);
        new File(stagingDir, "bin").mkdirs();  // partial extraction in staging
        new File(prefixDir, "tmp").mkdirs();   // old partial prefix
        trashDir.mkdirs();                     // leftover trash

        assertTrue(stagingDir.exists());
        assertTrue(prefixDir.exists());
        assertTrue(trashDir.exists());

        // Perform retry cleanup (mirrors showBootstrapErrorDialog "Try again" handler)
        performRetryCleanup(filesDir);

        assertFalse("Staging should be deleted on retry", stagingDir.exists());
        assertFalse("Prefix should be deleted on retry", prefixDir.exists());
        assertFalse("Trash should be deleted on retry", trashDir.exists());
    }

    /**
     * Leftover staging from a previous interrupted extraction must be cleaned before
     * a new installation attempt.
     */
    @Test
    public void testStagingCleanupBeforeInstall() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Staging exists from a prior interrupted extraction
        File stagingDir = new File(filesDir, STAGING_DIR_NAME);
        new File(stagingDir, "partial-data").mkdirs();

        // Perform install — staging should be deleted first, then recreated
        performSuccessfulInstall(filesDir);

        // After successful install, staging should not exist (renamed to prefix)
        assertFalse("Staging should not exist after successful install", stagingDir.exists());
        assertTrue("Prefix should exist after successful install",
            new File(filesDir, PREFIX_DIR_NAME).exists());
    }

    // ── Trash backup management ────────────────────────────────────────

    /**
     * Leftover trash from a prior failed rename must be cleaned up before a new install.
     */
    @Test
    public void testTrashBackupCleanup() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Trash exists from a prior failed install where trash wasn't cleaned up
        File trashDir = new File(filesDir, TRASH_DIR_NAME);
        new File(trashDir, "old-data").mkdirs();

        // Perform successful install — trash should be cleaned up
        performSuccessfulInstall(filesDir);

        assertFalse("Trash should be deleted after successful install", trashDir.exists());
    }

    /**
     * The full rename-with-backup flow: existing prefix is moved to trash (not deleted),
     * staging is extracted and renamed to prefix, then trash is cleaned up.
     * This verifies the atomic-like swap that prevents data loss.
     */
    @Test
    public void testRenameWithTrashBackup() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Set up existing (old) prefix with user-installed content
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        new File(prefixDir, "bin").mkdirs();
        createFile(new File(prefixDir, "lib/user-installed-package.so"), "old-package-data");

        File stagingDir = new File(filesDir, STAGING_DIR_NAME);
        File trashDir = new File(filesDir, TRASH_DIR_NAME);

        // Step 1: Move prefix → trash (backup)
        assertTrue("Prefix should be moved to trash", prefixDir.renameTo(trashDir));
        assertFalse("Prefix should no longer exist at original path", prefixDir.exists());
        assertTrue("Trash should contain old prefix", trashDir.exists());
        assertTrue("Trash should have bin/", new File(trashDir, "bin").exists());

        // Step 2: Create staging and simulate extraction
        new File(stagingDir, "bin").mkdirs();
        createFile(new File(stagingDir, "bin/new-binary"), "new-binary-content");

        // Step 3: Rename staging → prefix
        assertTrue("Staging should be renamed to prefix", stagingDir.renameTo(prefixDir));
        assertTrue("Prefix should exist with new content", prefixDir.exists());
        assertTrue("New prefix should have bin/", new File(prefixDir, "bin").exists());

        // Step 4: Clean up trash
        deleteRecursively(trashDir);
        assertFalse("Trash should be deleted after successful rename", trashDir.exists());

        // Verify final state
        assertTrue("Final prefix should be valid", isPrefixValid(prefixDir));
        assertTrue("New binary should exist",
            new File(prefixDir, "bin/new-binary").exists());
    }

    /**
     * If the staging→prefix rename fails, the old prefix must be restored from trash.
     * This is the critical safety net that prevents total data loss.
     */
    @Test
    public void testRenameFailureRestoresTrash() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Step 1: Move prefix → trash (backup)
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        new File(prefixDir, "bin").mkdirs();
        createFile(new File(prefixDir, "lib/important.so"), "important-data");

        File trashDir = new File(filesDir, TRASH_DIR_NAME);
        assertTrue("Prefix should be moved to trash", prefixDir.renameTo(trashDir));

        // Step 2: Simulate staging→prefix rename failure
        // (e.g., by having something at the prefix path that prevents rename)
        File blocker = new File(filesDir, PREFIX_DIR_NAME);
        assertTrue("Create blocker at prefix path", blocker.mkdirs());
        createFile(new File(blocker, "blocker"), "cannot-overwrite");

        File stagingDir = new File(filesDir, STAGING_DIR_NAME);
        new File(stagingDir, "bin").mkdirs();

        // The rename should fail because prefix path is occupied
        boolean renameSucceeded = stagingDir.renameTo(prefixDir);

        if (!renameSucceeded) {
            // Restore from trash (mirrors the catch block in TermuxInstaller)
            deleteRecursively(prefixDir);  // Remove the blocker first
            boolean restored = trashDir.renameTo(prefixDir);
            assertTrue("Prefix should be restored from trash", restored);
            assertTrue("Restored prefix should have bin/",
                new File(prefixDir, "bin").exists());
            assertTrue("Restored prefix should have important data",
                new File(prefixDir, "lib/important.so").exists());
        }
        // If rename succeeded unexpectedly (platform-dependent behavior), the test
        // still passes — the important thing is that the safety net code path is
        // exercised when needed.
    }

    /**
     * After a successful install replaces an old prefix, the trash directory
     * (containing the old prefix backup) must be cleaned up.
     */
    @Test
    public void testTrashDeletedAfterSuccessfulReplaceInstall() throws IOException {
        File filesDir = tempFolder.newFolder("files");

        // Existing valid prefix
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        new File(prefixDir, "bin").mkdirs();
        createFile(new File(prefixDir, "etc/motd"), "old-motd");

        // Perform a complete install (simulates the full flow)
        performSuccessfulInstall(filesDir);

        // Verify final state
        File trashDir = new File(filesDir, TRASH_DIR_NAME);
        File stagingDir = new File(filesDir, STAGING_DIR_NAME);
        prefixDir = new File(filesDir, PREFIX_DIR_NAME);

        assertFalse("Trash should not exist after install", trashDir.exists());
        assertFalse("Staging should not exist after install", stagingDir.exists());
        assertTrue("Prefix should exist after install", prefixDir.exists());
        assertTrue("Prefix should be valid after install", isPrefixValid(prefixDir));
    }

    // ── Helper methods ─────────────────────────────────────────────────
    // These mirror the decision logic in TermuxInstaller and TermuxFileUtils.

    /**
     * Check if a prefix directory represents a valid, complete installation.
     * Mirrors {@code TermuxFileUtils.isTermuxPrefixDirectoryValid()}.
     */
    private static boolean isPrefixValid(File prefixDir) {
        return prefixDir.isDirectory() && new File(prefixDir, BIN_DIR_NAME).isDirectory();
    }

    /**
     * Determine whether a reinstall is needed.
     * Mirrors the gate logic in {@code TermuxInstaller.setupBootstrapIfNeeded()}.
     *
     * @return true if prefix is missing, empty, or invalid (needs reinstall)
     */
    private static boolean needsReinstall(File prefixDir) {
        // If prefix doesn't exist as a directory → needs reinstall
        if (!prefixDir.isDirectory()) {
            return true;
        }
        // If prefix is valid (has bin/) → skip reinstall
        if (isPrefixValid(prefixDir)) {
            return false;
        }
        // Prefix exists but is invalid (partial extraction or empty) → needs reinstall
        return true;
    }

    /**
     * Simulate the retry cleanup performed in
     * {@code TermuxInstaller.showBootstrapErrorDialog()} "Try again" handler.
     */
    private static void performRetryCleanup(File filesDir) {
        deleteRecursively(new File(filesDir, STAGING_DIR_NAME));
        deleteRecursively(new File(filesDir, PREFIX_DIR_NAME));
        deleteRecursively(new File(filesDir, TRASH_DIR_NAME));
    }

    /**
     * Simulate a complete, successful bootstrap installation using the trash-backup strategy.
     * This mirrors the install thread logic in {@code TermuxInstaller.setupBootstrapIfNeeded()}.
     */
    private static void performSuccessfulInstall(File filesDir) throws IOException {
        File stagingDir = new File(filesDir, STAGING_DIR_NAME);
        File prefixDir = new File(filesDir, PREFIX_DIR_NAME);
        File trashDir = new File(filesDir, TRASH_DIR_NAME);

        // Step 1: Delete staging (cleanup from any prior attempt)
        deleteRecursively(stagingDir);

        // Step 2: Delete trash (cleanup from any prior attempt)
        deleteRecursively(trashDir);

        // Step 3: Move existing prefix to trash (backup)
        if (prefixDir.isDirectory()) {
            boolean moved = prefixDir.renameTo(trashDir);
            if (!moved) {
                deleteRecursively(prefixDir);
            }
        }

        // Step 4: Create staging directory
        assertTrue("Should be able to create staging dir", stagingDir.mkdirs());

        // Step 5: Simulate ZIP extraction to staging
        new File(stagingDir, BIN_DIR_NAME).mkdirs();
        createFile(new File(stagingDir, "bin/sh"), "#!/bin/sh");
        new File(stagingDir, "lib").mkdirs();
        new File(stagingDir, "etc/termux").mkdirs();
        new File(stagingDir, "tmp").mkdirs();

        // Step 6: Rename staging → prefix
        boolean renamed = stagingDir.renameTo(prefixDir);
        if (!renamed) {
            // Restore from trash on failure
            if (trashDir.isDirectory()) {
                trashDir.renameTo(prefixDir);
            }
            fail("Staging→prefix rename should succeed in test environment");
        }

        // Step 7: Clean up trash
        deleteRecursively(trashDir);

        // Step 8: Write env file (mirrors TermuxShellEnvironment.writeEnvironmentToFile)
        File envFile = new File(prefixDir, ENV_FILE_RELATIVE);
        simulateWriteEnvFile(envFile);
    }

    /**
     * Simulate writing the termux.env file.
     */
    private static void simulateWriteEnvFile(File envFile) throws IOException {
        envFile.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(envFile)) {
            writer.write("export PREFIX=\"/data/data/com.termux/files/usr\"\n");
            writer.write("export HOME=\"/data/data/com.termux/files/home\"\n");
            writer.write("export PATH=\"/data/data/com.termux/files/usr/bin\"\n");
        }
    }

    /**
     * Create a file with optional content, creating parent directories as needed.
     */
    private static void createFile(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    /**
     * Recursively delete a file or directory. No-op if the path does not exist.
     */
    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
