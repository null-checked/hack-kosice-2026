/**
 * Language-agnostic heuristics for picking the "function under caret" without
 * needing a specific language server. Ports the logic in
 * [`CodeSelection.kt`](../../../plugin/src/main/kotlin/com/rubberduck/live/util/CodeSelection.kt:1).
 *
 * Python is first-class (regex + indentation); brace-based languages share a
 * generic walker.
 */
export interface CodeSelection {
  code: string;
  functionName: string | null;
  startLine: number;
  endLine: number;
  language: string;
}

const PYTHON_DEF = /^\s*def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/;
const KOTLIN_FUN = /^\s*(?:(?:public|private|protected|internal|open|suspend|inline)\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]/;
const JS_FUN = /^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(/;

export function extractFunction(text: string, caretOffset: number, language: string): CodeSelection {
  if (text.length === 0) return { code: '', functionName: null, startLine: 0, endLine: 0, language };
  const safeOffset = Math.max(0, Math.min(text.length, caretOffset));
  const lines = text.split('\n');
  const before = text.slice(0, safeOffset);
  const caretLine = (before.match(/\n/g) ?? []).length;
  switch (language) {
    case 'python':
      return extractPython(lines, caretLine);
    case 'kotlin':
    case 'java':
      return extractBraceBased(lines, caretLine, KOTLIN_FUN, language);
    case 'javascript':
    case 'typescript':
      return extractBraceBased(lines, caretLine, JS_FUN, language);
    default:
      return extractBraceBased(lines, caretLine, KOTLIN_FUN, language);
  }
}

function indentOf(line: string): number {
  let n = 0;
  for (const c of line) {
    if (c === ' ') n += 1;
    else if (c === '\t') n += 4;
    else break;
  }
  return n;
}

function extractPython(lines: string[], caretLine: number): CodeSelection {
  let headerLine = -1;
  let headerIndent = -1;
  let i = Math.min(caretLine, lines.length - 1);
  while (i >= 0) {
    const line = lines[i];
    if (line !== undefined) {
      const m = PYTHON_DEF.exec(line);
      if (m) {
        headerLine = i;
        headerIndent = indentOf(line);
        break;
      }
    }
    i -= 1;
  }
  if (headerLine < 0) {
    const fallback = lines[caretLine] ?? '';
    return { code: fallback, functionName: null, startLine: caretLine, endLine: caretLine, language: 'python' };
  }
  let end = headerLine;
  let j = headerLine + 1;
  while (j < lines.length) {
    const line = lines[j] ?? '';
    if (line.trim().length === 0) { j += 1; continue; }
    if (indentOf(line) <= headerIndent) break;
    end = j;
    j += 1;
  }
  const headerText = lines[headerLine] ?? '';
  const name = PYTHON_DEF.exec(headerText)?.[1] ?? null;
  const code = lines.slice(headerLine, end + 1).join('\n');
  return { code, functionName: name, startLine: headerLine, endLine: end, language: 'python' };
}

function extractBraceBased(
  lines: string[],
  caretLine: number,
  headerRegex: RegExp,
  language: string,
): CodeSelection {
  let headerLine = -1;
  let i = Math.min(caretLine, lines.length - 1);
  while (i >= 0) {
    const line = lines[i];
    if (line !== undefined && headerRegex.test(line)) { headerLine = i; break; }
    i -= 1;
  }
  if (headerLine < 0) {
    const fallback = lines[caretLine] ?? '';
    return { code: fallback, functionName: null, startLine: caretLine, endLine: caretLine, language };
  }
  let openLine = headerLine;
  let openCol = -1;
  for (let k = headerLine; k < lines.length; k += 1) {
    const line = lines[k] ?? '';
    const col = line.indexOf('{');
    if (col >= 0) { openLine = k; openCol = col; break; }
  }
  const headerText = lines[headerLine] ?? '';
  const headerName = headerRegex.exec(headerText)?.[1] ?? null;
  if (openCol < 0) {
    let end = headerLine;
    let j = headerLine + 1;
    while (j < lines.length && (lines[j] ?? '').trim().length > 0) { end = j; j += 1; }
    return { code: lines.slice(headerLine, end + 1).join('\n'), functionName: headerName, startLine: headerLine, endLine: end, language };
  }
  let depth = 0;
  let endLine = openLine;
  outer: for (let k = openLine; k < lines.length; k += 1) {
    const line = lines[k] ?? '';
    const startCol = k === openLine ? openCol : 0;
    for (let c = startCol; c < line.length; c += 1) {
      const ch = line[c];
      if (ch === '{') depth += 1;
      else if (ch === '}') {
        depth -= 1;
        if (depth === 0) { endLine = k; break outer; }
      }
    }
  }
  const code = lines.slice(headerLine, endLine + 1).join('\n');
  return { code, functionName: headerName, startLine: headerLine, endLine, language };
}

/** Map a VS Code languageId / file extension to the backend's language tag. */
export function languageFor(languageId: string | undefined, fileNameOrExt?: string): string {
  if (languageId) {
    switch (languageId) {
      case 'python': return 'python';
      case 'kotlin': return 'kotlin';
      case 'java': return 'java';
      case 'typescript':
      case 'typescriptreact':
        return 'typescript';
      case 'javascript':
      case 'javascriptreact':
        return 'javascript';
    }
  }
  const ext = (fileNameOrExt ?? '').toLowerCase().split('.').pop();
  switch (ext) {
    case 'py': return 'python';
    case 'kt':
    case 'kts': return 'kotlin';
    case 'java': return 'java';
    case 'ts':
    case 'tsx': return 'typescript';
    case 'js':
    case 'jsx': return 'javascript';
    default: return 'python';
  }
}
