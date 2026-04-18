import * as path from 'node:path';
import * as vscode from 'vscode';

/**
 * Strips absolute filesystem paths to workspace-relative form before they
 * leave the IDE. Mirrors the IntelliJ plugin's
 * [`PathRedactor.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/util/PathRedactor.kt:1):
 * inside the workspace → relative path; outside → `<external>/<basename>`.
 */
export class PathRedactor {
  /**
   * @param workspaceRoots optional list of folder fs-paths; if omitted we read from the active workspace.
   */
  static redact(absolutePath: string | undefined | null, workspaceRoots?: readonly string[]): string | null {
    if (!absolutePath || absolutePath.trim().length === 0) return null;
    const normalized = absolutePath.replace(/\\/g, '/');
    const roots = (workspaceRoots ?? PathRedactor.currentRoots()).map((r) => r.replace(/\\/g, '/'));

    // Sort longest-first so a nested workspace folder wins over a parent.
    const sorted = [...roots].sort((a, b) => b.length - a.length);
    for (const root of sorted) {
      const trimmed = root.replace(/\/+$/, '');
      if (normalized === trimmed) return '.';
      if (normalized.startsWith(`${trimmed}/`)) {
        return normalized.slice(trimmed.length + 1);
      }
    }
    // Fallback — never leak the home directory.
    const base = path.posix.basename(normalized);
    return `<external>/${base}`;
  }

  /** Convenience: redact a `vscode.Uri`'s file-system path. */
  static redactUri(uri: vscode.Uri | undefined | null): string | null {
    if (!uri) return null;
    if (uri.scheme !== 'file') return `<${uri.scheme}>/${path.posix.basename(uri.path)}`;
    return PathRedactor.redact(uri.fsPath);
  }

  private static currentRoots(): string[] {
    const folders = vscode.workspace.workspaceFolders ?? [];
    return folders.map((f) => f.uri.fsPath);
  }
}
