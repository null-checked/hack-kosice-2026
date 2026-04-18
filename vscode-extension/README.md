# RubberDuck Live — VS Code Extension

An AI pair programmer that watches you think. Right-click to generate tests, refactor selections, and get Socratic nudges when you're stuck. Same backend, same dashboard, same three pillars as the IntelliJ plugin — just for the VS Code crowd.

> Full setup walk-through: [`docs/VSCODE_GUIDE.md`](../docs/VSCODE_GUIDE.md).

## Develop in 60 seconds

```bash
cd vscode-extension
npm install
npm run compile
code .
# F5 → Extension Development Host opens with the extension loaded
```

## Build a `.vsix`

```bash
npm run package        # produces rubberduck-live-vscode-0.1.0.vsix
code --install-extension rubberduck-live-vscode-0.1.0.vsix
```

## Configure

`Cmd/Ctrl+,` → search **RubberDuck Live**. Five settings:

| Setting | Default | Notes |
|---|---|---|
| `rubberduckLive.backendUrl` | `http://127.0.0.1:8765` | FastAPI base URL |
| `rubberduckLive.dashboardUrl` | `http://localhost:3000` | opened by `Open Dashboard` |
| `rubberduckLive.sendEventsToDashboard` | `true` | turn the WS off |
| `rubberduckLive.interventionFrequency` | `NORMAL` | `MINIMAL` / `NORMAL` / `CHATTY` |
| `rubberduckLive.privacyMode` | `false` | strict — disables AI commands |

## Commands

- `🦆 RubberDuck: Generate Tests` — right-click in any editor.
- `🦆 RubberDuck: Suggest Refactor` — right-click in any editor.
- `🦆 RubberDuck: Open Dashboard` — palette / status bar.
- `🦆 RubberDuck: Test Connection` — palette; pings `/health`.

## Activity bar

Look for the duck icon. Two views:

- **Recent Events** — live tail of your `EDIT`, `UNDO`, `TEST_RUN`, etc.
- **Socratic Feed** — every question the duck has asked this session.

## License

MIT.
