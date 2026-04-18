import { defineConfig } from 'vitest/config';
import * as path from 'node:path';

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
    server: { deps: { inline: ['ws'] } },
  },
  resolve: {
    alias: {
      // The 'vscode' module is only available at extension runtime. For unit
      // tests we shim it with a tiny stub that mirrors the surface util/* uses.
      vscode: path.resolve(__dirname, 'tests/__mocks__/vscode.ts'),
    },
  },
});
