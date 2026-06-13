package com.termux.shared.shell.command;

import org.junit.Test;

import com.termux.shared.errors.Errno;
import com.termux.shared.shell.command.ExecutionCommand.ExecutionState;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;

import static org.junit.Assert.*;

/**
 * Tests for {@link ExecutionCommand} core logic: Runner resolution, ShellCreateMode,
 * state machine transitions, and result processing guards.
 */
public class ExecutionCommandTest {

    // ==================== Runner Tests ====================

    @Test
    public void testRunnerOf_validNames() {
        assertEquals(Runner.APP_SHELL, Runner.runnerOf("app-shell"));
        assertEquals(Runner.TERMINAL_SESSION, Runner.runnerOf("terminal-session"));
    }

    @Test
    public void testRunnerOf_invalidReturnsNull() {
        assertNull(Runner.runnerOf("bogus"));
        assertNull(Runner.runnerOf(""));
        assertNull(Runner.runnerOf(null));
    }

    @Test
    public void testRunnerOf_withDefault() {
        assertEquals(Runner.APP_SHELL, Runner.runnerOf(null, Runner.APP_SHELL));
        assertEquals(Runner.TERMINAL_SESSION, Runner.runnerOf("bogus", Runner.TERMINAL_SESSION));
        assertEquals(Runner.APP_SHELL, Runner.runnerOf("app-shell", Runner.TERMINAL_SESSION));
    }

    @Test
    public void testRunnerEqualsRunner() {
        assertTrue(Runner.APP_SHELL.equalsRunner("app-shell"));
        assertTrue(Runner.TERMINAL_SESSION.equalsRunner("terminal-session"));
        assertFalse(Runner.APP_SHELL.equalsRunner("terminal-session"));
        assertFalse(Runner.APP_SHELL.equalsRunner(null));
    }

    @Test
    public void testRunnerGetName() {
        assertEquals("app-shell", Runner.APP_SHELL.getName());
        assertEquals("terminal-session", Runner.TERMINAL_SESSION.getName());
    }

    // ==================== ShellCreateMode Tests ====================

    @Test
    public void testShellCreateModeOf_valid() {
        assertEquals(ShellCreateMode.ALWAYS, ShellCreateMode.modeOf("always"));
        assertEquals(ShellCreateMode.NO_SHELL_WITH_NAME, ShellCreateMode.modeOf("no-shell-with-name"));
    }

    @Test
    public void testShellCreateModeOf_invalid() {
        assertNull(ShellCreateMode.modeOf("bogus"));
        assertNull(ShellCreateMode.modeOf(null));
        assertNull(ShellCreateMode.modeOf(""));
    }

    @Test
    public void testShellCreateModeEqualsMode() {
        assertTrue(ShellCreateMode.ALWAYS.equalsMode("always"));
        assertFalse(ShellCreateMode.ALWAYS.equalsMode("no-shell-with-name"));
        assertFalse(ShellCreateMode.ALWAYS.equalsMode(null));
    }

    @Test
    public void testShellCreateModeGetMode() {
        assertEquals("always", ShellCreateMode.ALWAYS.getMode());
        assertEquals("no-shell-with-name", ShellCreateMode.NO_SHELL_WITH_NAME.getMode());
    }

    // ==================== State Machine Tests ====================

    @Test
    public void testStateTransition_forwardSucceeds() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertEquals(ExecutionState.PRE_EXECUTION, getExecutionState(cmd));

        assertTrue(cmd.setState(ExecutionState.EXECUTING));
        assertEquals(ExecutionState.EXECUTING, getExecutionState(cmd));

        assertTrue(cmd.setState(ExecutionState.EXECUTED));
        assertEquals(ExecutionState.EXECUTED, getExecutionState(cmd));

        assertTrue(cmd.setState(ExecutionState.SUCCESS));
        assertEquals(ExecutionState.SUCCESS, getExecutionState(cmd));
    }

    @Test
    public void testStateTransition_cannotGoBackward() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertTrue(cmd.setState(ExecutionState.EXECUTING));

        assertFalse(cmd.setState(ExecutionState.PRE_EXECUTION));
        // State should remain at EXECUTING
        assertEquals(ExecutionState.EXECUTING, getExecutionState(cmd));
    }

    @Test
    public void testStateTransition_successIsFinal() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertTrue(cmd.setState(ExecutionState.EXECUTING));
        assertTrue(cmd.setState(ExecutionState.EXECUTED));
        assertTrue(cmd.setState(ExecutionState.SUCCESS));

        // Cannot transition from SUCCESS to any state
        assertFalse(cmd.setState(ExecutionState.FAILED));
        assertFalse(cmd.setState(ExecutionState.EXECUTING));
        assertEquals(ExecutionState.SUCCESS, getExecutionState(cmd));
    }

    @Test
    public void testStateTransition_canSkipStates() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        // Can go directly from PRE_EXECUTION to EXECUTED
        assertTrue(cmd.setState(ExecutionState.EXECUTED));
        assertEquals(ExecutionState.EXECUTED, getExecutionState(cmd));
    }

    @Test
    public void testStateTransition_failedCanBeSetAgain() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertTrue(cmd.setState(ExecutionState.EXECUTING));
        assertTrue(cmd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "error1"));
        assertEquals(ExecutionState.FAILED, getExecutionState(cmd));

        // Can set failed again (to add more errors)
        assertTrue(cmd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "error2"));
        assertEquals(ExecutionState.FAILED, getExecutionState(cmd));
    }

    @Test
    public void testSetStateFailed_setsStateAndError() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertTrue(cmd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "test error"));

        assertEquals(ExecutionState.FAILED, getExecutionState(cmd));
        assertTrue(cmd.isStateFailed());
        assertEquals(Errno.ERRNO_FAILED.getCode(), cmd.resultData.getErrCode());
    }

    @Test
    public void testSetStateFailed_accumulatesErrors() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "error1");
        cmd.setStateFailed(Errno.ERRNO_FAILED.getCode(), "error2");

        assertEquals(2, cmd.resultData.errorsList.size());
    }

    @Test
    public void testHasExecuted() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertFalse(cmd.hasExecuted());

        cmd.setState(ExecutionState.EXECUTING);
        assertFalse(cmd.hasExecuted());

        cmd.setState(ExecutionState.EXECUTED);
        assertTrue(cmd.hasExecuted());
    }

    @Test
    public void testIsExecuting() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertFalse(cmd.isExecuting());

        cmd.setState(ExecutionState.EXECUTING);
        assertTrue(cmd.isExecuting());

        cmd.setState(ExecutionState.EXECUTED);
        assertFalse(cmd.isExecuting());
    }

    @Test
    public void testIsSuccessful() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertFalse(cmd.isSuccessful());

        cmd.setState(ExecutionState.EXECUTING);
        cmd.setState(ExecutionState.EXECUTED);
        cmd.setState(ExecutionState.SUCCESS);
        assertTrue(cmd.isSuccessful());
    }

    // ==================== Result Processing Guard Tests ====================

    @Test
    public void testShouldNotProcessResults_firstCallReturnsFalse() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertFalse(cmd.shouldNotProcessResults());
    }

    @Test
    public void testShouldNotProcessResults_secondCallReturnsTrue() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        assertFalse(cmd.shouldNotProcessResults());
        assertTrue(cmd.shouldNotProcessResults());
        assertTrue(cmd.shouldNotProcessResults()); // remains true
    }

    // ==================== Plugin Execution Command Tests ====================

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_falseWhenNotPlugin() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.isPluginExecutionCommand = false;
        cmd.resultConfig.resultDirectoryPath = "/some/path";
        assertFalse(cmd.isPluginExecutionCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_falseWhenNoPendingResult() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.isPluginExecutionCommand = true;
        // No pending intent or result directory
        assertFalse(cmd.isPluginExecutionCommandWithPendingResult());
    }

    @Test
    public void testIsPluginExecutionCommandWithPendingResult_trueWithDirectory() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.isPluginExecutionCommand = true;
        cmd.resultConfig.resultDirectoryPath = "/some/path";
        assertTrue(cmd.isPluginExecutionCommandWithPendingResult());
    }

    // ==================== resolveRunner Tests ====================

    @Test
    public void testResolveRunner_validRunner() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.runner = "app-shell";
        assertEquals(Runner.APP_SHELL, cmd.resolveRunner());

        cmd.runner = "terminal-session";
        assertEquals(Runner.TERMINAL_SESSION, cmd.resolveRunner());
    }

    @Test
    public void testResolveRunner_invalidRunner_returnsNull() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.runner = "bogus";
        assertNull(cmd.resolveRunner());
    }

    @Test
    public void testResolveRunner_nullRunner_returnsNull() {
        ExecutionCommand cmd = new ExecutionCommand(1);
        cmd.runner = null;
        assertNull(cmd.resolveRunner());
    }

    // ==================== Constructor Tests ====================

    @Test
    public void testDefaultConstructor() {
        ExecutionCommand cmd = new ExecutionCommand();
        assertNull(cmd.id);
        assertEquals(ExecutionState.PRE_EXECUTION, getExecutionState(cmd));
        assertNull(cmd.runner);
        assertFalse(cmd.isPluginExecutionCommand);
    }

    @Test
    public void testIdConstructor() {
        ExecutionCommand cmd = new ExecutionCommand(42);
        assertEquals(Integer.valueOf(42), cmd.id);
    }

    @Test
    public void testFullConstructor() {
        String[] args = {"-l", "-a"};
        ExecutionCommand cmd = new ExecutionCommand(1, "/bin/sh", args, "stdin", "/tmp", "app-shell", true);
        assertEquals(Integer.valueOf(1), cmd.id);
        assertEquals("/bin/sh", cmd.executable);
        assertArrayEquals(args, cmd.arguments);
        assertEquals("stdin", cmd.stdin);
        assertEquals("/tmp", cmd.workingDirectory);
        assertEquals("app-shell", cmd.runner);
        assertTrue(cmd.isFailsafe);
    }

    // ==================== Helper ====================

    /** Extract current state via toString() since currentState is private. */
    private ExecutionState getExecutionState(ExecutionCommand cmd) {
        String str = cmd.toString();
        for (ExecutionState state : ExecutionState.values()) {
            if (str.contains("Current State: `" + state.getName() + "`"))
                return state;
        }
        return null;
    }
}
