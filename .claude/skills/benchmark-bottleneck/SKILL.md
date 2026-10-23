---
name: benchmark-bottleneck
description: Autonomously find where the monitor hub's throughput stops and remove it. Reads the saturation results (build/saturation, docs/saturation, JMH), ranks the bottlenecks, tries optimizations one at a time with short before/after probes, and for one that works runs the full saturation benchmark before and after, adds both runs to docs/saturation, screenshots the saturation page as proof (improving the page when it does not show the gain plainly), and opens a concise pull request with the code, the run data, and the screenshots. Runs without asking for approval. Use when asked to analyse benchmark or saturation results, find a bottleneck, or make the hub faster.
---

# Find a bottleneck, remove it, and prove it

Arguments: `$ARGUMENTS`, optionally a scenario (such as `applications-500`), a sweep, or a measure to focus on, and how many pull requests or hours to spend.

Work autonomously: do not stop to ask for approval between steps. Decide from the evidence, keep a journal of what was tried and why, and tell the user at the
end, or when a pull request is open. Stop and ask only when something outside this skill's reach blocks the work (a build that fails before any change, a
dependency that does not resolve, a push or pull request that is refused).

| Step | Runs | Moves on when |
|---|---|---|
| 1. Analyse | Nothing: reads the results that exist | Bottlenecks are ranked, each with a probe to measure it |
| 2. Probe before | The target scenario and one or two check scenarios, on the base commit | The probe shows the bottleneck, and the profile agrees |
| 3. Try a change | `./gradlew test`, then the same probes | A change improves the target and worsens no check, or the candidates run out |
| 4. Prove it | `./gradlew saturation` before and after, about 13 minutes each | Both runs are in `docs/saturation` and the screenshots show the gain |
| 5. Pull request | `git push`, `gh pr create` | The pull request is open and the user is told |

**Stop** after one pull request, or after five changes that did not improve anything, unless the arguments ask for more; then report what the journal holds.

## Ground rules

- **Correctness first.** Follow `AGENTS.md`: one semantic change per pull request, the writer thread's ownership, epochs never mutated after publishing,
  the memory budget still bounding everything the hub keeps, focused tests of caller-visible behavior. A change that is faster but weakens one of these is not
  an optimization; record it in the journal and drop it.
- **One run at a time.** Never run two saturation runs, probes, JMH runs, or test runs at once, from any checkout or agent: they share the machine's cores
  and both measure lower limits. Other agents may follow this skill on the same machine, so run each of them under one lock that every checkout shares; a
  second agent then waits for the first instead of measuring beside it:

  ```bash
  flock /tmp/smd-monitor-bench.lock ./gradlew saturation ...      # and likewise ./gradlew test, ./gradlew jmh
  ```

  Where there is no `flock` (Windows), check before every run that none is going, and wait while one is:

  ```powershell
  Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object CommandLine -match 'SaturationRun|jmh|GradleWorkerMain' | Select-Object ProcessId, CommandLine
  ```

- **One machine.** Limits depend on the machine. Compare only runs of the same machine: probes with probes, and full runs with full runs whose `machine` in
  `docs/saturation/runs.json` names this one. The runs kept there may come from another machine; then they only point at bottlenecks, never serve as a
  "before".
- **Background, no polling.** Run probes, benchmarks, and the tests in the background (Bash `run_in_background`) and wait for the notification.
- **Commands.** Use `python3` (Linux, macOS) or `py` (Windows) for the scripts below. When `./gradlew` is not executable in the checkout, run `bash ./gradlew`
  rather than changing its mode.
- **Branches.** Work on `perf/<slug>`, created from the latest default branch, never on `main` or a branch of the user. Never force-push a branch this skill
  did not create, and never merge a pull request. Revert only changes this skill made.
- **Journal.** Append each analysis, probe, change, and verdict to `build/saturation-probe/journal.md`: what was tried, the numbers, and why it was kept or
  dropped. It is the report when nothing works.

## 1. Analyse the current results

Read, without running anything:

- `build/saturation/<scenario>.json`: the latest result of each scenario, with its limits (`keepsUp`, `holds`, `failsAt`, `failsAs`) and every step's
  measures; `summary.md` for the same as tables, and `<scenario>.csv` for the seconds of a step that needs a closer look.
- `docs/saturation/runs.json`, `steps.json` and `seconds.json` for the runs kept, so a trend or a regression shows; `profile.json` for the last writer profile
  kept; `setup.json` for how they were measured.
- `build/results/jmh/results.json`, when it exists, for the JMH benchmarks of the single-threaded paths.
- `docs/monitor-hub-design.md`, sections *Saturation* and *Open items*, for what is already known and the levers already named.

Read each scenario at its last step that holds and its first that fails:

| Symptom at the limit | Points at |
|---|---|
| `writerBusyMean` near 1 and `writerCpu` close to it | Work on the writer thread: receive, linking, fitting the budget, the tick |
| `writerBusyMean` near 1 but `writerCpu` well below it | The writer waits for a core or for collections: check `gcMillisPerSecond` and the GC logs `gc-*.log` |
| Refuses or sheds with the writer well below half busy | Queue shares and shedding policy, not speed: check the seconds that refuse against collection pauses and the tick |
| `tickLateP99Millis` or `epochLagP99Millis` growing | The tick or building the epoch |
| `horizonSeconds` or `historySeconds` falling | The memory budget gives way, not the writer |
| `frameMillisP50` near 1000 or `framesSkipped` | The publisher writing frames |
| `generatorBound` or `GENERATOR_BOUND` | The load generator, not the hub: not a hub bottleneck |

A refusal fails a step however small, so read the CSV of a failing step second by second: the `busy` and `shed` counters are cumulative, and a refusal that
lands in the second of a long `gcMs` jump, with the writer mostly idle, is a burst after a pause, not a slow writer.

Compare along the sweeps: a sweep whose limit falls fastest (applications, instances, types, budget) names the dimension a cost grows with. Then read the code of
the suspected path, using the package table in `AGENTS.md`, and find the loop or the structure whose cost grows with that dimension.

Rank the bottlenecks by how much limit is at stake. For each, write in the journal: the scenarios and steps that show it, the measures that are the evidence,
the code suspected (`file:line`), the change to try, what it risks among the correctness semantics, and its probe.

Other agents may be working on bottlenecks too. Skip one that a `perf/*` branch or an open pull request already takes on (`git ls-remote origin
'refs/heads/perf/*'`, and the open pull requests), and pick the next.

## 2. Probe before the change

Create the branch from the latest default branch, and note its commit; the working tree must be clean, so the "before" is reproducible:

```bash
git fetch origin main && git switch -c perf/<slug> origin/main && git push -u origin perf/<slug>
```

Pushing the branch at once claims the bottleneck for other agents; name it after the bottleneck, such as `perf/intake-queue-shares`.

Pick the probe: the scenario that shows the bottleneck most, at the rates around its failure (the last that holds, the first that fails, and two above), and
one or two **check scenarios** the change could make worse, such as the opposite end of the same sweep and a writer-bound scenario. Run each into its own
directory, so a full run's results in `build/saturation` stay as they are:

```bash
flock /tmp/smd-monitor-bench.lock ./gradlew saturation -PsaturationScenarios=<scenario> -PsaturationRates=<r1>,<r2>,<r3>,<r4> \
  -PsaturationOutput=saturation-probe/before-<scenario> -PsaturationJfr=build/saturation-probe/before-<scenario>/<scenario>.jfr
```

A probe fills the scenario's whole history at its **first** rate before it measures, and stops at the first failing rate. The fill sets the state the limits
depend on, so a probe is comparable only with a probe of the same scenario and the same rates; a probe's limits are not a full run's. The console's
`filled ... in <E> s` line says how long the fill took. Profile the writer thread over the steps:

```bash
python3 -I .claude/skills/benchmark-bottleneck/writer_profile.py build/saturation-probe/before-<scenario>/<scenario>.jfr --skip <E>
```

It lists the hub's methods by the share of the writer's samples that include them, and the methods running by self time. Use `--thread
saturation-publisher` for the publisher, or `jfr view gc-pauses <recording>` and `jfr view hot-methods <recording>` for the process as a whole. When the path
has a JMH benchmark (`WriterBenchmark`, `FrameBenchmark`, `QueryBenchmark`, `HeapBenchmark`), run it narrowly too:
`flock /tmp/smd-monitor-bench.lock ./gradlew jmh -PjmhIncludes=<Benchmark>`, copying `build/results/jmh/results.json` to the probe's directory.

When the probe does not show the bottleneck, or the profile shows another cost, record that and go back to step 1 with the new evidence.

## 3. Try a change

Make one change, following `AGENTS.md`, with focused tests of caller-visible behavior. Run `flock /tmp/smd-monitor-bench.lock ./gradlew test`, and
`integrationTest` the same way when Spring wiring or endpoints changed; a change whose tests fail is fixed or dropped, never probed. Then run exactly the probes of step 2, with `after-<n>` for `before`
in the output directories: the same scenarios and rates. Compare:

```bash
python3 -I .claude/skills/benchmark-bottleneck/writer_profile.py build/saturation-probe/before-<scenario>/<scenario>.jfr build/saturation-probe/after-<n>-<scenario>/<scenario>.jfr --skip <E>
```

Compare rate by rate from the probes' `<scenario>.json`: the verdict, `processedPerSecond`, `writerBusyMean`, `writerCpu`, `endQueued`, `busyShare`, the shed
shares, `tickLateP99Millis`, `epochLagP99Millis`, `gcMillisPerSecond`, and `heapMaxMegabytes`.

The change **works** when, on the target scenario, a rate's verdict got better and none got worse, or the writer's busy mean at the highest common rate fell
by a tenth or more at the same verdicts with no other measure worse; and no check scenario's verdict got worse. Two passes of a run agree within one step of
the ladder, so a gain of one step alone is noise: probe again before believing it.

When it does not work, revert it, record why in the journal, and try the next candidate: a variant of the same idea when the probe says why it fell short,
otherwise the next bottleneck of step 1.

## 4. Prove it with full runs

Commit the change on the branch, with its tests and the docs `AGENTS.md` asks for (`docs/monitor-hub-design.md` when a decision or budget changes,
`docs/monitor.md` when public behavior changes). Do not change the throughput `docs/monitor.md` documents.

The proof is two full runs on this machine: **before** on the base commit and **after** on the branch's commit, each with the core scenarios, the
default; add `-PsaturationScenarios=all` when the change targets a scenario outside them. When the change touches frames, queries, or the WebSocket, also run
the capacity frontier (`./gradlew saturationCapacity`) before and after, add both with `saturationDashboard -PsaturationCapacity`, and screenshot
`view=capacity`; the viewer ladder (`saturationViewers`) shows what each viewer costs at one rate when the frontier moved and the reason is unclear.

- Name the machine as the earlier runs of this machine in `docs/saturation/runs.json` did; when there are none, name it by processor, threads, memory, and
  operating system, such as `Ryzen 7 5825U, 16 threads, 32 GB, Linux`, and use that name for both runs.
- Reuse a "before" already kept when `runs.json` holds a full run of this machine on the base commit, or on a commit whose `src/main` does not differ from
  it. Otherwise run it: switch to the base commit in a separate worktree (`git worktree add`), so the branch stays as it is.

```bash
flock /tmp/smd-monitor-bench.lock ./gradlew saturation -PsaturationMachine="<machine>"     # in the base worktree for before, on the branch for after
cp -r <that checkout>/build/saturation build/saturation-probe/full-<before|after>
python3 -I docs/saturation/dashboard.py --add build/saturation-probe/full-<before|after> --name "<name>" --out build/saturation-page/dashboard.html
```

Run "before" and add it, then "after" and add it, always into the branch's `docs/saturation`: a run in the base worktree writes that worktree's
`build/saturation`, so copy it into the branch's `build/saturation-probe/full-before` first. Keep both copies until the pull request is merged: they are what
the runs are added again from when `main` moves. Each line the run prints, and `build/saturation/progress.txt`, say which scenario of how many it runs and how
long it still takes. `dashboard.py --add` merges the run into `docs/saturation` as the next Run N and writes the page with the data inlined; the
`docs/saturation/dashboard.html` itself is the template and stays as it is.

Name each run so the page says what it measured, in at most about 40 characters: the "after" by the change, such as `Reserved queue room and pool`, and
the "before" by the same words, such as `Before reserved queue room and pool`. The page shows the name instead of "Run N"; the query string still picks runs by
their number, which `dashboard.py` prints. Name a run kept, or rename one, with `--rename <N>=<name>`.

Compare each scenario's writer capacity (the table of the page's `change` view: spans processed per second of the writer on a core, over the rates both
runs offered) and its `keepsUp`, `holds`, `failsAt` and `failsAs` between the two runs. Runs of one commit differ by a few percent in capacity and up to a
step in a limit. The full runs must confirm the probe: the target scenario's capacity or limit better, no scenario's capacity lower by more than 5%, and no
limit worse by more than one step. When they do not, record it, drop the commit, and go back to step 3.

### Screenshots

The screenshots need Playwright and a Chromium. Prefer a Chromium already on the machine to a download, which can hang: look for a Playwright browser cache
(`find / -maxdepth 5 -type d -name ms-playwright`; on the dev container it is `/opencode/.cache/ms-playwright`, which `/opencode/loom` uses), read the
revision in its directory names (`chromium_headless_shell-<revision>`), and install outside the repo the Playwright version whose `browsers.json` names that
revision (revision 1243 is Playwright 1.63.0):

```bash
npm install --prefix <scratch>/pw playwright@<version>
```

Point `PLAYWRIGHT_BROWSERS_PATH` at the cache, or `CHROMIUM` at a Chromium executable. Only when there is none, run `npx --prefix <scratch>/pw playwright
install chromium-headless-shell` in the background. Then screenshot the page, choosing each view by its query string, with the run numbers of before and after:

```bash
PLAYWRIGHT_BROWSERS_PATH=<cache> NODE_PATH=<scratch>/pw/node_modules node .claude/skills/benchmark-bottleneck/dashboard_screenshots.cjs \
  build/saturation-page/dashboard.html docs/saturation/proof/<slug> \
  change="view=change&runs=<before>,<after>" \
  target="view=steps&scenario=<target>&measure=<measure that shows the mechanism>&runs=<before>,<after>"
```

The script takes each view as a figure: the title, the runs, the chart and its key, 1,000 pixels wide so a pull request shows it unscaled. It fails on a page
without data or with a script error, and grows the window so a view's every row is in the image. Show two figures: `change` (every scenario's change in writer
capacity, labelled) and `steps` for the target, with the measure that shows how the change works (such as `writerBusy`, `capacity`, `busy`,
`shedUnretained`, `gcMsPerS`); its strips under the chart give the verdict at each rate, so the limits show too. Add `view=sweep-applications` only when the
change moves that whole sweep. Look at each screenshot before using it (Read the PNG): it must show the two runs and the difference the pull request claims.

### Improve the page when it hides the gain

The saturation page is part of the work too: we iterate on everything. When no view shows plainly what the change improved, or a view fails, misleads, or
cuts rows off, improve the page rather than settling for a weaker screenshot: a measure or view it lacks, a clearer label or scale, a chart that compares two
runs better, a bug. The page is `docs/saturation/dashboard.html`, a template without data, and `docs/saturation/dashboard.py`, which inlines the data.

- Keep the page self-contained: one HTML file that opens from disk, its state in the query string, no external scripts or fonts.
- Change what serves every run, not this one pull request: no run numbers or scenario names written into the page.
- Compare runs in one chart when each run is a line of a few points on a shared scale, and the comparison happens at a point: whose line is higher at
  a rate, whose limit sits further right. Rate by rate, the sweeps, and the limits per scenario overlay their runs; in panels the eye would jump
  between charts to compare two points. Give each run a panel of its own, on the same axes, when overlaid runs would cover or tangle: stacked parts,
  such as frame delivery, and long series of seconds, such as second by second. `multiples` draws the panels.
- Write the page again with `dashboard.py --out build/saturation-page/dashboard.html`, and screenshot every view (`change`, `overview`, each
  `sweep-<sweep>`, `steps`, `seconds`, `about`, and one with `figure=0`) for the runs kept, so a change that breaks another view shows as the script failing. Look at them.
- Commit it on the branch in a commit of its own, before the data, and say what it changed in one line of the pull request.

Commit the data and the screenshots on the branch in a commit of their own: `docs/saturation/{runs,steps,seconds,scenarios}.json` and
`docs/saturation/proof/<slug>/*.png`.

## 5. Open the pull request

Push the branch (`git push -u origin perf/<slug>`) and open the pull request with `gh pr create --title "<title>" --body-file <file>`. When `gh` is missing,
use the REST API with the `GITHUB_TOKEN` the environment holds; pass it only as `$GITHUB_TOKEN`, never print it or write it to a file:

```bash
python3 -I -c 'import json,sys; print(json.dumps({"title": sys.argv[1], "head": sys.argv[2], "base": "main", "body": open(sys.argv[3]).read()}))' \
  "<title>" perf/<slug> <body file> > build/saturation-probe/pr.json
curl -sS -X POST -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/<owner>/<repo>/pulls --data @build/saturation-probe/pr.json | python3 -I -c 'import json,sys; print(json.load(sys.stdin).get("html_url"))'
```

When neither works, give the user the compare link `https://github.com/<owner>/<repo>/compare/main...perf/<slug>?expand=1` and the body file instead.

Keep the pull request short. The title says what got faster, in a few words. The body has two short paragraphs of prose, and puts every
number in a table:

- **Problem** and **Change** are two sentences each, at most about 50 words, in plain language a reader outside the code follows. Name the
  mechanism, not the method: "the publisher wrote each record's key as text" rather than `FrameDelta.keys`. Leave numbers, percentages, and
  asides out of them; the tables hold the numbers.
- **The target scenario's table** has a row per limit, the capacity, and the measure that shows the mechanism, with each change in parentheses.
- **The other scenarios' table** has a row per scenario whose limit moved or whose capacity changed beyond the ±5% runs of one commit differ by,
  with before → after in each cell; one line under it covers every other scenario. When none moved, that line alone replaces the table.
- **The benchmarks' table** has a row per JMH measure run before and after; leave it out when none ran.
- One line each for the runs and, when it changed, the page.

```markdown
**Problem.** <What limited the hub, and where it showed.> <Why.>

**Change.** <What the code now does instead.> <What it keeps intact: threads, the budget, what each figure counts.>

### <Target scenario>

| | <Before's name> | <After's name> |
|---|---|---|
| Keeps up with | ... | ... |
| Holds without refusing | ... | ... |
| First fails at | ... | ... |
| Writer capacity, spans/s per core | ... | ... (+n%) |
| <The measure that shows the mechanism, at a rate both ran> | ... | ... |

### Other scenarios

| Scenario | Keeps up with | Holds without refusing | First fails at | Writer capacity |
|---|---|---|---|---|
| <Scenario> | 150,000 → 200,000 | ... → ... | ... → ... | +n% |

Every other scenario: the same limits, writer capacity within ±n%.

### Benchmarks

| Benchmark, at the design target | Before | After |
|---|---|---|
| <Benchmark.method>, p50 | ... | ... (−n%) |

**Runs.** Full saturation runs on <machine>, kept in `docs/saturation` as runs <before> and <after>.
**Page.** <When the page changed: what it now shows.>

![What changed between runs](https://github.com/<owner>/<repo>/blob/<commit>/docs/saturation/proof/<slug>/change.png?raw=true)
![<Measure> at <target> rate by rate](https://github.com/<owner>/<repo>/blob/<commit>/docs/saturation/proof/<slug>/target.png?raw=true)
```

Link the screenshots by the commit that added them, so the images stay as they were when the branch moves on.

When `main` moves before the pull request is merged, and above all when it adds runs of its own, the run data conflicts: each pull request numbers its runs
after the last run on its base. Rebase the branch onto `origin/main`, take the run data from `main` (`git checkout origin/main --
docs/saturation/{runs,steps,seconds,scenarios}.json`), add the two runs again from `build/saturation-probe/full-{before,after}` with their names (and `--only` with the scenarios
`scenarios.json` holds, when a run's results hold scenarios `SaturationScenario` no longer defines), take the
screenshots again with the new run numbers, update the body's links, and push with `--force-with-lease`; this branch is this skill's own. Then tell the user: the pull request's link,
the table, and anything the journal holds that the pull request leaves out, such as changes that did not work and the next bottleneck.
