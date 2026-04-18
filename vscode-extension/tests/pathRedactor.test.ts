import { describe, expect, it } from 'vitest';
import { PathRedactor } from '../src/util/pathRedactor';

describe('PathRedactor', () => {
  it('returns null for empty input', () => {
    expect(PathRedactor.redact('', ['/proj'])).toBeNull();
    expect(PathRedactor.redact(null, ['/proj'])).toBeNull();
    expect(PathRedactor.redact(undefined, ['/proj'])).toBeNull();
  });

  it('strips workspace prefix to relative path', () => {
    expect(PathRedactor.redact('/Users/alice/proj/src/a.py', ['/Users/alice/proj'])).toBe('src/a.py');
  });

  it('returns "." when path equals workspace root', () => {
    expect(PathRedactor.redact('/Users/alice/proj', ['/Users/alice/proj'])).toBe('.');
  });

  it('handles Windows separators', () => {
    expect(PathRedactor.redact('C:\\Users\\alice\\proj\\src\\a.py', ['C:/Users/alice/proj'])).toBe('src/a.py');
  });

  it('falls back to <external>/basename for paths outside workspace', () => {
    expect(PathRedactor.redact('/Users/alice/Desktop/secret.py', ['/Users/alice/proj']))
      .toBe('<external>/secret.py');
  });

  it('prefers the longest matching workspace folder when nested', () => {
    const roots = ['/Users/alice/proj', '/Users/alice/proj/inner'];
    expect(PathRedactor.redact('/Users/alice/proj/inner/sub/x.py', roots)).toBe('sub/x.py');
  });

  it('redacts with no workspace folders by falling back to external', () => {
    expect(PathRedactor.redact('/somewhere/file.py', [])).toBe('<external>/file.py');
  });
});
