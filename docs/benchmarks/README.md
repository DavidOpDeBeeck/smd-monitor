# Benchmarks

Every benchmark of the hub follows one process: **run it, record the run, commit the file.** Main keeps one run per merged change and kind; its newest is
the baseline every pull request compares with on the benchmarks page.

## The kinds

| Kind         | Task                                     | Takes        | Measures                                                                      |
|--------------|------------------------------------------|--------------|-------------------------------------------------------------------------------|
| `saturation` | `./gradlew saturation`                   | 13 minutes   | Where ingest stops keeping up, scenario by scenario, rate by rate             |
| `capacity`   | `./gradlew saturationCapacity`           | 30 minutes   | How many usable open UIs the hub serves at which span rate                    |
| `viewers`    | `./gradlew saturationViewers`            | 25 minutes   | What open UIs get and what they cost the hub, at one rate                     |
| `jmh`        | `./gradlew jmh -PjmhIncludes=<Benchmark>` | minutes each | The single-threaded paths at the design target: receive, tick, frames, queries |

[`monitor-hub-design.md`](../monitor-hub-design.md#saturation) describes what each measures and how. `./gradlew saturationCheck` is not a kind: it checks
the throughput `docs/monitor.md` documents and records nothing.

## The process

1. **Run** the kinds your change affects, on the reference machine, otherwise idle, with every change committed:

   ```bash
   ./gradlew saturation
   ```

   Each run names its machine from the hardware, the same way for every kind and every run: the processor's model, its threads, the memory rounded
   up to a multiple of 8 GB, and the operating system, such as `AMD Ryzen 7 5825U, 16 threads, 32 GB, Linux`. Runs compare only with runs of the same
   name; the page warns when the runs picked ran on different machines.

2. **Record** them under one name, what the change does, in a few words:

   ```bash
   ./gradlew benchmarkRecord -PbenchmarkName="Scoped specimen walks"
   ```

   It keeps each kind's latest results that measured `HEAD` as a run file in `docs/benchmarks/runs`, `<date>-<slug of the name>.<kind>.json`, and
   writes the page, `build/benchmarks/page.html`. It refuses a run measured with uncommitted changes, and passes over results of another commit.

3. **Commit** the run files with the change. The page opens on the newest two runs of each kind: main's baseline and your change.

## Seeing the results

- **Main:** <https://davidopdebeeck.github.io/smd-monitor/>, published from main's run files whenever they change.
- **A pull request:** CI publishes its page under `pr-preview/pr-<number>/` while it is open and comments the link, with the views of the kinds of
  runs it adds. The page opens on main's newest run of each kind against the pull request's.
- **Locally:** `./gradlew benchmarkPage`, then open `build/benchmarks/page.html`.

The state of the page is in its query string, so a link opens a view: `?view=steps&scenario=applications-500&measure=writerBusy`.

## The rules

- **A pull request adds one run per kind it affects**, measured on its own commit. Probes, profiles, and runs that did not work out stay in `build`.
- **Main is not measured again** unless its newest run does not compare with yours: another machine or setup, or a change to `src/main` since that the
  run would see. Then measure main's commit in another worktree, and record it from there with its own name in the same pull request:

  ```bash
  ./gradlew benchmarkRecord -PbenchmarkName="Main at 1a2b3c4" -PbenchmarkResults=saturation=../main-worktree/build/saturation
  ```

- **Runs are never changed.** A run file on main stays as it is; a new measurement is a new run. Only the newest three saturation runs keep their
  seconds (`*.seconds.json`); recording a fourth drops the oldest's.
- **The page compares runs by their slug.** `?view=change&runs=main-at-1a2b3c4,scoped-specimen-walks` links a comparison; one change's runs share their
  slug across kinds.

CI checks every run file and, on a pull request, that it only adds runs: at most one per kind of its own commits and one of main's, each from a
committed tree. It warns when `src/main` changed after a run was measured.

## Commands

```bash
./gradlew benchmarkRecord -PbenchmarkName="..."     # keeps the runs of HEAD and writes build/benchmarks/page.html
./gradlew benchmarkPage                             # writes the page from the runs kept
python3 -I docs/benchmarks/benchmarks.py check --base origin/main    # what CI checks
```

`benchmarks.py` needs Python 3; Gradle runs it with `python3`, or `py` on Windows, or `-PbenchmarkPython`. The page is `page.html`, a template the
script fills with every run's data, so it opens from disk; `setup.json` describes how the runs were measured.
