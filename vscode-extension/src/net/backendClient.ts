import { Config } from '../settings/config';
import {
  parseGenerateTests,
  parseHealth,
  parseSocratic,
  parseSuggestRefactor,
} from './jsonCodec';
import type {
  GenerateTestsRequest,
  GenerateTestsResponse,
  HealthResponse,
  SocraticRequest,
  SocraticResponse,
  SuggestRefactorRequest,
  SuggestRefactorResponse,
} from '../model/api';
import { log } from '../util/logger';

/**
 * Tiny HTTP client that speaks the four JSON endpoints of the RubberDuck Live
 * backend. Mirrors [`BackendClient.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/net/BackendClient.kt:1).
 *
 * Uses Node's built-in `fetch` (Node 20 is the minimum runtime for VS Code
 * 1.95+). All methods reject on non-2xx with a descriptive error.
 */
class BackendClientImpl {
  private base(): string {
    return Config.backendApiBase;
  }

  async health(apiBase?: string): Promise<HealthResponse> {
    const base = apiBase ?? this.base();
    return this.get(`${base}/health`, parseHealth);
  }

  async generateTests(req: GenerateTestsRequest): Promise<GenerateTestsResponse> {
    return this.post('/generate-tests', req, parseGenerateTests);
  }

  async suggestRefactor(req: SuggestRefactorRequest): Promise<SuggestRefactorResponse> {
    return this.post('/suggest-refactor', req, parseSuggestRefactor);
  }

  async socraticQuestion(req: SocraticRequest): Promise<SocraticResponse> {
    return this.post('/socratic-question', req, parseSocratic);
  }

  private async get<T>(url: string, parser: (raw: unknown) => T): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5_000);
    try {
      const resp = await fetch(url, { method: 'GET', signal: controller.signal });
      const text = await resp.text();
      if (!resp.ok) throw new Error(`GET ${url} HTTP ${resp.status}: ${text.slice(0, 300)}`);
      return parser(text.length === 0 ? null : JSON.parse(text));
    } finally {
      clearTimeout(timer);
    }
  }

  private async post<TReq, TRes>(
    path: string,
    body: TReq,
    parser: (raw: unknown) => TRes,
  ): Promise<TRes> {
    const url = `${this.base()}${path}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 60_000);
    try {
      const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await resp.text();
      if (!resp.ok) {
        log.warn(`POST ${path} failed HTTP ${resp.status}: ${text.slice(0, 300)}`);
        throw new Error(`POST ${path} HTTP ${resp.status}: ${text.slice(0, 300)}`);
      }
      return parser(text.length === 0 ? null : JSON.parse(text));
    } finally {
      clearTimeout(timer);
    }
  }
}

let _instance: BackendClientImpl | undefined;
export const BackendClient = {
  getInstance(): BackendClientImpl {
    if (!_instance) _instance = new BackendClientImpl();
    return _instance;
  },
};
