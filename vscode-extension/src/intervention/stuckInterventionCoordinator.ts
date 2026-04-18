import * as vscode from 'vscode';
import type { DeveloperEvent } from '../model/events';
import type { SocraticRequest, SocraticResponse } from '../model/api';
import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { SessionContext } from '../detector/sessionContext';
import { log } from '../util/logger';
import { pushSocratic, showInfo, showInvitation } from './socraticNotifier';

/**
 * Mirrors [`StuckInterventionCoordinator.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/intervention/StuckInterventionCoordinator.kt:1):
 *
 * - Global throttle ≤ 1 intervention per 2 min (NORMAL); MINIMAL = 10 min, CHATTY = 30 s.
 * - Per-rule cooldown 10 min after a `Not now`.
 * - On accept: POST `/socratic-question` and surface the answer in the
 *   notification + "Socratic Feed" tree view.
 */
class StuckInterventionCoordinatorImpl {
  private lastInterventionTs = 0;
  private readonly ruleDismissedUntil = new Map<string, number>();

  onStuckDetected(_event: DeveloperEvent, rule: string): void {
    const now = Date.now();
    const globalWindow = this.globalThrottleMs();
    if (now - this.lastInterventionTs < globalWindow) {
      log.debug(`Intervention throttled (global), waiting ${globalWindow - (now - this.lastInterventionTs)}ms`);
      return;
    }
    const silencedUntil = this.ruleDismissedUntil.get(rule) ?? 0;
    if (now < silencedUntil) {
      log.debug(`Rule ${rule} silenced until ${new Date(silencedUntil).toISOString()}`);
      return;
    }
    this.lastInterventionTs = now;

    void showInvitation(
      rule,
      () => { void this.fetchAndDisplay(rule); },
      () => { this.ruleDismissedUntil.set(rule, Date.now() + 10 * 60_000); },
    );
  }

  private async fetchAndDisplay(stuckReason: string): Promise<void> {
    if (Config.privacyMode) {
      showInfo(
        'RubberDuck Live — privacy mode',
        "Strict privacy is on: code isn't sent. Disable it in Settings to request a Socratic question.",
      );
      return;
    }
    const currentCode = currentEditorText();
    const recent = SessionContext.getInstance().recent(20);
    const request: SocraticRequest = {
      recent_events: recent,
      current_code: currentCode,
      stuck_reason: stuckReason,
    };
    let response: SocraticResponse;
    try {
      response = await BackendClient.getInstance().socraticQuestion(request);
    } catch (err) {
      log.warn(`Socratic request failed: ${err instanceof Error ? err.message : String(err)}`);
      response = {
        question: '(Backend unreachable — take a deep breath and describe the invariant out loud.)',
        follow_ups: ['What should be true before this line?', 'What inputs have you actually tried?'],
        confidence: 0.0,
      };
    }
    pushSocratic(stuckReason, response.question);
    showInfo('🦆 RubberDuck asks…', response.question);
  }

  private globalThrottleMs(): number {
    switch (Config.interventionFrequency) {
      case 'MINIMAL': return 10 * 60_000;
      case 'NORMAL': return 2 * 60_000;
      case 'CHATTY': return 30_000;
    }
  }
}

function currentEditorText(): string {
  const editor = vscode.window.activeTextEditor;
  return editor?.document.getText() ?? '';
}

let _instance: StuckInterventionCoordinatorImpl | undefined;
export const StuckInterventionCoordinator = {
  getInstance(): StuckInterventionCoordinatorImpl {
    if (!_instance) _instance = new StuckInterventionCoordinatorImpl();
    return _instance;
  },
};
