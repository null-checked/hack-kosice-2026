/**
 * Minimal `vscode` stub for unit tests that import code from `src/util/*`.
 *
 * We only stub the surface the testable code actually touches. Anything else
 * is a clearly-named throwaway that crashes loudly if accidentally hit.
 */
export const workspace = {
  workspaceFolders: undefined as undefined | Array<{ uri: { fsPath: string } }>,
  getConfiguration: () => ({ get: <T>(_key: string, dflt: T): T => dflt }),
  onDidChangeConfiguration: () => ({ dispose: () => undefined }),
};

export const window = {
  createOutputChannel: () => ({
    appendLine: (_msg: string) => undefined,
    dispose: () => undefined,
  }),
};

export class Disposable {
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  constructor(private readonly _dispose: () => void = () => {}) {}
  dispose(): void { this._dispose(); }
}
