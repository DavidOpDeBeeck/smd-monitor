---
name: benchmark-bottleneck
description: Analyse the monitor hub's current benchmark results (the saturation run in build/saturation, the runs kept in docs/saturation, JMH results) to find where its throughput stops and why, propose a code change, measure it with a short targeted probe before and after, and run the full saturation benchmark only once the probe shows an improvement. Asks for confirmation before every step. Use when asked to analyse benchmark or saturation results, find a bottleneck, or make the hub faster.
---

# Find a bottleneck in the benchmark results and remove it

Arguments: `$ARGUMENTS`, optionally a scenario (such as `applications-200`), a sweep, or a measure to focus on.

The work goes through five steps. **Each step ends at a gate: stop, report, and ask with AskUserQuestion. Never start the next step without a yes.**
A "no" or another answer replaces the plan; never treat an earlier yes as covering a later step.

| Step | Runs | Gate before the next step |
|---|---|---|
| 1. Analyse | Nothing: reads the results that exist | Which bottleneck, and the probe to measure it |
| 2. Probe before | One scenario at a few rates, with a flight recording | The code change |
| 3. Change | `./gradlew test` | The probe after the change |
| 4. Probe after | The same probe as step 2 | The full benchmark |
| 5. Full benchmark | `./gradlew saturation`, about 70 minutes | Adding the run to the saturation page |

Never run the full benchmark to find or confirm a bottleneck: steps 1 to 4 read existing results and run probes only. Never run two saturation runs or
probes at once, from any checkout: they share the machine's cores and both measure lower limits. Before every run, check that none is going:

```powershell
Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object CommandLine -match 'SaturationRun|jmh' | Select-Object ProcessId, CommandLine
```

Run probes and benchmarks in the background (Bash `run_in_background`) and wait for the notification; do not poll.

## 1. Analyse the current results

Read, without running anything:

- `build/saturation/<scenario>.json`: the latest result of each scenario, with its limits (`keepsUp`, `holds`, `failsAt`, `failsAs`) and every step's
  measures. Their `run` field names the run; if the files name several runs, say so and analyse the latest one of each scenario.
- `build/saturation/summary.md` for the same steps as tables, and `<scenario>.csv` for the seconds of a step that needs a closer look.
- `docs/saturation/runs.json` and `steps.json` for the runs before it, so a trend or a regression shows; `docs/saturation/profile.json` for the last
  writer profile kept; `docs/saturation/setup.json` for how they were measured. Compare only runs of the same `machine`.
- `build/results/jmh/results.json`, when it exists, for the JMH benchmarks of the single-threaded paths.
- `docs/monitor-hub-design.md`, sections *Saturation* and *Open items*, for what is already known and the levers already named.

Read each scenario at its last step that holds and its first that fails. What the measures say:

| Symptom at the limit | Points at |
|---|---|
| `writerBusyMean` near 1 and `writerCpu` close to it | Work on the writer thread: receive, linking, fitting the budget, the tick |
| `writerBusyMean` near 1 but `writerCpu` well below it | The writer waits for a core or for collections: check `gcMillisPerSecond` and the GC logs `gc-*.log` |
| `tickLateP99Millis` or `epochLagP99Millis` growing | The tick or building the epoch |
| `unretainedShare` or `unjoinedShare` from low rates on, writer idle | Queue shares and shedding policy, not speed |
| `horizonSeconds` or `historySeconds` falling | The memory budget gives way, not the writer |
| `frameMillisP50` near 1000 or `framesSkipped` | The publisher writing frames |
| `generatorBound` or `GENERATOR_BOUND` | The load generator, not the hub: not a hub bottleneck |

Compare along the sweeps: a sweep whose limit falls fastest (applications, instances, types, budget) names the dimension a cost grows with, such as a
pass over every application per eviction. Then read the code of the suspected path, using the package table in `AGENTS.md`, and find the loop or the
structure whose cost grows with that dimension.

**Gate 1.** Report, ranked by how much limit is at stake:

- each bottleneck: the scenarios and steps that show it, the measures that are the evidence, the code suspected (`file:line`), and why it costs what it costs;
- for each, the change proposed, the effect expected on the limits, and what it risks among the correctness semantics in `AGENTS.md` (which thread runs it,
  what each figure counts, what yields under memory pressure, epochs never mutated after publishing);
- the probe that would measure it: the scenario that shows it most, the rates around its failure (the last that holds, the first that fails, and the next
  rate of the ladder), the fill, and about how long it takes (the scenario's fill, about 30 seconds a rate, and half a minute to start).

Ask which bottleneck to pursue and whether to run that probe.

## 2. Probe before the change

Note the commit and whether the working tree differs (`git status --short`), so the "before" is reproducible. Run the probe into its own directory, so the
full run's results in `build/saturation` stay as they are:

```bash
./gradlew saturation -PsaturationScenarios=<scenario> -PsaturationRates=<rate>,<rate>,<rate> -PsaturationOutput=saturation-probe/before -PsaturationJfr=build/saturation-probe/before/<scenario>.jfr
```

Keep the scenario's own fill: the limits depend on the state the fill leaves. Shorten it with `-PsaturationFill=PT2M` only when the user agrees, and then
for the probe after too. A probe of one scenario runs in one JVM, so the recording covers the fill and the steps; the console's `filled ... in <E> s` line
says how long the fill took. Profile the writer thread over the steps:

```bash
py -I .claude/skills/benchmark-bottleneck/writer_profile.py build/saturation-probe/before/<scenario>.jfr --skip <E>
```

It lists the hub's methods by the share of the writer's samples that include them, and the methods running by self time. Use `--thread
saturation-publisher` for the publisher, or `jfr view gc-pauses <recording>` and `jfr view hot-methods <recording>` for the process as a whole.

When the path has a JMH benchmark (`ReceiveBenchmark`, `WriterBenchmark`, `RelinkBenchmark`, `FrameBenchmark`, `QueryBenchmark`, `HeapBenchmark`),
also offer a narrow run of it, which measures the path without the run's noise: `./gradlew jmh -PjmhIncludes=<Benchmark>`,
and copy `build/results/jmh/results.json` to `build/saturation-probe/before/jmh.json`.

**Gate 2.** Report the probe's verdict and measures at each rate, and whether the profile confirms the suspected code (its share of the writer's samples).
If it does not, say what the profile shows instead and go back to gate 1 with that evidence. If it does, present the change: the files, what the code does
instead, how it keeps the correctness semantics, the tests it adds or changes, and the docs it touches. Ask whether to make it.

## 3. Change the code

Make that change and nothing else, following `AGENTS.md`: one semantic slice, the writer thread's ownership, the budget still bounding everything the hub keeps,
focused tests of caller-visible behavior. Run `./gradlew test`, and `./gradlew integrationTest` when Spring wiring or endpoints changed. Update
`docs/monitor-hub-design.md` when a design decision or budget changes. Do not commit.

**Gate 3.** Report the change (the diff in a few lines) and the test results. If tests fail, say so with the output and fix or ask before going on. Ask
whether to run the probe after the change.

## 4. Probe after the change

Run exactly the probe of step 2, with `after` for `before` in the output directory and the recording; the same scenario, rates, and fill. Then compare:

```bash
py -I .claude/skills/benchmark-bottleneck/writer_profile.py build/saturation-probe/before/<scenario>.jfr build/saturation-probe/after/<scenario>.jfr --skip <E>
```

Use the `--skip` of each probe's own fill when they differ; run the script once per recording then.

Compare rate by rate from `build/saturation-probe/{before,after}/<scenario>.json`: the verdict, `processedPerSecond`, `writerBusyMean`, `writerCpu`,
`endQueued`, the shed shares, `tickLateP99Millis`, `epochLagP99Millis`, `gcMillisPerSecond`, and `heapMaxMegabytes`. The change improved something when a rate's
verdict got better and none got worse, or when at the same verdicts the writer's busy mean at the highest common rate fell by a tenth or more with no other
measure worse. Two passes of a full run agree within one step, so a smaller difference is noise: say so rather than claim it.

**Gate 4.** Report before and after side by side, and the profile's shift (the suspected method's share before and after).

- Improved: ask whether to run the full benchmark (about 70 minutes, the machine kept idle meanwhile). Offer first, as an option, a probe of one other
  scenario that the change could make worse, such as the opposite end of the same sweep.
- Not improved, or worse: say so plainly, and ask whether to revise the change, try the next bottleneck of gate 1, or revert. Never revert without a yes.

## 5. Run the full benchmark

The full run overwrites `build/saturation`. First check that the run already there is in `docs/saturation/runs.json` (its `run` field); if not, ask whether to
add it first with the `saturation-dashboard` skill, so the comparison keeps its "before". Name the machine as the earlier runs in `runs.json` did:

```bash
./gradlew saturation -PsaturationMachine="<machine of the earlier runs>"
```

Every line it prints, and `build/saturation/progress.txt`, says which scenario of how many it runs and how long it still takes; read them when asked
how far it is. When it ends, compare each scenario's `keepsUp`, `holds`, `failsAt` and `failsAs` with the run before it in `docs/saturation/runs.json`.

**Gate 5.** Report the comparison scenario by scenario, every regression included. Ask whether to add the run to the saturation page with the
`saturation-dashboard` skill. Offer, without doing it unasked: updating `docs/saturation/profile.json` from the probe's profile after the change, the
*Saturation* and *Open items* sections of `docs/monitor-hub-design.md`, and the throughput `docs/monitor.md` documents (checked with
`./gradlew saturationCheck`). Commit only when asked.
