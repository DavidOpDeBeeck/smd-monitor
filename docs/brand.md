# Brand

<picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/smd-mark-dark.svg">
    <img src="brand/smd-mark-light.svg" alt="The SMD mark: smd in Morse code" width="90">
</picture>

## Story

SMD moves intent through a system, and nothing else. A command says what should happen, a query asks what is, and an event says what happened. SMD dispatches each one plainly: no hidden
threads, no silent fallbacks, no framework you cannot leave. It is small enough to read in an afternoon and strict enough to trust in production, and the monitor shows every message and what it
caused.

Three values follow from the [design priorities](../AGENTS.md):

- **Plain.** A small API, direct code, and names that say what they do.
- **Explicit.** Safe defaults; incomplete configuration fails instead of falling back.
- **Observable.** Threads, ordering, and failures are behavior you can see.

## Mark

The mark is `smd` in Morse code, one letter per row: `···` `——` `—··`. The logo is itself a message.

It is drawn on one grid: a dot is 3 units wide and tall, a gap is 1, and a dash is 7, exactly dot + gap + dot, so every row snaps to the same columns. The whole mark is 15 × 11 units. Signs are
fully rounded. The last dot carries the accent color; everything else uses the ink color.

| File                                                 | Use                                                         |
|------------------------------------------------------|-------------------------------------------------------------|
| [`smd-mark-light.svg`](brand/smd-mark-light.svg)     | The mark on light backgrounds                               |
| [`smd-mark-dark.svg`](brand/smd-mark-dark.svg)       | The mark on dark backgrounds                                |
| [`smd-tile.svg`](brand/smd-tile.svg)                 | Favicon, avatar, and anywhere the background is unknown     |
| [`smd-loading.svg`](brand/smd-loading.svg)           | Loading indicator                                           |

Next to the mark, the wordmark is a lowercase `smd` in the heaviest weight of the system sans-serif, followed by a product name in a regular weight and muted color, such as `smd monitor`. Leave at
least one dot of space around the mark, and do not recolor individual signs, add effects, or change the grid.

## Loading

While loading, the mark first draws a faint copy of itself, sign by sign, and then transmits itself over it in Morse timing: signs are keyed one time unit apart and letters three, each sign fades in
as it grows to size, and dashes stretch out to their length. The finished code holds, fades with its copy, and rests before the next transmission. Users who prefer reduced motion see the static mark.

The monitor uses it for all of its loading: in the top bar while it reconnects, on its connecting screen, and while it loads traces, messages, failures, or an invocation from
the hub. It fades in late, so an answer that arrives quickly never shows it. Where there is nothing to show, the monitor shows the mark at rest in the same place, with a
title that says why.

## Color

| Token  | Light     | Dark      |
|--------|-----------|-----------|
| Ink    | `#1f1e1b` | `#ebeae6` |
| Accent | `#5148e0` | `#8d87ff` |
| Paper  | `#f4f3ee` | `#101113` |

The monitor's command, event, and query colors describe messages, not the brand; keep them out of the mark.
