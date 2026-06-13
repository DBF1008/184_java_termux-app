package com.termux.shared.shell.command.result;

import org.junit.Test;

import com.termux.shared.errors.Errno;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Tests for {@link ResultData} operations: state failure, stdout/stderr, and error codes.
 */
public class ResultDataTest {

    @Test
    public void testSetStateFailed_addsError() {
        ResultData rd = new ResultData();
        assertTrue(rd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "test error"));
        assertTrue(rd.isStateFailed());
        assertEquals(1, rd.errorsList.size());
        assertEquals(Errno.ERRNO_FAILED.getCode(), rd.getErrCode());
    }

    @Test
    public void testSetStateFailed_multipleErrors() {
        ResultData rd = new ResultData();
        rd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "error1");
        rd.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), "error2");

        assertEquals(2, rd.errorsList.size());
        // getErrCode returns last error's code
        assertEquals(Errno.ERRNO_CANCELLED.getCode(), rd.getErrCode());
    }

    @Test
    public void testGetErrCode_noErrors_returnsSuccess() {
        ResultData rd = new ResultData();
        assertEquals(Errno.ERRNO_SUCCESS.getCode(), rd.getErrCode());
    }

    @Test
    public void testIsStateFailed_noErrors_returnsFalse() {
        ResultData rd = new ResultData();
        assertFalse(rd.isStateFailed());
    }

    @Test
    public void testStdoutAppend() {
        ResultData rd = new ResultData();
        rd.appendStdout("hello");
        assertEquals("hello", rd.stdout.toString());

        rd.appendStdoutLn("world");
        assertEquals("helloworld\n", rd.stdout.toString());
    }

    @Test
    public void testStderrAppend() {
        ResultData rd = new ResultData();
        rd.appendStderr("err1");
        assertEquals("err1", rd.stderr.toString());

        rd.appendStderrLn("err2");
        assertEquals("err1err2\n", rd.stderr.toString());
    }

    @Test
    public void testClearStdout() {
        ResultData rd = new ResultData();
        rd.appendStdout("data");
        assertFalse(rd.stdout.toString().isEmpty());
        rd.clearStdout();
        assertTrue(rd.stdout.toString().isEmpty());
    }

    @Test
    public void testClearStderr() {
        ResultData rd = new ResultData();
        rd.appendStderr("data");
        assertFalse(rd.stderr.toString().isEmpty());
        rd.clearStderr();
        assertTrue(rd.stderr.toString().isEmpty());
    }

    @Test
    public void testPrependStdout() {
        ResultData rd = new ResultData();
        rd.appendStdout("world");
        rd.prependStdout("hello ");
        assertEquals("hello world", rd.stdout.toString());
    }

    @Test
    public void testPrependStdoutLn() {
        ResultData rd = new ResultData();
        rd.appendStdout("world");
        rd.prependStdoutLn("hello");
        assertEquals("hello\nworld", rd.stdout.toString());
    }

    @Test
    public void testExitCode() {
        ResultData rd = new ResultData();
        assertNull(rd.exitCode);

        rd.exitCode = 0;
        assertEquals(Integer.valueOf(0), rd.exitCode);

        rd.exitCode = 137;
        assertEquals(Integer.valueOf(137), rd.exitCode);
    }

    @Test
    public void testSetStateFailed_withError() {
        ResultData rd = new ResultData();
        com.termux.shared.errors.Error error = new com.termux.shared.errors.Error();
        error.setStateFailed(null, Errno.ERRNO_FAILED.getCode(), "custom error", null);
        rd.setStateFailed(error);

        assertTrue(rd.isStateFailed());
        assertEquals(1, rd.errorsList.size());
    }

    @Test
    public void testSetStateFailed_withThrowable() {
        ResultData rd = new ResultData();
        Exception ex = new IOException("test");
        assertTrue(rd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "io error", ex));

        assertTrue(rd.isStateFailed());
        assertEquals(1, rd.errorsList.size());
    }

    @Test
    public void testGetErrorsListMinimalString() {
        ResultData rd = new ResultData();
        assertEquals("", ResultData.getErrorsListMinimalString(rd));

        rd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "fail1");
        String minimal = ResultData.getErrorsListMinimalString(rd);
        assertNotNull(minimal);
        assertFalse(minimal.isEmpty());
    }
}
