"""Records benchmark runs in docs/benchmarks/runs, checks them, and writes the benchmarks page from them.

Every kind of benchmark follows one process, which docs/benchmarks/README.md describes: run it on a committed tree, record the run under a name, and
commit the file it writes. A run file holds one run of one kind, named <date>-<slug of the name>.<kind>.json, and is never changed afterwards; the
seconds of a saturation run sit beside it in <...>.seconds.json, kept for the newest three saturation runs only.

  record  takes the results the benchmark tasks wrote (build/saturation, build/saturation-capacity, build/saturation-viewers, build/results/jmh),
          keeps each one measured on HEAD as a run file, and writes the page. --results <kind>=<dir> records a run from elsewhere instead, such as one
          of main measured in another worktree, whose commit must be HEAD or one of its ancestors.
  page    writes the page, one self-contained HTML file that opens from disk, the data of every run file inlined.
  check   checks every run file; with --base, also what a pull request changes: it adds run files only, at most one per kind of its own commits and
          one per kind of the base's, each measured on a committed tree.

Usage: python -I docs/benchmarks/benchmarks.py record --name <name> [--results <kind>=<dir> ...] [--out build/benchmarks/page.html]
       python -I docs/benchmarks/benchmarks.py page [--out build/benchmarks/page.html]
       python -I docs/benchmarks/benchmarks.py check [--base <ref> [--head <ref>]]
"""
import argparse
import csv
import json
import math
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RUNS = HERE / 'runs'
DATA_MARK = '/*BENCHMARK_DATA*/'
# Where each kind's task writes its results, and what a run file of that kind holds besides its name, run, and machine.
RESULTS = {
    'saturation': 'build/saturation',
    'capacity': 'build/saturation-capacity',
    'viewers': 'build/saturation-viewers',
    'jmh': 'build/results/jmh',
}
BODY = {'saturation': ['scenarios', 'limits', 'steps'], 'capacity': ['points'], 'viewers': ['points'], 'jmh': ['benchmarks']}
KEEP_SECONDS = 3
STEP_SECONDS = 30
# A run is named by when it started and the commit it measured, marked when the working tree differed from that commit.
RUN_ID = re.compile(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ) ([0-9a-f]{7,40})( with changes)?')

STEP_FIELDS = {
    'offered': 'offeredPerSecond',
    'processed': 'processedPerSecond',
    'busy': 'busyShare',
    'shedUnretained': 'unretainedShare',
    'shedUnjoined': 'unjoinedShare',
    'queueMax': 'maxQueued',
    'queueEnd': 'endQueued',
    'writerCpu': 'writerCpu',
    'writerBusy': 'writerBusyMean',
    'writerBusyMax': 'writerBusyMax',
    'processCores': 'processCores',
    'tickLateMs': 'tickLateP99Millis',
    'epochLagMs': 'epochLagP99Millis',
    'frameMs': 'frameMillisP50',
    'frameMaxMs': 'frameMillisMax',
    'deltaKb': 'deltaKilobytesP50',
    'gcMsPerS': 'gcMillisPerSecond',
    'heapMb': 'heapMaxMegabytes',
    'accountedMb': 'accountedMegabytes',
    'retained': 'retained',
    'horizonS': 'horizonSeconds',
    'historyS': 'historySeconds',
    'verdict': 'verdict',
}
SCENARIO_FIELDS = ['scenario', 'name', 'sweep', 'description', 'applications', 'instances', 'exporters', 'typesPerApplication', 'groupsPerApplication',
                   'memoryMb', 'historyS', 'queueSpans']
# The answers of the OTLP server, for runs that exported over OTLP/HTTP (-PsaturationTransport).
EXPORT_FIELDS = {'exportP50Ms': 'answerP50Millis', 'exportP99Ms': 'answerP99Millis'}
SECOND_FIELDS = ['offered', 'processed', 'busy', 'queued', 'shed', 'writerCpuMs', 'horizonS', 'historyS']
# The frame of each second as clients read it, for runs with clients (-PsaturationClients): the parts of its lag up to the frame, then for the fast
# clients and the slow ones its send, which adds up to the lag of the client in the middle, that lag and the most of a client, and how many read it
# as a snapshot or never read it; -1 where a second has none.
DELIVERY_FIELDS = ['tickMs', 'buildMs', 'waitMs', 'frameMs', 'sendMs', 'lagMs', 'lagMaxMs', 'snapshots', 'unread',
                   'slowSendMs', 'slowLagMs', 'slowLagMaxMs', 'slowSnapshots', 'slowUnread']



def read_json(path):
    with open(path, encoding='utf-8') as file:
        return json.load(file)


def fail(message):
    print(f'benchmarks: {message}', file=sys.stderr)
    sys.exit(1)


# One row per line, so a run shows as lines of its own in a diff.
def write_run(path, header, lists):
    dump = lambda value: json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    parts = [dump(header)[1:-1]] + [f'"{key}":[\n' + ',\n'.join(dump(row) for row in rows) + '\n]' for key, rows in lists.items()]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('{' + ',\n'.join(parts) + '\n}\n', encoding='utf-8', newline='\n')


def slug_of(name):
    return re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')


def git(*arguments):
    result = subprocess.run(['git', *arguments], cwd=ROOT, capture_output=True, text=True)
    return result.returncode, result.stdout.strip()


def commit_of(ref):
    code, out = git('rev-parse', '--verify', '--quiet', f'{ref}^{{commit}}')
    return out if code == 0 else None


def is_ancestor(commit, of):
    return git('merge-base', '--is-ancestor', commit, of)[0] == 0


# The seconds are the largest data, so their file holds one line per scenario: every measure as an array, the rate as [rate, seconds] runs, and t
# only when the seconds do not run 1, 2, 3, ... without a gap.
def compact_seconds(rows):
    groups = {}
    for row in rows:
        groups.setdefault(row['scenario'], []).append(row)
    compacted = []
    for scenario, group in groups.items():
        rates = []
        for row in group:
            if rates and rates[-1][0] == row['rate']:
                rates[-1][1] += 1
            else:
                rates.append([row['rate'], 1])
        ts = [row['t'] for row in group]
        fields = SECOND_FIELDS + [field for field in DELIVERY_FIELDS if field in group[0]]
        compacted.append({'scenario': scenario, 'rates': rates,
                          **({} if ts == list(range(1, len(ts) + 1)) else {'t': ts}),
                          **{field: [row[field] for row in group] for field in fields}})
    return compacted


# The rate a CSV second offered. Each step names its first and last CSV second, as steps that kept up early end early. Results from before that
# had steps of stepSeconds each: second t = CSV second - fillS of step k ran from k * stepSeconds + 1 to (k + 1) * stepSeconds.
def rate_at(result, second, step_seconds):
    steps = result['steps']
    if steps and 'firstSecond' in steps[0]:
        return next((step['rate'] for step in steps if step['firstSecond'] <= second <= step['lastSecond']), None)
    t = second - result['fillS']
    step = (t - 1) // result.get('stepS', step_seconds)
    return steps[step]['rate'] if t >= 1 and step < len(steps) else None


# Each CSV row holds the run's counters when an epoch was published; a second is the difference from the row before. The fill ends at the
# first row, so second t of the steps is CSV second fillS + t. Processed is what the writer drained of the queues. CSVs from before the drained
# column only have accepted, read when the epoch was received, and queued, as of the epoch's tick: their difference moves spans accepted in between
# to the next second, and goes negative when that epoch is received sooner after its tick.
def seconds_of(result, csv_path, step_seconds):
    with open(csv_path, encoding='utf-8') as file:
        rows = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(file)]
    delivered = any(row.get('lagMs', -1) >= 0 for row in rows)
    seconds = []
    for before, after in zip(rows, rows[1:]):
        rate = rate_at(result, after['second'], step_seconds)
        if rate is None:
            continue
        delta = lambda field: after[field] - before[field]
        seconds.append({
            'run': result['run'],
            'scenario': result['scenario'],
            't': after['second'] - result['fillS'],
            'rate': rate,
            'offered': delta('offered'),
            'processed': delta('drained') if 'drained' in after else delta('accepted') - delta('queued'),
            'busy': delta('busy'),
            'queued': after['queued'],
            'shed': delta('shedUnretained') + delta('shedUnjoined'),
            'writerCpuMs': delta('writerCpuMs'),
            'horizonS': after['horizonS'],
            'historyS': after['historyS'],
            **({field: after[field] for field in DELIVERY_FIELDS} if delivered else {}),
        })
    return seconds


# A saturation run: the newest run whose scenario results (<scenario>.json and .csv) the directory holds; results of older runs left beside them,
# such as scenarios an earlier run ran and this one did not, are left out.
def read_saturation(directory):
    results = [read_json(path) for path in sorted(directory.glob('*.json'))]
    results = [result for result in results if 'steps' in result and 'run' in result]
    if not results:
        fail(f'no scenario results (<scenario>.json with steps) in {directory}')
    run_id = max(result['run'] for result in results)
    older = sorted({result['scenario'] for result in results if result['run'] != run_id})
    results = [result for result in results if result['run'] == run_id]
    missing_csv = [result['scenario'] for result in results if not (directory / f"{result['scenario']}.csv").exists()]
    if missing_csv:
        fail(f'no per-second CSV for scenarios {missing_csv} in {directory}')
    limits, steps, seconds, scenarios = [], [], [], []
    for result in results:
        rates = [step['rate'] for step in result['steps']]
        limits.append({
            'scenario': result['scenario'],
            'keepsUp': result['keepsUp'],
            'holds': result['holds'],
            'failsAt': result['failsAt'],
            'failsAs': result['failsAs'],
            'maxOffered': max(rates),
            'maxRate': result['maxRate'],
            # The spans its intake queues held at most, as its budget sized them; queues that count room in bytes hold this many when names are shared.
            'queueSpans': result['queueSpans'],
            # The clients a run with -PsaturationClients served its frames to, and the viewers open (-PsaturationViewers).
            **({'delivery': {field: result['delivery'][field] for field in ['clients', 'slowClients', 'slowCharsPerSecond']}} if 'delivery' in result else {}),
            **({'viewers': result['viewers']['count']} if 'viewers' in result else {}),
        })
        exports = {export['rate']: export for export in result.get('exports', {}).get('steps', [])}
        for step in result['steps']:
            export = exports.get(step['rate'])
            steps.append({'scenario': result['scenario'], 'rate': step['rate'],
                          **{field: step[source] for field, source in STEP_FIELDS.items()},
                          **({field: export[source] for field, source in EXPORT_FIELDS.items()} if export else {})})
        seconds.extend(seconds_of(result, directory / f"{result['scenario']}.csv", STEP_SECONDS))
        scenario = dict(result, exporters=result['applications'] * result['instances'])
        scenarios.append({field: scenario[field] for field in SCENARIO_FIELDS})
    transport = results[0].get('transport', 'direct')
    run = {'run': run_id, 'machine': results[0]['machine'],
           # How the run offered its batches when not directly (-PsaturationTransport).
           **({'transport': transport} if transport != 'direct' else {}),
           'scenarios': scenarios, 'limits': limits, 'steps': steps}
    notes = [f'left out {", ".join(older)} of older runs in {directory}'] if older else []
    return run, compact_seconds(seconds), notes


# A viewer ladder's points (cap-<c>/v-<n>) and the run without viewers (baseline).
def read_viewers(directory):
    baseline_dir = directory / 'baseline'
    paths = sorted(directory.glob('cap-*/v-*/reference.json'))
    if not paths or not (baseline_dir / 'reference.json').exists():
        fail(f'no viewer ladder (baseline/ and cap-<c>/v-<n>/ with reference.json) in {directory}')
    run_id, baseline = viewer_point(baseline_dir, None)
    points = [dict(baseline, viewers=0, cap=None)] + [viewer_point(path.parent, baseline['capacity'])[1] for path in paths]
    points.sort(key=lambda point: (point.get('cap') or 0, point.get('viewers', 0)))
    return {'run': run_id, 'machine': baseline['machine'], 'points': points}, None, []


# The measures of one point of a viewer ladder: one rate offered while the viewers were open, over the seconds after the step settled. A viewer's
# page is fresh in a second when the hub answered the query it asked after that second's epoch.
def viewer_point(directory, baseline_capacity):
    result = read_json(directory / 'reference.json')
    step = result['steps'][0]
    first, last = step['firstSecond'] + 5, step['lastSecond']
    capacity = step['processedPerSecond'] / step['writerCpu'] if step['writerCpu'] else None
    point = {
        'scenario': result['scenario'], 'rate': step['rate'], 'verdict': step['verdict'], 'machine': result['machine'],
        'capacity': capacity, 'capacityShare': capacity / baseline_capacity if capacity and baseline_capacity else None,
        'processCores': step['processCores'], 'heapMb': step['heapMaxMegabytes'], 'gcMsPerS': step['gcMillisPerSecond'],
        'epochLagMs': step['epochLagP99Millis'],
    }
    queries = directory / 'reference-queries.csv'
    if 'queries' in result and queries.exists():
        viewers = result['queries']['viewers']
        with open(queries, encoding='utf-8') as file:
            rows = [row for row in csv.DictReader(file) if first <= int(row['second']) <= last]
        # A page asks after every frame it reads, so the seconds some page asked after are the frames published: every second, or every few when
        # the publisher sends a frame less often. A page is fresh for a frame when the hub answered the query it asked after it.
        published = {int(row['second']) for row in rows} or {first}
        fresh = {page: set() for page in range(viewers)}
        for row in rows:
            if row['answer'] in ('answered', 'unchanged'):
                fresh[int(row['page'])].add(int(row['second']))
        shares = sorted(len(seconds) / len(published) for seconds in fresh.values())
        answered = sorted(float(row['answerMs']) for row in rows if row['answer'] in ('answered', 'unchanged'))
        point.update({
            'viewers': viewers, 'cap': result['queries']['queryCap'],
            'answered': sum(row['answer'] in ('answered', 'unchanged') for row in rows) / len(rows) if rows else None,
            'freshMedian': shares[len(shares) // 2] if shares else None, 'freshWorst': shares[0] if shares else None,
            'answerP50Ms': percentile(answered, 0.5), 'answerP99Ms': percentile(answered, 0.99),
        })
    if 'viewers' in result:
        point['viewerCores'] = result['viewers']['steps'][0]['cores']
    if 'delivery' in result:
        clients = result['delivery']['steps'][0]['fast']
        point.update({'framesReceived': clients['received'] / clients['expected'] if clients['expected'] else None,
                      'frameLagP99Ms': clients['lagP99Millis'] if clients['received'] else None, 'snapshots': clients['snapshots']})
    return result['run'], point


def percentile(values, quantile):
    return values[min(len(values) - 1, max(0, math.ceil(quantile * len(values)) - 1))] if values else None


# A frontier's points (SaturationCapacity's capacity.json and the climbs in v-<n>, v-<n>-from-<rate>): for each number of viewers the highest rate at
# which ingest kept up and, with viewers, their UIs stayed usable, with what the hub and the viewers measured there and what stopped the climb
# above it; the first number of viewers with no usable rate closes the frontier without one.
def read_capacity(directory):
    capacity_file = directory / 'capacity.json'
    if not capacity_file.exists():
        fail(f'no capacity frontier (capacity.json) in {directory}')
    capacity = read_json(capacity_file)
    limits = {limit['viewers']: limit['rate'] for limit in capacity['limits']}
    climbs = {}
    for climb in sorted(directory.glob('v-*')):
        viewers = int(climb.name.split('-')[1])
        if (climb / 'reference.json').exists():
            climbs.setdefault(viewers, []).append(read_json(climb / 'reference.json'))
    points = []
    for viewers in sorted(climbs):
        results = climbs[viewers]
        rate = limits.get(viewers)
        point = {'viewers': viewers, 'rate': rate, 'machine': results[0]['machine']}
        for result in results:
            step = next((step for step in result['steps'] if step['rate'] == rate), None)
            if step:
                point.update(processCores=step['processCores'], heapMb=step['heapMaxMegabytes'])
            usable = next((each for each in result.get('usability', []) if each['rate'] == rate), None)
            if usable:
                point.update(frameLagP99Ms=usable['frameLagP99Millis'], answerP99Ms=usable['answerP99Millis'], freshWorst=usable['freshWorst'])
        point['stoppedBy'] = stopped_by(results, viewers, rate)
        points.append(point)
    if not points:
        fail(f'no climbs (v-<n>/reference.json) beside {capacity_file}')
    return {'run': capacity['run'], 'machine': points[0]['machine'], 'points': points}, None, []


# The JMH benchmarks of one ./gradlew jmh: results.json, and the run.json the task writes before it starts, which names the run and the machine.
def read_jmh(directory):
    results_file, stamp_file = directory / 'results.json', directory / 'run.json'
    if not results_file.exists():
        fail(f'no JMH results (results.json) in {directory}')
    if not stamp_file.exists():
        fail(f'{results_file} has no run.json beside it to name its run; run the benchmarks with ./gradlew jmh, which writes it')
    if results_file.stat().st_mtime < stamp_file.stat().st_mtime:
        fail(f'{results_file} is older than {stamp_file}: the last ./gradlew jmh stopped before it wrote its results; run it again')
    stamp = read_json(stamp_file)
    number = lambda value: value if isinstance(value, (int, float)) and math.isfinite(value) else None
    benchmarks = []
    for result in read_json(results_file):
        metric = result['primaryMetric']
        percentiles = metric.get('scorePercentiles', {})
        benchmarks.append({'benchmark': '.'.join(result['benchmark'].split('.')[-2:]),
                           **({'params': result['params']} if result.get('params') else {}),
                           'mode': result['mode'], 'unit': metric['scoreUnit'], 'score': number(metric['score']), 'error': number(metric.get('scoreError')),
                           'p50': number(percentiles.get('50.0')), 'p99': number(percentiles.get('99.0'))})
    if not benchmarks:
        fail(f'{results_file} holds no benchmarks')
    benchmarks.sort(key=lambda each: (each['benchmark'], json.dumps(each.get('params', {}), sort_keys=True)))
    return {'run': stamp['run'], 'machine': stamp['machine'], 'benchmarks': benchmarks}, None, []


READERS = {'saturation': read_saturation, 'capacity': read_capacity, 'viewers': read_viewers, 'jmh': read_jmh}
# What shows a directory holds results of its kind at all, so record passes over the kinds that were not run.
MARKERS = {'saturation': '*.csv', 'capacity': 'capacity.json', 'viewers': 'baseline/reference.json', 'jmh': 'results.json'}


# What stopped the climbs of one number of viewers above the limit: the lowest rate at which a climb failed, its UIs not usable or, without viewers
# or where ingest failed first, ingest; a climb again from a lower rate stops below the first climb's failure, or passes every rate it offers.
def stopped_by(results, viewers, limit):
    stops = sorted(stop for stop in (first_stop(result, viewers) for result in results) if stop and (limit is None or stop[0] > limit))
    return stops[0][1] if stops else 'the highest rate offered'


def first_stop(result, viewers):
    unusable = next((each for each in result.get('usability', []) if not each['usable']), None)
    failed = next((step for step in result['steps'] if step['verdict'] != 'KEEPS_UP'), None)
    if viewers and unusable and (not failed or unusable['rate'] <= failed['rate']):
        return unusable['rate'], f"{unusable['rate']:,} spans/s: " + ', '.join(unusable['shortfalls'])
    if failed:
        return failed['rate'], f"{failed['rate']:,} spans/s: ingest {failed['verdict'].lower().replace('_', ' ')}"
    return None


# Every run file kept, checked as it is read: (path, run) in the order of the kinds, then of when each run started.
def load_runs():
    runs = []
    for path in sorted(RUNS.glob('*.json')):
        if path.name.endswith('.seconds.json'):
            continue
        try:
            run = read_json(path)
        except ValueError as error:
            fail(f'{path.name} is not JSON: {error}')
        kind = run.get('kind')
        if kind not in BODY:
            fail(f'{path.name} has kind {kind!r}; expected one of {", ".join(BODY)}')
        missing = [key for key in ['name', 'run', 'machine', *BODY[kind]] if key not in run]
        if missing:
            fail(f'{path.name} lacks {", ".join(missing)}')
        match = RUN_ID.fullmatch(run['run'])
        if not match:
            fail(f'{path.name} has run {run["run"]!r}; expected "<started>Z <commit>", as the benchmark tasks name a run')
        expected = f'{match.group(1)[:10]}-{slug_of(run["name"])}.{kind}.json'
        if path.name != expected:
            fail(f'{path.name} holds run {run["name"]!r} of {match.group(1)[:10]}; expected it named {expected}')
        runs.append((path, run))
    seen = {}
    for path, run in runs:
        other = seen.setdefault((run['kind'], run['run']), path)
        if other != path:
            fail(f'{other.name} and {path.name} hold the same {run["kind"]} run {run["run"]}')
    for path in sorted(RUNS.glob('*.seconds.json')):
        owner = RUNS / path.name.replace('.seconds.json', '.json')
        if not owner.exists() or read_json(path).get('run') != read_json(owner)['run']:
            fail(f'{path.name} holds seconds of no run file kept; expected the seconds of {owner.name}')
    runs.sort(key=lambda each: (list(BODY).index(each[1]['kind']), each[1]['run']))
    return runs


def seconds_file(path):
    return path.with_name(path.name.replace('.json', '.seconds.json'))


def record(name, chosen, out):
    slug = slug_of(name)
    if not slug:
        fail(f'--name {name!r} has no letters or digits to name the run file by')
    head = commit_of('HEAD')
    recorded = {(run['kind'], run['run']) for _, run in load_runs()}
    sources = [(kind, ROOT / directory, False) for kind, directory in RESULTS.items() if kind not in chosen]
    sources += [(kind, directory, True) for kind, directory in chosen.items()]
    added, notes = [], []
    for kind, directory, explicit in sources:
        if not any(directory.glob(MARKERS[kind])):
            if explicit:
                fail(f'{directory} holds no {kind} results ({MARKERS[kind]})')
            continue
        run, seconds, read_notes = READERS[kind](directory)
        match = RUN_ID.fullmatch(run['run'])
        if not match:
            fail(f'{directory} holds run {run["run"]!r}, which names no commit; run it again in a git checkout')
        if match.group(3):
            fail(f'{directory} holds run {run["run"]}, measured with uncommitted changes; commit them and run it again, so the run file measures its commit')
        commit = commit_of(match.group(2))
        if not commit:
            fail(f'{directory} holds run {run["run"]} of commit {match.group(2)}, which this repository does not have')
        if not explicit and commit != head:
            notes.append(f'{kind}: passed over {directory}, a run of {match.group(2)}, not of HEAD {head[:7]}; record it with --results {kind}={directory}')
            continue
        if explicit and not is_ancestor(commit, head):
            fail(f'{directory} holds run {run["run"]} of commit {match.group(2)}, which is neither HEAD nor one of its ancestors')
        if (kind, run['run']) in recorded:
            notes.append(f'{kind}: passed over {directory}, run {run["run"]} is recorded already')
            continue
        path = RUNS / f'{match.group(1)[:10]}-{slug}.{kind}.json'
        if path.exists():
            fail(f'{path.relative_to(ROOT)} exists already; give this run another name')
        write_run(path, {'kind': kind, 'name': name, **{key: value for key, value in run.items() if key not in BODY[kind]}},
                  {key: run[key] for key in BODY[kind]})
        if seconds:
            write_run(seconds_file(path), {'run': run['run']}, {'seconds': seconds})
        added.append(path)
        notes += [f'{kind}: {note}' for note in read_notes]
    if not added:
        fail('recorded nothing: ' + ('; '.join(notes) if notes else f'no results in {", ".join(RESULTS.values())}'))
    # The seconds are the largest data; only the newest saturation runs keep theirs.
    saturation = [path for path, run in sorted(load_runs(), key=lambda each: each[1]['run'], reverse=True) if run['kind'] == 'saturation']
    for path in saturation[KEEP_SECONDS:]:
        if seconds_file(path).exists():
            seconds_file(path).unlink()
            notes.append(f'saturation: dropped the seconds of {path.name}')
    for path in added:
        print(f'Recorded {path.relative_to(ROOT)}')
    for note in notes:
        print(note)
    write_page(out)


# The run's colour, order, and name on the page; the order counts the runs of one kind in the order they started.
def page_data():
    metas = []
    data = {'runs': metas, 'limits': [], 'steps': [], 'seconds': [], 'scenarios': [], 'setup': read_json(HERE / 'setup.json'),
            'capacity': [], 'viewers': [], 'jmh': []}
    scenarios = {}
    order = {}
    for path, run in load_runs():
        kind = run['kind']
        order[kind] = order.get(kind, 0) + 1
        label = f'{run["name"]}, {run["run"]}'
        if run.get('transport'):
            label = f'{label}, over {"OTLP/HTTP, gzipped" if run["transport"] == "otlp-gzip" else "OTLP/HTTP"}'
        meta = {'kind': kind, 'run': run['run'], 'slug': slug_of(run['name']), 'name': run['name'], 'order': order[kind], 'label': label,
                'machine': run['machine']}
        metas.append(meta)
        tag = lambda rows: [{'run': run['run'], **row} for row in rows]
        if kind == 'saturation':
            data['limits'] += tag(run['limits'])
            data['steps'] += tag(run['steps'])
            if seconds_file(path).exists():
                data['seconds'] += tag(read_json(seconds_file(path))['seconds'])
            scenarios.update({scenario['scenario']: scenario for scenario in run['scenarios']})
        else:
            data[kind] += tag(run[BODY[kind][0]])
    # The page lists scenarios in this order: the reference, then each sweep by its swept value, then the profiles.
    sweep_order = ['reference', 'applications', 'instances', 'types', 'budget', 'profile']
    sweep_value = {'applications': 'applications', 'instances': 'instances', 'types': 'typesPerApplication', 'budget': 'memoryMb'}
    data['scenarios'] = sorted(scenarios.values(), key=lambda scenario: (
        sweep_order.index(scenario['sweep']) if scenario['sweep'] in sweep_order else len(sweep_order),
        scenario.get(sweep_value[scenario['sweep']], 0) if scenario['sweep'] in sweep_value else 0,
        scenario['scenario']))
    return data


# The data goes into a script element as JSON; "</" is escaped so no value can end that element.
def write_page(out):
    template = (HERE / 'page.html').read_text(encoding='utf-8')
    if template.count(DATA_MARK) != 1:
        fail(f'{HERE / "page.html"} must hold {DATA_MARK} exactly once, where the data goes')
    payload = json.dumps(page_data(), ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/').replace('<!--', '<\\!--')
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(template.replace(DATA_MARK, payload), encoding='utf-8', newline='\n')
    print(f'Wrote {out}')


# What a pull request may change in the runs: add run files, at most one per kind measured on its own commits (the change) and one per kind
# measured on a commit of the base (main measured again, when its newest run does not compare), each on a committed tree; and drop seconds. A run
# file once on the base is never changed or removed. A change's run that no longer measures the head's code only warns: running again is the
# author's call.
def check(base, head):
    runs = load_runs()
    page_data()
    print(f'{len(runs)} run files hold valid runs')
    if not base:
        return
    base_commit, head_commit = commit_of(base), commit_of(head)
    if not base_commit or not head_commit:
        fail(f'--base {base} and --head {head} must name commits this repository has; fetch the base with its history')
    if not git('ls-tree', '-d', base_commit, 'docs/benchmarks/runs')[1]:
        print(f'{base} keeps no runs in docs/benchmarks/runs yet; nothing to compare')
        return
    errors, warnings, counts = [], [], {}
    for line in git('diff', '--name-status', '--no-renames', f'{base_commit}...{head_commit}', '--', 'docs/benchmarks/runs')[1].splitlines():
        status, name = line.split('\t')
        path = ROOT / name
        if path.name.endswith('.seconds.json'):
            if status not in 'AD':
                errors.append(f'{path.name} was changed; the seconds of a run are added with it and only ever dropped')
            continue
        if status != 'A':
            errors.append(f'{path.name} was {"removed" if status == "D" else "changed"}; a run on {base} stays as it is, so record a new run instead')
            continue
        run = read_json(path)
        match = RUN_ID.fullmatch(run['run'])
        if match.group(3):
            errors.append(f'{path.name} was measured with uncommitted changes; commit them, run it again, and record that run')
            continue
        commit = commit_of(match.group(2))
        of_base = bool(commit) and is_ancestor(commit, base_commit)
        counts.setdefault((run['kind'], of_base), []).append(path.name)
        if not commit:
            warnings.append(f'{path.name} measured {match.group(2)}, which this repository does not have; was the branch rebased after the run?')
        elif not of_base and not is_ancestor(commit, head_commit):
            warnings.append(f'{path.name} measured {match.group(2)}, which the branch no longer holds; was it rebased after the run?')
        elif not of_base and git('diff', '--quiet', commit, head_commit, '--', 'src/main')[0] != 0:
            warnings.append(f'{path.name} measured {match.group(2)}, and src/main changed since; run it again when the change affects what it measures')
    for (kind, of_base), names in sorted(counts.items()):
        if len(names) > 1:
            errors.append(f'{", ".join(names)} are {len(names)} {kind} runs of {"the base" if of_base else "this change"}; keep the one that measures '
                          f'{"main" if of_base else "the change"}')
    github = os.environ.get('GITHUB_ACTIONS') == 'true'
    for warning in warnings:
        print(f'::warning::{warning}' if github else f'warning: {warning}')
    for error in errors:
        print(f'::error::{error}' if github else f'error: {error}', file=sys.stderr)
    if errors:
        sys.exit(1)
    added = sum(len(names) for names in counts.values())
    print(f'Adds {added} run file{"" if added == 1 else "s"} to {base}' if added else f'Adds no runs to {base}')


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    record_parser = commands.add_parser('record', help='keeps the runs the benchmark tasks wrote for HEAD, and writes the page')
    record_parser.add_argument('--name', required=True, help='what the runs measured, such as the change: "Scoped specimen walks"')
    record_parser.add_argument('--results', action='append', default=[], help='<kind>=<dir>: records the run in dir, of HEAD or an ancestor')
    record_parser.add_argument('--out', type=Path, default=ROOT / 'build' / 'benchmarks' / 'page.html')
    page_parser = commands.add_parser('page', help='writes the page')
    page_parser.add_argument('--out', type=Path, default=ROOT / 'build' / 'benchmarks' / 'page.html')
    check_parser = commands.add_parser('check', help='checks the run files and, with --base, what a pull request changes in them')
    check_parser.add_argument('--base', help='the commit a pull request merges into, such as origin/main')
    check_parser.add_argument('--head', default='HEAD', help="the pull request's commit")
    args = parser.parse_args()

    if args.command == 'record':
        chosen = {}
        for each in args.results:
            kind, separator, directory = each.partition('=')
            if not separator or kind not in RESULTS or not directory:
                fail(f'--results takes <kind>=<dir>, kind one of {", ".join(RESULTS)}; got {each!r}')
            chosen[kind] = Path(directory).resolve()
        record(args.name.strip(), chosen, args.out)
    elif args.command == 'page':
        write_page(args.out)
    else:
        check(args.base, args.head)


if __name__ == '__main__':
    main()
