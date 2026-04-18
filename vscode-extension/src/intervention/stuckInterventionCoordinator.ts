import * as vscode from 'vscode';
import { randomUUID } from 'node:crypto';

import type { DeveloperEvent } from '../model/events';
import type { SocraticRequest, SocraticResponse } from '../model/api';
import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { SessionContext } from '../detector/sessionContext';
import { emit } from '../detector/eventEmitter';
import { EventType } from '../model/events';
import { log } from '../util/logger';
import { pushSocratic, showInfo } from './socraticNotifier';

/**
 * Passive Socratic coordinator.
 *
 * v0.3 simplification (mirrors the IntelliJ plugin's
 * [`StuckInterventionCoordinator.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/intervention/StuckInterventionCoordinator.kt:1)):
 * the old "Ask me / Not now" invitation is gone. When a stuck moment is
 * detected we go directly to fetch + display the Socratic question as a
 * non-intrusive info balloon. Users can glance or ignore. If they want to
 * engage, they open the Chat webview.
 *
 * Responsibilities:
 * - **Global throttle** (≤ 1 balloon per 2 min at NORMAL frequency).
 * - **Privacy gate** — strict-privacy shows a stock nudge with no code sent.
 * - Emits **one `INTERVENTION_SHOWN`** per firing. Payload carries
 *   `question`, `follow_ups`, `rule`, `confidence` so the dashboard
 *   SocraticFeed renders real text instead of an ellipsis.
 */
class StuckInterventionCoordinatorImpl {
  private lastInterventionTs = 0;

  onStuckDetected(_event: DeveloperEvent, rule: string): void {
    const now = Date.now();
    const globalWindow = this.globalThrottleMs();
    if (now - this.lastInterventionTs < globalWindow) {
      log.debug(
        `Intervention throttled (global), waiting ${globalWindow - (now - this.lastInterventionTs)}ms`,
      );
      return;
    }
    this.lastInterventionTs = now;
    void this.fetchAndDisplay(rule);
  }

  private async fetchAndDisplay(stuckReason: string): Promise<void> {
    const interventionId = randomUUID();

    if (Config.privacyMode) {
      const stockQuestion =
        "Take a breath. State the invariant out loud. (Strict privacy is on — no code sent.)";
      showInfo(
        `🦆 RubberDuck noticed a "${stuckReason}" pattern`,
        stockQuestion,
      );
      pushSocratic(stuckReason, stockQuestion);
      this.emitShown(interventionId, stuckReason, {
        question: stockQuestion,
        follow_ups: [],
        confidence: 0.0,
      });
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
        question:
          '(Backend unreachable — take a deep breath and describe the invariant out loud.)',
        follow_ups: [],
        confidence: 0.0,
      };
    }
    pushSocratic(stuckReason, response.question);
    showInfo('🦆 RubberDuck asks…', response.question);
    this.emitShown(interventionId, stuckReason, response);
  }

  private emitShown(
    interventionId: string,
    stuckReason: string,
    response: SocraticResponse,
  ): void {
    emit({
      type: EventType.INTERVENTION_SHOWN,
      payload: {
        intervention_id: interventionId,
        kind: 'socratic',
        rule: stuckReason,
        question: response.question,
        confidence: response.confidence,
        follow_ups: response.follow_ups,
      },
    });
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
