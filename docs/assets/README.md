# 🦆 Visual Assets — recording recipes

This folder stores the hero GIF and three pillar screenshots referenced from
the root [`README.md`](../../README.md). Until the user records them, the
repo ships with a placeholder [`hero.svg`](hero.svg) so the README still
renders cleanly.

All four files are **optional** — missing them just shows a broken image tag
on the rendered README. None of them are build-time assets.

| File             | Purpose                                             | Target size | Approx. length |
| ---------------- | --------------------------------------------------- | ----------- | -------------- |
| `hero.gif`       | 20 s montage of the three pillars                   | 1600×1000   | 20 s           |
| `pillar1.png`    | Generate Tests action + generated pytest file       | 1600×1000   | still          |
| `pillar2.png`    | Suggest Refactor diff viewer with gutter tooltips   | 1600×1000   | still          |
| `pillar3.png`    | Undo-burst balloon + dashboard split-screen         | 1600×1000   | still          |

## Recommended tools (macOS)

Pick whichever you already have installed:

- **Peek** (`brew install --cask peek`) — GIF recorder with a movable frame.
- **Kap** (`brew install --cask kap`) — GIF + MP4, free, native macOS.
- **Gifcap** (browser-based, <https://gifcap.dev>) — zero-install fallback.
- **ffmpeg** — for converting MP4 → GIF.

## Recipe — `hero.gif` (20 s montage)

1. Pre-flight: `make demo-preflight` must pass; `make dev` running;
   IntelliJ open at `demo/fizz.py`.
2. Start Kap, frame IntelliJ at 1600×1000.
3. **0–6 s**: right-click `fizzbuzz` → *Generate Tests* → show the toast +
   generated `test_fizz.py` tab.
4. **6–12 s**: open `demo/bad.py` → select function → *Suggest Refactor* →
   show the diff viewer.
5. **12–20 s**: open `demo/stuck.py` → `Ctrl+Z` four times → show the
   Socratic balloon sliding in. Cut to the dashboard tab showing the new
   stuck-moment timeline entry.
6. Stop. Export as GIF, target ≤ 8 MB. If Kap gives MP4:
   ```bash
   ffmpeg -i hero.mp4 -vf "fps=15,scale=1600:-1:flags=lanczos" \
          -loop 0 docs/assets/hero.gif
   ```

## Recipe — `pillar1.png`

IntelliJ at `demo/fizz.py`. Right-click `fizzbuzz`, hover over
*RubberDuck → Generate Tests*. Take a full-IDE screenshot (`⇧⌘4 + space`),
crop to 1600×1000 with the generated `test_fizz.py` visible in a split pane.

## Recipe — `pillar2.png`

Select the whole `analyze` function in `demo/bad.py`. Right-click →
*Suggest Refactor*. Once the native diff viewer opens, screenshot it with
the gutter tooltip *"nested_loops (O(n³))"* visible.

## Recipe — `pillar3.png`

Split screen: IntelliJ on the left showing the Socratic balloon over
`demo/stuck.py`, browser on the right at `http://localhost:3000` showing
the wellness score + freshly-appended stuck moment card. Use
`⇧⌘4` and drag-select both halves.

## Optimising

```bash
# GIF: strip metadata, re-palette, cap width
gifsicle -O3 --lossy=80 --colors 128 docs/assets/hero.gif -o docs/assets/hero.gif

# PNG: shrink ~30 % losslessly
pngquant --force --ext .png --quality=65-85 docs/assets/pillar*.png
```

## Fallback behaviour

The root README references these files with relative paths. If a file is
missing, GitHub renders a broken-image icon — not a build failure. Safe to
ship without the GIFs if time runs out; the `hero.svg` placeholder will
keep the hero section looking intentional.
