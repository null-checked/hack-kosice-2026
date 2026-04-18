import * as vscode from 'vscode';
import { EventType } from '../model/events';
import { emit } from '../detector/eventEmitter';
import { SessionContext } from '../detector/sessionContext';

/**
 * Emits `TEST_RUN` events when a test-runner task finishes.
 *
 * IMPORTANT: We intentionally do NOT use the `vscode.tests` proposed API
 * (`createTestObserver` / `onDidChangeTestResults` / `TestController`).
 * That API is gated behind `enabledApiProposals: ["testObserver"]` in
 * `package.json`, requires the extension to be installed with the
 * `--enable-proposed-api <id>` CLI flag (or be a special Insiders build),
 * and crashes activation in stable VS Code with:
 *   "CANNOT use API proposal: testObserver"
 *
 * Instead we use the stable `vscode.tasks.onDidEndTaskProcess` event and
 * regex-match the task name against well-known test runners. This means
 * we observe whole test-task pass/fail (via the process exit code) rather
 * than per-test outcomes — which is sufficient for the StuckDetector's
 * `repeated_failing_test` rule, which keys on the task name.
 *
 * Mirrors [`TestExecutionListener.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/listeners/TestExecutionListener.kt:1).
 */
export function registerTestExecutionListener(context: vscode.ExtensionContext): void {
  const sub = vscode.tasks.onDidEndTaskProcess((event) => {
    const task = event.execution.task;
    const taskName = task.name || '';
    const source = task.source || '';
    const haystack = `${taskName} ${source}`;

    const runner = detectRunner(haystack);
    if (!runner) return;

    const exitCode = event.exitCode;
    const passed = exitCode === 0;
    const outcome: 'pass' | 'fail' | 'error' =
      passed ? 'pass' : exitCode === undefined ? 'error' : 'fail';

    const failureSignature =
      !passed && exitCode !== undefined ? `${taskName} exited ${exitCode}` : null;
    if (failureSignature) {
      SessionContext.getInstance().lastTestFailureSignature = failureSignature;
    }

    emit({
      type: EventType.TEST_RUN,
      file: SessionContext.getInstance().activeFile,
      payload: {
        test_name: taskName || runner,
        passed,
        outcome,
        exit_code: exitCode ?? -1,
        runner,
        duration_ms: 0,
        failure_signature: failureSignature,
      },
    });
  });
  context.subscriptions.push(sub);
}

/**
 * Returns the matched runner identifier (lowercased) or `null` if the task
 * doesn't look like a test run.
 */
function detectRunner(haystack: string): string | null {
  const m = haystack.match(
    /\b(pytest|jest|vitest|mocha|gradle\s+test|mvn\s+test|npm\s+test|yarn\s+test|pnpm\s+test|cargo\s+test|go\s+test)\b/i,
  );
  if (!m || !m[1]) return null;
  return m[1].toLowerCase().replace(/\s+/g, ' ');
}
