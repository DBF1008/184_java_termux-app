package com.termux.app;

import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for the shared runner/shell-create-mode resolution that the external
 * {@code RUN_COMMAND} entry point ({@link com.termux.app.RunCommandService}) and the internal
 * {@code ACTION_SERVICE_EXECUTE} entry point ({@link com.termux.app.TermuxService}) both rely on.
 *
 * Both entry points now resolve the runner via {@link Runner#resolveRunner(String, boolean)} and
 * validate it via {@link Runner#runnerOf(String)} before dispatching foreground TermuxSession and
 * background TermuxTask commands through a single path. These tests pin that pure, shared contract
 * so the two entry points cannot drift apart again:
 *  - foreground session routing (no runner / background=false => terminal-session),
 *  - background task routing (no runner / background=true => app-shell),
 *  - invalid-parameter handling (unknown runner / shell-create-mode rejected, not silently coerced).
 *
 * The actual foreground/background shell spawn requires a live Service and native processes and is
 * therefore exercised by manual/instrumented runs rather than these unit tests.
 */
public class RunCommandRunnerResolutionTest {

    // Foreground TermuxSession scenario: no explicit runner and not background => terminal-session.
    @Test
    public void testResolveRunnerDefaultsToForegroundTerminalSession() {
        Assert.assertEquals(Runner.TERMINAL_SESSION.getName(), Runner.resolveRunner(null, false));
        // An empty runner is treated the same as an unset runner.
        Assert.assertEquals(Runner.TERMINAL_SESSION.getName(), Runner.resolveRunner("", false));
        // The resolved value must validate as a supported runner.
        Assert.assertSame(Runner.TERMINAL_SESSION, Runner.runnerOf(Runner.resolveRunner(null, false)));
    }

    // Background TermuxTask scenario: no explicit runner and background => app-shell.
    @Test
    public void testResolveRunnerDefaultsToBackgroundAppShell() {
        Assert.assertEquals(Runner.APP_SHELL.getName(), Runner.resolveRunner(null, true));
        Assert.assertEquals(Runner.APP_SHELL.getName(), Runner.resolveRunner("", true));
        Assert.assertSame(Runner.APP_SHELL, Runner.runnerOf(Runner.resolveRunner(null, true)));
    }

    // An explicitly requested runner takes precedence over the background flag, identically for
    // both entry points.
    @Test
    public void testExplicitRunnerOverridesBackgroundFlag() {
        Assert.assertEquals(Runner.TERMINAL_SESSION.getName(),
            Runner.resolveRunner(Runner.TERMINAL_SESSION.getName(), true));
        Assert.assertEquals(Runner.APP_SHELL.getName(),
            Runner.resolveRunner(Runner.APP_SHELL.getName(), false));
    }

    // Invalid-parameter scenario: an unknown runner must be passed through unchanged by
    // resolveRunner (so the caller can report the exact bad value) and must be rejected by
    // runnerOf, which is the precise gate both entry points use to fail the command.
    @Test
    public void testInvalidRunnerIsPassedThroughAndRejected() {
        String invalid = "bogus-runner";
        Assert.assertEquals(invalid, Runner.resolveRunner(invalid, false));
        Assert.assertEquals(invalid, Runner.resolveRunner(invalid, true));
        Assert.assertNull(Runner.runnerOf(invalid));
        Assert.assertNull(Runner.runnerOf(null));
        Assert.assertNull(Runner.runnerOf(""));
    }

    // Shell-create-mode resolution that drives the existing-shell reuse branch of the consolidated
    // dispatch path. Known modes resolve; an unknown mode is rejected (=> command fails).
    @Test
    public void testShellCreateModeResolution() {
        Assert.assertSame(ShellCreateMode.ALWAYS, ShellCreateMode.modeOf(ShellCreateMode.ALWAYS.getMode()));
        Assert.assertSame(ShellCreateMode.NO_SHELL_WITH_NAME,
            ShellCreateMode.modeOf(ShellCreateMode.NO_SHELL_WITH_NAME.getMode()));
        Assert.assertNull(ShellCreateMode.modeOf("bogus-mode"));
        Assert.assertNull(ShellCreateMode.modeOf(null));

        Assert.assertTrue(ShellCreateMode.ALWAYS.equalsMode("always"));
        Assert.assertTrue(ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode("no-shell-with-name"));
        Assert.assertFalse(ShellCreateMode.ALWAYS.equalsMode("no-shell-with-name"));
        Assert.assertFalse(ShellCreateMode.ALWAYS.equalsMode(null));
    }

}
