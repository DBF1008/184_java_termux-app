package com.termux.app;

import com.termux.shared.file.FileUtils;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Regression tests for the idempotent bootstrap install/retry recovery logic in {@link TermuxInstaller}.
 *
 * These exercise the path-parameterized helpers ({@link TermuxInstaller#clearBootstrapStagingAndPrefixDirectories},
 * {@link TermuxInstaller#migrateStagingToPrefixDirectory} and {@link TermuxInstaller#isExistingPrefixDirectoryValid})
 * against a real temporary directory tree, covering the three required scenarios:
 *
 * <ul>
 *     <li>First install onto a clean filesystem.</li>
 *     <li>Retry after an extraction/migration was interrupted mid-way (leftover partial staging/prefix).</li>
 *     <li>An already valid prefix must never be deleted.</li>
 * </ul>
 *
 * Robolectric is used because {@link FileUtils} resolves file types via {@code android.system.Os}.
 */
@RunWith(RobolectricTestRunner.class)
public class TermuxInstallerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File stagingDir() {
        return new File(tempFolder.getRoot(), "usr-staging");
    }

    private File prefixDir() {
        return new File(tempFolder.getRoot(), "usr");
    }

    private void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        Assert.assertTrue(parent.exists() || parent.mkdirs());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** Populate a directory tree resembling an extracted/installed bootstrap. */
    private void populateAsInstalled(File dir) throws IOException {
        writeFile(new File(dir, "bin/login"), "#!/bin/sh\n");
        writeFile(new File(dir, "etc/termux/termux.env"), "export PREFIX=/x\n");
        writeFile(new File(dir, "lib/libfoo.so"), "binary");
    }

    /** Scenario 1: first install onto a clean filesystem. */
    @Test
    public void firstInstall_movesStagingOntoMissingPrefix() throws Exception {
        File staging = stagingDir();
        File prefix = prefixDir();

        // (1) On a clean first install nothing exists yet; clearing must be a no-op without error.
        Assert.assertNull(TermuxInstaller.clearBootstrapStagingAndPrefixDirectories(
            staging.getAbsolutePath(), prefix.getAbsolutePath()));

        // (2) Simulate a successful extraction into the staging directory.
        populateAsInstalled(staging);
        Assert.assertFalse("prefix must not exist before migration", prefix.exists());

        // (3) Migrate staging -> prefix.
        Assert.assertNull(TermuxInstaller.migrateStagingToPrefixDirectory(
            staging.getAbsolutePath(), prefix.getAbsolutePath()));

        // The prefix now contains the extracted contents and the staging directory is consumed.
        Assert.assertTrue(new File(prefix, "bin/login").isFile());
        Assert.assertTrue(new File(prefix, "etc/termux/termux.env").isFile());
        Assert.assertFalse("staging must be consumed by the move", staging.exists());
        Assert.assertTrue(TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath()));
    }

    /** Scenario 2: retry after an interrupted extraction leaves a partial staging and an empty prefix. */
    @Test
    public void retryAfterInterruptedExtraction_recoversCleanly() throws Exception {
        File staging = stagingDir();
        File prefix = prefixDir();

        // State left behind by an interrupted attempt: a partially extracted staging directory and an
        // empty leftover prefix directory (as would be left by a premature prefix creation).
        writeFile(new File(staging, "bin/half-extracted"), "partial");
        Assert.assertTrue(prefix.mkdirs());

        // The empty leftover prefix must NOT be mistaken for a valid installation.
        Assert.assertFalse("empty leftover prefix is not a valid install",
            TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath()));

        // (1) The retry clears both leftovers idempotently.
        Assert.assertNull(TermuxInstaller.clearBootstrapStagingAndPrefixDirectories(
            staging.getAbsolutePath(), prefix.getAbsolutePath()));
        Assert.assertFalse("leftover staging must be removed", staging.exists());
        Assert.assertFalse("leftover prefix must be removed", prefix.exists());

        // (2) A fresh extraction + migration now succeeds.
        populateAsInstalled(staging);
        Assert.assertNull(TermuxInstaller.migrateStagingToPrefixDirectory(
            staging.getAbsolutePath(), prefix.getAbsolutePath()));
        Assert.assertTrue(new File(prefix, "bin/login").isFile());
        Assert.assertFalse("the partial file from the interrupted attempt must be gone",
            new File(prefix, "bin/half-extracted").exists());
        Assert.assertTrue(TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath()));
    }

    /**
     * Migration must be reliable and idempotent even if a (stale) prefix directory exists at the
     * destination, replacing its contents entirely rather than failing or merging.
     */
    @Test
    public void migrate_overwritesLeftoverPrefixDirectory() throws Exception {
        File staging = stagingDir();
        File prefix = prefixDir();

        populateAsInstalled(staging);
        // A stale prefix directory with old content already exists at the destination.
        writeFile(new File(prefix, "bin/old-binary"), "old");
        writeFile(new File(prefix, "stale-marker"), "old");

        Assert.assertNull(TermuxInstaller.migrateStagingToPrefixDirectory(
            staging.getAbsolutePath(), prefix.getAbsolutePath()));

        // New content replaced the old content; stale files are gone and staging is consumed.
        Assert.assertTrue(new File(prefix, "bin/login").isFile());
        Assert.assertFalse("stale prefix marker must be replaced", new File(prefix, "stale-marker").exists());
        Assert.assertFalse("stale prefix binary must be replaced", new File(prefix, "bin/old-binary").exists());
        Assert.assertFalse("staging must be consumed by the move", staging.exists());
    }

    /** Scenario 3: an already valid prefix must be detected and never deleted. */
    @Test
    public void validExistingPrefix_isPreservedAndNotDeleted() throws Exception {
        File staging = stagingDir();
        File prefix = prefixDir();

        populateAsInstalled(prefix);

        // A populated prefix is recognized as a valid installation that must be kept.
        Assert.assertTrue(TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath()));

        // setupBootstrapIfNeeded() only clears directories when the prefix is NOT valid. Emulate that
        // gate and assert the valid prefix is left completely untouched.
        if (!TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath())) {
            TermuxInstaller.clearBootstrapStagingAndPrefixDirectories(
                staging.getAbsolutePath(), prefix.getAbsolutePath());
        }

        Assert.assertTrue("valid prefix must not be deleted", new File(prefix, "bin/login").isFile());
        Assert.assertTrue(new File(prefix, "etc/termux/termux.env").isFile());
        Assert.assertTrue(new File(prefix, "lib/libfoo.so").isFile());
    }

    /** A missing or empty prefix is not valid, so installation must proceed. */
    @Test
    public void emptyOrMissingPrefix_isNotValid() throws Exception {
        File prefix = prefixDir();

        Assert.assertFalse("missing prefix is not valid",
            TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath()));

        Assert.assertTrue(prefix.mkdirs());
        Assert.assertFalse("empty prefix is not valid",
            TermuxInstaller.isExistingPrefixDirectoryValid(prefix.getAbsolutePath()));
    }
}
