package com.termux.shared.shell.command.result;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ResultConfig} — pending result detection.
 */
public class ResultConfigTest {

    @Test
    public void testIsCommandWithPendingResult_noConfig_returnsFalse() {
        ResultConfig rc = new ResultConfig();
        assertFalse(rc.isCommandWithPendingResult());
    }

    @Test
    public void testIsCommandWithPendingResult_withDirectory_returnsTrue() {
        ResultConfig rc = new ResultConfig();
        rc.resultDirectoryPath = "/data/result";
        assertTrue(rc.isCommandWithPendingResult());
    }

    @Test
    public void testResultConfigDefaults() {
        ResultConfig rc = new ResultConfig();
        assertNull(rc.resultPendingIntent);
        assertNull(rc.resultBundleKey);
        assertNull(rc.resultStdoutKey);
        assertNull(rc.resultStderrKey);
        assertNull(rc.resultExitCodeKey);
        assertNull(rc.resultErrCodeKey);
        assertNull(rc.resultErrmsgKey);
        assertNull(rc.resultDirectoryPath);
        assertNull(rc.resultDirectoryAllowedParentPath);
        assertFalse(rc.resultSingleFile);
        assertNull(rc.resultFileBasename);
        assertNull(rc.resultFileOutputFormat);
        assertNull(rc.resultFileErrorFormat);
        assertNull(rc.resultFilesSuffix);
    }
}
