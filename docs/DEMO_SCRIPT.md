# RubberDuck Live — 3-Minute Demo Script

Target length: **3:00**. Two presenters recommended (`P1` = narrator/driver, `P2` = dashboard + commentary). Rehearse twice before judging. Use a fresh IntelliJ window with a prepared `demo/` Python project already open and Ollama pre-warmed (`ollama run qwen2.5-coder:7b "hi"` before the judges arrive).

---

## 0:00 – 0:30 — Hook & Problem

**[On screen]** Title slide: "RubberDuck Live — an AI pair-programmer that watches you think." Fade to IntelliJ.

**P1 (camera on presenter, IDE visible):**
> "Every developer has been stuck staring at the same red squiggly for ten minutes. You rewrite the same loop four times. You *know* something's wrong. What if your IDE gently asked the right question at the right moment — without stealing the keyboard from you?
>
> That's **RubberDuck Live**. An IntelliJ plugin that watches your workflow, privately, on your machine, and intervenes with a Socratic question — not another Copilot autocomplete. It also writes your tests and suggests refactors on demand. One hundred percent local. Ollama by default."

**Visual cue**: Briefly show the three pillars as a side chyron:
- ✅ Generate Tests
- ✅ Suggest Refactor
- ✅ Notice when you're stuck

*(End at 0:30 sharp — do not over-narrate the intro.)*

---

## 0:30 – 1:15 — Pillar 1: Generate Tests

**Setup (pre-demo)**: File `demo/fizz.py` is open with the snippet below already typed. Cursor on line 1.

```python
# demo/fizz.py
def fizzbuzz(n: int) -> str:
    if n % 15 == 0:
        return "FizzBuzz"
    if n % 3 == 0:
        return "Fizz"
    if n % 5 == 0:
        return "Buzz"
    return str(n)
```

**P1 action**:
1. Right-click on the `fizzbuzz` function name.
2. Select **"RubberDuck → Generate Tests"**.
3. A progress toast appears: *"Planning tests… writing tests…"* (~4–8 s on warm Ollama).
4. A new editor tab `demo/test_fizz.py` opens with a generated test file.

**P1 narration over action**:
> "Right-click any function, pick *Generate Tests*. The plugin sends the function to our local FastAPI backend, which runs a planner-worker pipeline on Ollama. No code leaves the machine. Out comes a pytest file covering the happy path, the FizzBuzz edge case, and boundary inputs — ready to run."

**Expected generated content** *(approximate, used only as a sanity reference — do not rely on exact wording)*:
```python
# demo/test_fizz.py
import pytest
from fizz import fizzbuzz

@pytest.mark.parametrize("n,expected", [
    (1, "1"), (3, "Fizz"), (5, "Buzz"),
    (15, "FizzBuzz"), (30, "FizzBuzz"), (0, "FizzBuzz"),
])
def test_fizzbuzz(n, expected):
    assert fizzbuzz(n) == expected
```

**Transition (0:10 max)**: Run the tests in IntelliJ's gutter, show green bar, say: *"Green. Next pillar."*

---

## 1:15 – 2:00 — Pillar 2: Suggest Refactor

**Setup**: Open `demo/bad.py`. This file is deliberately ugly.

```python
# demo/bad.py
def analyze(xs):
    results = []
    for i in range(len(xs)):
        for j in range(len(xs)):
            for k in range(len(xs)):
                if xs[i] + xs[j] + xs[k] == 86400:
                    if xs[i] > 0 and xs[j] > 0 and xs[k] > 0:
                        results.append((xs[i], xs[j], xs[k]))
    return results
```

**P1 action**:
1. Select the whole function.
2. Right-click → **"RubberDuck → Suggest Refactor"**.
3. IntelliJ's built-in **diff viewer** opens showing before/after.
4. Hover over the gutter to surface the smell tooltips: *"nested_loops (O(n³))"*, *"magic_number 86400 → SECONDS_PER_DAY"*.

**P1 narration**:
> "Same mechanism: one action, local LLM, structured response. The backend's smell detector tags three problems — triple-nested loop, magic number, duplicate positivity check — and returns a unified diff. IntelliJ renders it with its native diff viewer. One click applies it. If we don't like it, we close the tab and nothing changes."

**Expected diff direction** (approximate):
```python
# after
SECONDS_PER_DAY = 86400

def analyze(xs):
    positives = [x for x in xs if x > 0]
    return [
        (a, b, c)
        for a, b, c in itertools.combinations_with_replacement(positives, 3)
        if a + b + c == SECONDS_PER_DAY
    ]
```

---

## 2:00 – 2:40 — Pillar 3: The META Wellness Layer *(show-stealer)*

**Setup**: Open `demo/stuck.py` with an intentionally broken loop. The presenter will simulate being stuck.

```python
# demo/stuck.py
def rolling_sum(xs, k):
    out = []
    total = 0
    for i in range(len(xs)):
        total += xs[i]
        if i >= k:
            total -= xs[i - k]   # off-by-one the presenter will flip-flop on
        if i >= k - 1:
            out.append(total)
    return out
```

**P1 action** — *rehearse this macro*:
1. Place cursor on the `total -= xs[i - k]` line.
2. Press **Ctrl+Z** four times in rapid succession (within ~5 s).
3. Within ~1 s, a **balloon notification** slides in from the bottom-right:

   > 🦆 **RubberDuck Live**
   > *"You've undone this line four times in thirty seconds. What invariant is `total` supposed to maintain after each iteration?"*
   >
   > [ Open ToolWindow ] [ Dismiss ]

4. Click **Open ToolWindow**. It shows the question plus two follow-ups:
   - *"Which element should `total` represent — the sum ending at `i`, or the next sum?"*
   - *"What should be true about `total` before you enter the append branch?"*

**P1 narration**:
> "The plugin is always watching — locally. Listeners track edits, undo bursts, test failures, cursor dwell on red squigglies. A heuristic engine decides when you're stuck. Four undos in thirty seconds on the same line? That's a signal. It doesn't tell me the answer. It asks me a **Socratic question** — the kind a good mentor asks. That's the trick we stole from rubber-duck debugging research."

**P2 (switches to dashboard in browser at `http://localhost:3000`)**:
> "And every moment gets streamed — still on localhost — to the companion dashboard. Here's the last 24 hours of this developer: **heatmap** of edit density, a **timeline** of every stuck moment with the question that was asked, and my personal **anti-patterns** — apparently I undo-thrash on nested loops twice a week. It's Strava for your coding flow state."

**Visual**: Heatmap with a hot cluster around 14:00–16:00, anti-pattern list showing `undo_burst × 7`, `repeated_failing_test × 3`, timeline card for the moment that just happened.

---

## 2:40 – 3:00 — Differentiation & Close

**P1**:
> "Three things make this win:
> **One** — it's *privacy-first*. Default local Ollama. Your code never leaves the box.
> **Two** — it's *Socratic*, not generative. We're not the 457th Copilot clone.
> **Three** — the *META wellness* layer is a completely new angle: developer wellness as a first-class IDE feature.
>
> Next up: team-mode anti-pattern radar, a VSCode port, and a 'why was I stuck on Tuesday' session replay. But today, three minutes, three pillars, one plugin. Thanks."

*(Stop at 3:00. Do not take questions unless judges ask.)*

---

## Staged Assets Checklist

Put these in the demo project **before** judging:

- [ ] `demo/fizz.py` (Pillar 1 source — exact content above)
- [ ] `demo/bad.py` (Pillar 2 source — exact content above)
- [ ] `demo/stuck.py` (Pillar 3 source — exact content above)
- [ ] Empty `demo/__init__.py` and `pyproject.toml` with pytest configured
- [ ] Dashboard pre-loaded with a seed event file (see fallback §1) so it's not empty on first render
- [ ] Ollama warmed: `ollama run qwen2.5-coder:7b "ok"` returns < 2 s
- [ ] Browser tab at `http://localhost:3000` already open on second monitor
- [ ] IntelliJ zoom level bumped to 150% for visibility

---

## Fallback Plan (if Ollama is slow or misbehaving)

**Symptom thresholds**: if any LLM call takes > 12 s live, switch to mock mode *immediately* (don't wait).

1. **Mock mode** — set env `DEMO_MOCK=1` before `uvicorn` start. Backend returns pre-cached JSON responses from [`backend/tests/fixtures/demo_responses/`](../backend/tests/fixtures/demo_responses/) (files to be produced in Phase 5): `generate_tests__fizzbuzz.json`, `suggest_refactor__bad.json`, `socratic__undo_burst.json`. Responses include a fake 400 ms "thinking" delay for realism.
2. **Pre-recorded GIFs** — keep `docs/assets/pillar1.gif`, `pillar2.gif`, `pillar3.gif` (recorded during rehearsal, checked in Phase 5) as a hard fallback if the IDE crashes.
3. **Network independence check**: disable Wi-Fi for 30 s before demo and run the full flow once — if it works offline against Ollama, it'll work in front of judges.
4. **Second laptop** as hot standby, SSH tunnel ready, same demo project cloned.
5. **Dashboard seed file**: `dashboard/public/seed-events.json` containing ~200 synthetic events covering the last 24 h so the heatmap never looks empty.

If all fails, P1 narrates over the GIFs and sells the architecture diagram. The three pillars are still coherent without a live LLM.

---

## Pre-flight checklist (5 minutes before judging)

Run this *in order*. If any step fails, fix it or fall back to mock mode
before the judges arrive. The automation for steps 1–8 is available as
[`scripts/demo-preflight.sh`](../scripts/demo-preflight.sh) via
`make demo-preflight`.

1. **Ollama model present** — `ollama list | grep qwen2.5-coder:7b`
2. **Ollama daemon reachable** — `curl -fsS http://127.0.0.1:11434/api/tags`
3. **`make demo-preflight` passes all 10 checks** (ports free, zip present, deps installed, git clean).
4. **`make dev-live` starts without errors** — leave it running; watch the first 5 s of output.
5. **Browser at `http://localhost:3000`** shows the green **"Live"** pill in the top-right of the Hero.
6. **IntelliJ tool window "RubberDuck Live"** shows a green connection dot (right-edge panel, pre-opened).
7. **Right-click works** on the `fizzbuzz` function in `demo/fizz.py` → *RubberDuck* submenu is visible with both actions.
8. **Undo 4× quickly** on `demo/stuck.py` (cursor on the `total -= xs[i - k]` line) → balloon appears within ~1 s.
9. **Screen recorder armed** (OBS / QuickTime) with a pre-set 1600×1000 frame. Test the shortcut.
10. **Water bottle. Deep breath. Go.**
