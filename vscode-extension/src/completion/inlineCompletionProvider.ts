import * as vscode from 'vscode';

import { BackendClient } from '../net/backendClient';
import { Config } from '../settings/config';
import { PathRedactor } from '../util/pathRedactor';
import { log } from '../util/logger';

/**
 * Ghost-text inline completion backed by `POST /api/v1/complete`.
 *
 * Mirrors the IntelliJ plugin's
 * [`RubberDuckInlineCompletionProvider.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/completion/RubberDuckInlineCompletionProvider.kt:1)
 * feature-for-feature:
 *
 * - **Gated** on `rubberduckLive.enableInlineCompletion` (on by default) and
 *   the global privacy-mode setting (off path ignored).
 * - **Dead-end skip** — we don't ship requests for contexts that are
 *   almost-never useful: prefix < 3 chars, unmatched string literal on the
 *   current line, bare comment lines.
 * - **10-second cache** keyed on last-256 chars of prefix + first-64 of
 *   suffix. Protects against the flicker when the caret bounces.
 * - **INFO logs** on every decision so diagnostics are visible in the
 *   extension-host output panel.
 */
const MAX_PREFIX_CHARS = 4_000;
const MAX_SUFFIX_CHARS = 1_000;
const MIN_PREFIX_CHARS = 3;
const CACHE_TTL_MS = 10_000;
const CACHE_MAX_ENTRIES = 128;
const CACHE_CTX_PREFIX_CHARS = 256;
const CACHE_CTX_SUFFIX_CHARS = 64;

interface CachedCompletion {
  text: string;
  ts: number;
}

export class RubberDuckInlineCompletionProvider
  implements vscode.InlineCompletionItemProvider
{
  private readonly cache = new Map<string, CachedCompletion>();

  async provideInlineCompletionItems(
    document: vscode.TextDocument,
    position: vscode.Position,
    _context: vscode.InlineCompletionContext,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlineCompletionList | vscode.InlineCompletionItem[]> {
    if (!Config.enableInlineCompletion) return [];
    if (Config.privacyMode) {
      log.info('Inline completion: disabled (privacy mode on)');
      return [];
    }

    // Capture prefix (doc start → caret) and suffix (caret → doc end).
    const prefix = document.getText(
      new vscode.Range(new vscode.Position(0, 0), position),
    );
    const lastLine = document.lineCount > 0
      ? document.lineAt(document.lineCount - 1)
      : null;
    const endPos = lastLine
      ? new vscode.Position(document.lineCount - 1, lastLine.text.length)
      : position;
    const suffix = document.getText(new vscode.Range(position, endPos));

    const skipReason = skipReasonFor(prefix, suffix);
    if (skipReason) {
      log.info(`Inline completion: skipped (${skipReason})`);
      return [];
    }

    const trimmedPrefix = takeLast(prefix, MAX_PREFIX_CHARS);
    const trimmedSuffix = suffix.slice(0, MAX_SUFFIX_CHARS);
    const ctxKey = cacheKey(trimmedPrefix, trimmedSuffix);

    const now = Date.now();
    const cached = this.cache.get(ctxKey);
    if (cached && now - cached.ts < CACHE_TTL_MS) {
      log.info(`Inline completion: cache hit key=${ctxKey.slice(0, 12)}…`);
      return [new vscode.InlineCompletionItem(cached.text)];
    }

    const language = document.languageId;
    const redactedPath = PathRedactor.redact(document.uri.fsPath);

    log.info(
      `Inline completion: request language=${language} ` +
        `prefix_chars=${trimmedPrefix.length} suffix_chars=${trimmedSuffix.length}`,
    );

    try {
      const response = await BackendClient.getInstance().complete({
        language,
        prefix: trimmedPrefix,
        suffix: trimmedSuffix,
        file_path: redactedPath,
        max_tokens: 96,
      });
      if (token.isCancellationRequested) return [];
      const text = response.completion.trim();
      if (!text) {
        log.info(`Inline completion: empty response (model=${response.model})`);
        return [];
      }
      log.info(
        `Inline completion: accepted chars=${text.length} ` +
          `stop=${response.stop_reason} model=${response.model}`,
      );
      this.cache.set(ctxKey, { text, ts: now });
      this.pruneCacheIfLarge();
      return [new vscode.InlineCompletionItem(text)];
    } catch (err) {
      log.info(`Inline completion: backend error — ${err instanceof Error ? err.message : String(err)}`);
      return [];
    }
  }

  private pruneCacheIfLarge(): void {
    if (this.cache.size <= CACHE_MAX_ENTRIES) return;
    const cutoff = Date.now() - CACHE_TTL_MS;
    for (const [k, v] of this.cache) {
      if (v.ts < cutoff) this.cache.delete(k);
    }
    if (this.cache.size <= CACHE_MAX_ENTRIES) return;
    // still too big — drop oldest
    const entries = [...this.cache.entries()].sort((a, b) => a[1].ts - b[1].ts);
    const dropCount = entries.length - CACHE_MAX_ENTRIES;
    for (let i = 0; i < dropCount; i++) {
      const entry = entries[i];
      if (entry) this.cache.delete(entry[0]);
    }
  }
}

function skipReasonFor(prefix: string, suffix: string): string | null {
  if (prefix.length < MIN_PREFIX_CHARS) return 'prefix too short';

  const lineStart = prefix.lastIndexOf('\n') + 1;
  const currentLine = prefix.slice(lineStart);

  // Inside an unclosed string literal on the current line.
  const doubleCount = (currentLine.match(/"/g) ?? []).length;
  const singleCount = (currentLine.match(/'/g) ?? []).length;
  const unmatchedDouble = doubleCount % 2 !== 0;
  const unmatchedSingle = singleCount % 2 !== 0;
  if (unmatchedDouble || unmatchedSingle) {
    const suffixLine = suffix.split('\n')[0] ?? '';
    const closesDouble = unmatchedDouble && suffixLine.includes('"');
    const closesSingle = unmatchedSingle && suffixLine.includes("'");
    if (!closesDouble && !closesSingle) return 'inside open string';
  }

  // Bare comment line.
  const trimmed = currentLine.trimStart();
  if (
    (trimmed === '#' ||
      trimmed.startsWith('# ') ||
      trimmed === '//' ||
      trimmed.startsWith('// ')) &&
    trimmed.length < 6
  ) {
    return 'empty comment';
  }

  return null;
}

function cacheKey(prefix: string, suffix: string): string {
  const pTail = takeLast(prefix, CACHE_CTX_PREFIX_CHARS);
  const sHead = suffix.slice(0, CACHE_CTX_SUFFIX_CHARS);
  // Djb2-style simple string hash; cheap + collision-resistant enough for
  // an in-process cache of < 128 entries.
  return `${hash32(pTail)}:${hash32(sHead)}`;
}

function hash32(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  }
  return (h >>> 0).toString(16);
}

function takeLast(s: string, n: number): string {
  return s.length <= n ? s : s.slice(s.length - n);
}

/** Convenience registration helper for `extension.ts`. */
export function registerInlineCompletionProvider(
  context: vscode.ExtensionContext,
): void {
  const provider = new RubberDuckInlineCompletionProvider();
  context.subscriptions.push(
    vscode.languages.registerInlineCompletionItemProvider(
      { pattern: '**' },
      provider,
    ),
  );
  log.info('InlineCompletionProvider installed');
}
