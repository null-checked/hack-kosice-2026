/**
 * Tiny type-safe JSON helpers. We don't pull in zod/io-ts to keep the bundle
 * minimal; instead each backend response goes through a narrow `parse*`
 * function that lenient-defaults missing fields.
 */
import type {
  CodeChatResponse,
  CompleteResponse,
  ExplainCodeResponse,
  GenerateTestsResponse,
  HealthResponse,
  SocraticResponse,
  SuggestRefactorResponse,
} from '../model/api';

function asString(v: unknown, dflt = ''): string {
  return typeof v === 'string' ? v : dflt;
}
function asNumber(v: unknown, dflt = 0): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : dflt;
}
function asBool(v: unknown, dflt = false): boolean {
  return typeof v === 'boolean' ? v : dflt;
}
function asArray<T>(v: unknown, mapper: (item: unknown) => T | null): T[] {
  if (!Array.isArray(v)) return [];
  const out: T[] = [];
  for (const item of v) {
    const mapped = mapper(item);
    if (mapped !== null) out.push(mapped);
  }
  return out;
}

export function parseHealth(raw: unknown): HealthResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    status: asString(o['status'], 'unknown'),
    llm_provider: asString(o['llm_provider'], 'unknown'),
    llm_model: asString(o['llm_model'], 'unknown'),
    llm_available: asBool(o['llm_available'], false),
    demo_mock: asBool(o['demo_mock'], false),
    version: asString(o['version'], '0.0.0'),
  };
}

export function parseGenerateTests(raw: unknown): GenerateTestsResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    test_code: asString(o['test_code']),
    test_file_name: asString(o['test_file_name'], 'test_generated.py'),
    framework: asString(o['framework'], 'pytest'),
    reasoning: asString(o['reasoning']),
  };
}

export function parseSuggestRefactor(raw: unknown): SuggestRefactorResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    refactored_code: asString(o['refactored_code']),
    diff_unified: asString(o['diff_unified']),
    smells_detected: asArray(o['smells_detected'], (item) => {
      if (!item || typeof item !== 'object') return null;
      const s = item as Record<string, unknown>;
      return {
        type: asString(s['type'], 'unknown'),
        line: asNumber(s['line'], 0),
        description: asString(s['description']),
      };
    }),
    reasoning: asString(o['reasoning']),
  };
}

export function parseSocratic(raw: unknown): SocraticResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    question: asString(o['question'], '(no question returned)'),
    follow_ups: asArray(o['follow_ups'], (s) => (typeof s === 'string' ? s : null)),
    confidence: asNumber(o['confidence'], 0.5),
  };
}

export function parseComplete(raw: unknown): CompleteResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    completion: asString(o['completion']),
    stop_reason: asString(o['stop_reason'], 'eol'),
    model: asString(o['model'], 'unknown'),
  };
}

export function parseCodeChat(raw: unknown): CodeChatResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    answer: asString(o['answer'], '(no answer returned)'),
    model: asString(o['model'], 'unknown'),
  };
}

export function parseExplainCode(raw: unknown): ExplainCodeResponse {
  const o = (raw ?? {}) as Record<string, unknown>;
  return {
    explanation: asString(o['explanation'], '(no explanation returned)'),
    complexity: asString(o['complexity'], 'unknown'),
    suggestions: asArray(o['suggestions'], (s) => (typeof s === 'string' ? s : null)),
    model: asString(o['model'], 'unknown'),
  };
}
