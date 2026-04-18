# RubberDuck Live — IntelliJ Platform Plugin

Phase 3 deliverable for the Hack Košice 2026 entry. Kotlin plugin for IntelliJ
IDEA 2024.2+ that:

- Watches your editor (edits / undo-redo / test runs / file focus / long caret
  dwell on red squigglies) and emits a normalized event stream.
- Streams those events, over a loopback WebSocket, to the FastAPI backend in
  [`../backend`](../backend) which rebroadcasts them to the dashboard.
- Detects five "stuck" patterns locally (architecture §5) and, when one fires,
  offers a non-intrusive **Socratic balloon** asking if you want a rubber-duck
  question.
- Adds three right-click actions powered by the backend:
  - **RubberDuck → Generate Tests** (writes `test_<module>.py`)
  - **RubberDuck → Suggest Refactor** (opens the native IntelliJ diff viewer)
  - **RubberDuck → Open Dashboard** (browses to `http://localhost:3000`)
- Keeps everything local: SQLite event log under `$IDE_CONFIG_DIR/rubberduck-live/events.db`
  (30-day retention) and redacts absolute paths before they leave the IDE.

## Requirements

- JDK 17
- IntelliJ IDEA Community 2024.2.5+
- The backend running on `http://127.0.0.1:8765` (see
  [`../backend/README.md`](../backend/README.md)).
- Optional: the dashboard on `http://localhost:3000`
  ([`../dashboard/README.md`](../dashboard/README.md)).

## Running the plugin in a sandbox IDE

From inside `plugin/`:

```bash
./gradlew runIde
```

The first invocation downloads the IntelliJ Platform distribution (~1 GB) and
cached dependencies. A sandbox IDE window opens with the plugin installed.

> **Note.** This repo ships a minimal `gradlew` launcher but *not*
> `gradle-wrapper.jar` (it's a binary). Bootstrap it once with:
>
> ```bash
> (cd plugin && gradle wrapper --gradle-version 8.10.2)
> ```
>
> after which `./gradlew …` works as usual.

## Useful Gradle tasks

| Task | Purpose |
| --- | --- |
| `./gradlew runIde` | Launch a sandbox IDE with the plugin. |
| `./gradlew buildPlugin` | Produce a distributable `.zip` under `build/distributions/`. |
| `./gradlew verifyPlugin` | Run the IntelliJ Plugin Verifier against the recommended IDEs. A missing `untilBuild` may be flagged as a warning — this is intentional (architecture §12 / risk #8). |
| `./gradlew test` | Run the JUnit tests (none shipped in Phase 3). |

## What to expect on first launch

1. The sandbox IDE opens; RubberDuck Live shows in **Installed** plugins.
2. A tool window named **RubberDuck Live** appears on the right side. The
   Status section polls the backend's `/health` every 10 s.
3. Open any Python file in your sandbox project, right-click → you should see
   the three **RubberDuck** actions.
4. Settings → Tools → **RubberDuck Live** exposes the backend URL, intervention
   frequency, strict-privacy mode, and a *Test connection* button.
5. If the backend is unreachable the WebSocket keeps reconnecting with
   exponential backoff (1 s → 2 s → 4 s → … → 30 s max).

## Layout

```
plugin/
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
├── src/main/kotlin/com/rubberduck/live/    # plugin code (Kotlin only)
│   ├── RubberDuckStartup.kt                # ProjectActivity
│   ├── actions/                            # Generate/Refactor/Dashboard
│   ├── detector/                           # EventBus, SessionContext, StuckDetector
│   ├── intervention/                       # SocraticNotifier + Coordinator
│   ├── listeners/                          # Editor, FileFocus, CaretDwell, TestRun
│   ├── model/                              # DeveloperEvent + DTOs
│   ├── net/                                # BackendClient, EventsWebSocket
│   ├── settings/                           # RubberDuckSettings + Configurable
│   ├── storage/                            # SQLite EventLog
│   ├── toolwindow/                         # Tool window factory + panel
│   └── util/                               # PathRedactor, CodeSelection
└── src/main/resources/
    ├── META-INF/plugin.xml
    ├── META-INF/pluginIcon.svg
    └── messages/RubberDuckBundle.properties
```

## Privacy

- All network calls go to `127.0.0.1` (backend) or the user's browser. No
  telemetry, no analytics, no cloud.
- **Every outbound event and DTO `file_path` flows through
  [`PathRedactor`](src/main/kotlin/com/rubberduck/live/util/PathRedactor.kt)**
  first — absolute paths never leave the IDE. See §11 of the architecture doc.
- Strict privacy mode (Settings → RubberDuck Live → "Strict privacy")
  disables the HTTP actions that would otherwise send code to the backend.
