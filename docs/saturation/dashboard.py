"""Keeps the saturation runs in docs/saturation and writes the saturation page from them.

The runs, steps, seconds and scenarios files hold every run added so far; setup and profile describe how they were measured. With --add, the results of
one run that SaturationRun wrote (<scenario>.json and <scenario>.csv in build/saturation) are merged into them first: the run becomes the next Run N, or
replaces itself when it is already there. With --add-viewers, the points of a viewer ladder (SaturationViewerLadder, in build/saturation-viewers) become
the next viewer run in the viewers file instead, and with --add-capacity the frontier of span rate and open UIs (SaturationCapacity, in
build/saturation-capacity) the next run in the capacity file. --name gives the run added a short name the page shows instead of Run N, and --rename names a run kept by its
number. Either way the page is written again as one self-contained HTML file, the data inlined, that opens from disk.

Usage: python -I docs/saturation/dashboard.py [--add build/saturation [--only <scenario,...>] [--name <name>]] [--rename <N>=<name> ...] [--keep-seconds 3]
       [--add-viewers build/saturation-viewers [--name <name>]] [--add-capacity build/saturation-capacity [--name <name>]]
       [--out build/saturation/dashboard.html]
"""
import argparse
import csv
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DATASETS = ['runs', 'steps', 'seconds', 'scenarios', 'setup', 'profile', 'viewers', 'capacity']
DATA_MARK = '/*SATURATION_DATA*/'

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


# One row per line, so a run added shows as added lines in a diff.
def write_rows(path, rows):
    with open(path, 'w', encoding='utf-8', newline='\n') as file:
        file.write('[\n' + ',\n'.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) for row in rows) + '\n]\n')


# The seconds are the largest dataset, so their file holds one line per run and scenario: every measure as an array, the rate as [rate, seconds]
# runs, and t only when the seconds do not run 1, 2, 3, ... without a gap. The script and the page work with one row per second.
def compact_seconds(rows):
    groups = {}
    for row in rows:
        groups.setdefault((row['run'], row['scenario']), []).append(row)
    compacted = []
    for (run, scenario), group in groups.items():
        rates = []
        for row in group:
            if rates and rates[-1][0] == row['rate']:
                rates[-1][1] += 1
            else:
                rates.append([row['rate'], 1])
        ts = [row['t'] for row in group]
        fields = SECOND_FIELDS + [field for field in DELIVERY_FIELDS if field in group[0]]
        compacted.append({'run': run, 'scenario': scenario, 'rates': rates,
                          **({} if ts == list(range(1, len(ts) + 1)) else {'t': ts}),
                          **{field: [row[field] for row in group] for field in fields}})
    return compacted


def expand_seconds(groups):
    rows = []
    for group in groups:
        rates = [rate for rate, count in group['rates'] for _ in range(count)]
        ts = group.get('t', range(1, len(rates) + 1))
        fields = SECOND_FIELDS + [field for field in DELIVERY_FIELDS if field in group]
        for index, (t, rate) in enumerate(zip(ts, rates)):
            rows.append({'run': group['run'], 'scenario': group['scenario'], 't': t, 'rate': rate,
                         **{field: group[field][index] for field in fields}})
    return rows


def fail(message):
    print(f'dashboard: {message}', file=sys.stderr)
    sys.exit(1)


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


def add_run(data, results_dir, only, name, keep_seconds, step_seconds):
    results = [read_json(path) for path in sorted(results_dir.glob('*.json'))]
    results = [result for result in results if 'steps' in result and 'run' in result and (not only or result['scenario'] in only)]
    if not results:
        fail(f'no scenario results (<scenario>.json with steps) in {results_dir}')
    run_ids = sorted({result['run'] for result in results})
    if len(run_ids) > 1:
        fail(f'{results_dir} holds results of several runs {run_ids}; pass --only to take the scenarios of one, or clear the directory and run again')
    run_id = run_ids[0]
    missing_csv = [result['scenario'] for result in results if not (results_dir / f"{result['scenario']}.csv").exists()]
    if missing_csv:
        fail(f'no per-second CSV for scenarios {missing_csv} in {results_dir}')

    orders = {run['run']: run['order'] for run in data['runs']}
    replaced = run_id in orders
    order = orders.get(run_id, max(orders.values(), default=0) + 1)
    name = name or next((run['name'] for run in data['runs'] if run['run'] == run_id and run.get('name')), None)
    label = label_of(order, name, run_id)
    transport = results[0].get('transport', 'direct')
    if transport != 'direct':
        label = f'{label}, over {"OTLP/HTTP, gzipped" if transport == "otlp-gzip" else "OTLP/HTTP"}'

    runs = [run for run in data['runs'] if run['run'] != run_id]
    steps = [step for step in data['steps'] if step['run'] != run_id]
    seconds = [second for second in data['seconds'] if second['run'] != run_id]
    by_scenario = {scenario['scenario']: scenario for scenario in data['scenarios']}
    # A run kept from before runs recorded their queue capacity takes its scenario's, which every run had then.
    runs = [run if 'queueSpans' in run or run['scenario'] not in by_scenario else dict(run, queueSpans=by_scenario[run['scenario']]['queueSpans'])
            for run in runs]
    for result in results:
        rates = [step['rate'] for step in result['steps']]
        runs.append({
            'run': run_id,
            'label': label,
            'order': order,
            **({'name': name} if name else {}),
            'machine': result['machine'],
            'scenario': result['scenario'],
            'keepsUp': result['keepsUp'],
            'holds': result['holds'],
            'failsAt': result['failsAt'],
            'failsAs': result['failsAs'],
            'maxOffered': max(rates),
            'maxRate': result['maxRate'],
            # The spans its intake queues held at most, as its budget sized them; queues that count room in bytes hold this many when names are shared.
            'queueSpans': result['queueSpans'],
            # The clients a run with -PsaturationClients served its frames to.
            **({'delivery': {field: result['delivery'][field] for field in ['clients', 'slowClients', 'slowCharsPerSecond']}} if 'delivery' in result else {}),
            # How a run offered its batches when not directly (-PsaturationTransport), and the viewers open (-PsaturationViewers).
            **({'transport': result['transport']} if result.get('transport', 'direct') != 'direct' else {}),
            **({'viewers': result['viewers']['count']} if 'viewers' in result else {}),
        })
        exports = {export['rate']: export for export in result.get('exports', {}).get('steps', [])}
        for step in result['steps']:
            export = exports.get(step['rate'])
            steps.append({'run': run_id, 'scenario': result['scenario'], 'rate': step['rate'],
                          **{field: step[source] for field, source in STEP_FIELDS.items()},
                          **({field: export[source] for field, source in EXPORT_FIELDS.items()} if export else {})})
        seconds.extend(seconds_of(result, results_dir / f"{result['scenario']}.csv", step_seconds))
        scenario = dict(result, exporters=result['applications'] * result['instances'])
        by_scenario[result['scenario']] = {field: scenario[field] for field in SCENARIO_FIELDS}

    # The seconds are the largest dataset; only the latest runs keep them.
    order_of = {run['run']: run['order'] for run in runs}
    kept = set(sorted(set(order_of.values()))[-keep_seconds:]) if keep_seconds > 0 else set()
    dropped = sorted({second['run'] for second in seconds if order_of.get(second['run']) not in kept}, key=lambda run: order_of[run])
    seconds = [second for second in seconds if order_of.get(second['run']) in kept]

    # The page lists scenarios in this order: the reference, then each sweep by its swept value, then the profiles.
    sweep_order = ['reference', 'applications', 'instances', 'types', 'budget', 'profile']
    sweep_value = {'applications': 'applications', 'instances': 'instances', 'types': 'typesPerApplication', 'budget': 'memoryMb'}
    scenarios = sorted(by_scenario.values(), key=lambda scenario: (
        sweep_order.index(scenario['sweep']) if scenario['sweep'] in sweep_order else len(sweep_order),
        scenario.get(sweep_value[scenario['sweep']], 0) if scenario['sweep'] in sweep_value else 0,
        scenario['scenario']))
    runs.sort(key=lambda run: (run['order'], run['scenario']))
    steps.sort(key=lambda step: (order_of[step['run']], step['scenario'], step['rate']))
    seconds.sort(key=lambda second: (order_of[second['run']], second['scenario'], second['t']))

    machines = sorted({run['machine'] for run in runs if run['run'] != run_id})
    return dict(data, runs=runs, steps=steps, seconds=seconds, scenarios=scenarios), {
        'run': run_id,
        'label': label,
        'replaced': replaced,
        'machine': results[0]['machine'],
        'otherMachines': [machine for machine in machines if machine != results[0]['machine']],
        'scenarios': sorted(result['scenario'] for result in results),
        'runs': len({run['run'] for run in runs}),
        'secondsDroppedFor': dropped,
    }


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


# A viewer ladder's points (cap-<c>/v-<n>) and the run without viewers (baseline) become the next viewer run, or replace it when added again.
def add_viewers(data, ladder_dir, name):
    baseline_dir = ladder_dir / 'baseline'
    points = sorted(ladder_dir.glob('cap-*/v-*/reference.json'))
    if not points or not (baseline_dir / 'reference.json').exists():
        fail(f'no viewer ladder (baseline/ and cap-<c>/v-<n>/ with reference.json) in {ladder_dir}')
    base_id, baseline = viewer_point(baseline_dir, None)
    rows = []
    for path in points:
        _, point = viewer_point(path.parent, baseline['capacity'])
        rows.append(point)
    run_id = base_id
    viewers = data['viewers']
    orders = {row['run']: row['order'] for row in viewers}
    order = orders.get(run_id, max(orders.values(), default=0) + 1)
    name = name or next((row['name'] for row in viewers if row['run'] == run_id and row.get('name')), None)
    label = f'{name or f"Viewers {order}"}, {run_id}'
    kept = [row for row in viewers if row['run'] != run_id]
    added = [dict({'run': run_id, 'label': label, 'order': order, **({'name': name} if name else {})}, viewers=0, cap=None, **baseline)]
    added += [dict({'run': run_id, 'label': label, 'order': order, **({'name': name} if name else {})}, **point) for point in rows]
    added.sort(key=lambda row: (row['cap'] or 0, row['viewers']))
    return dict(data, viewers=kept + added), {'run': run_id, 'label': label, 'points': len(rows), 'replaced': run_id in orders}


# A frontier's points (SaturationCapacity's capacity.json and the climbs in v-<n>, v-<n>-from-<rate>): for each number of viewers the highest rate at
# which ingest kept up and, with viewers, their UIs stayed usable, with what the hub and the viewers measured there and what stopped the climb
# above it; the first number of viewers with no usable rate closes the frontier without one.
def add_capacity(data, capacity_dir, name):
    capacity_file = capacity_dir / 'capacity.json'
    if not capacity_file.exists():
        fail(f'no capacity frontier (capacity.json) in {capacity_dir}')
    capacity = read_json(capacity_file)
    limits = {limit['viewers']: limit['rate'] for limit in capacity['limits']}
    climbs = {}
    for directory in sorted(capacity_dir.glob('v-*')):
        viewers = int(directory.name.split('-')[1])
        if (directory / 'reference.json').exists():
            climbs.setdefault(viewers, []).append(read_json(directory / 'reference.json'))
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
        point['stoppedBy'] = stopped_by(results[-1], viewers)
        points.append(point)
    run_id = capacity['run']
    kept = data['capacity']
    orders = {row['run']: row['order'] for row in kept}
    order = orders.get(run_id, max(orders.values(), default=0) + 1)
    name = name or next((row['name'] for row in kept if row['run'] == run_id and row.get('name')), None)
    label = f'{name or f"Capacity {order}"}, {run_id}'
    added = [dict({'run': run_id, 'label': label, 'order': order, **({'name': name} if name else {})}, **point) for point in points]
    rows = [row for row in kept if row['run'] != run_id] + added
    return dict(data, capacity=rows), {'run': run_id, 'label': label, 'points': len(points), 'replaced': run_id in orders}


# What ended a climb: the first step whose UIs were not usable, or, without viewers or where ingest failed first, the step that failed.
def stopped_by(result, viewers):
    unusable = next((each for each in result.get('usability', []) if not each['usable']), None)
    if viewers and unusable:
        return f"{unusable['rate']:,} spans/s: " + ', '.join(unusable['shortfalls'])
    failed = next((step for step in result['steps'] if step['verdict'] != 'KEEPS_UP'), None)
    return f"{failed['rate']:,} spans/s: ingest {failed['verdict'].lower().replace('_', ' ')}" if failed else 'the highest rate offered'


# A run is labelled by its name when it has one, and by its number otherwise, followed by when it started and the commit it ran.
def label_of(order, name, run_id):
    return f'{name or f"Run {order}"}, {run_id}'


# Names the runs kept by their number: "7=Reserved queue room" names Run 7; an empty name, "7=", takes the name away.
def rename_runs(runs, renames):
    orders = {run['order'] for run in runs}
    for rename in renames:
        number, separator, name = rename.partition('=')
        if not separator or not number.strip().isdigit() or int(number) not in orders:
            fail(f'--rename takes <N>=<name> for a run kept, N one of {sorted(orders)}; got {rename!r}')
        for run in runs:
            if run['order'] == int(number):
                run.pop('name', None)
                if name.strip():
                    run['name'] = name.strip()
                run['label'] = label_of(run['order'], run.get('name'), run['run'])
    return [{field: run[field] for field in ['run', 'label', 'order', 'name'] if field in run} | run for run in runs]


# The data goes into a script element as JSON; "</" is escaped so no value can end that element.
def write_page(data, out):
    template = (HERE / 'dashboard.html').read_text(encoding='utf-8')
    if template.count(DATA_MARK) != 1:
        fail(f'{HERE / "dashboard.html"} must hold {DATA_MARK} exactly once, where the data goes')
    payload = json.dumps(data, ensure_ascii=False, separators=(',', ':')).replace('</', '<\\/').replace('<!--', '<\\!--')
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(template.replace(DATA_MARK, payload), encoding='utf-8', newline='\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--add', type=Path, help='a results directory of one run to add, such as build/saturation')
    parser.add_argument('--only', default='', help='comma-separated scenario IDs to take from the results; all by default')
    parser.add_argument('--name', help='a short name for the run --add, --add-viewers, or --add-capacity adds, which the page shows instead of its number; a run added again '
                                       'keeps its name')
    parser.add_argument('--add-viewers', type=Path, help='a viewer ladder to add, such as build/saturation-viewers')
    parser.add_argument('--add-capacity', type=Path, help='a frontier of span rate and open UIs to add, such as build/saturation-capacity')
    parser.add_argument('--rename', action='append', default=[], help='<N>=<name>: names Run N of the runs kept; <N>= takes its name away')
    parser.add_argument('--keep-seconds', type=int, default=3, help='how many of the latest runs keep their seconds')
    parser.add_argument('--step-seconds', type=int, default=30, help="SaturationScenario's STEP, for results that record neither steps' seconds nor stepS")
    parser.add_argument('--out', type=Path, default=ROOT / 'build' / 'saturation' / 'dashboard.html')
    args = parser.parse_args()

    data = {name: read_json(HERE / f'{name}.json') if (HERE / f'{name}.json').exists() else [] for name in DATASETS}
    data['seconds'] = expand_seconds(data['seconds'])
    if args.name and not args.add and not args.add_viewers and not args.add_capacity:
        fail('--name names the run --add, --add-viewers, or --add-capacity adds; name a run kept with --rename <N>=<name>')
    if sum(bool(each) for each in [args.add, args.add_viewers, args.add_capacity]) > 1:
        fail('--add, --add-viewers, and --add-capacity add one run each; add them one after the other')
    if args.add_capacity:
        data, summary = add_capacity(data, args.add_capacity, args.name)
        write_rows(HERE / 'capacity.json', data['capacity'])
        print(json.dumps(summary, indent=2, ensure_ascii=False))
    if args.add_viewers:
        data, summary = add_viewers(data, args.add_viewers, args.name)
        write_rows(HERE / 'viewers.json', data['viewers'])
        print(json.dumps(summary, indent=2, ensure_ascii=False))
    if args.add:
        only = {each.strip() for each in args.only.split(',') if each.strip()}
        data, summary = add_run(data, args.add, only, args.name, args.keep_seconds, args.step_seconds)
        for name in ['runs', 'steps', 'scenarios']:
            write_rows(HERE / f'{name}.json', data[name])
        write_rows(HERE / 'seconds.json', compact_seconds(data['seconds']))
        print(json.dumps(summary, indent=2, ensure_ascii=False))
    if args.rename:
        data['runs'] = rename_runs(data['runs'], args.rename)
        write_rows(HERE / 'runs.json', data['runs'])
    write_page(dict(data, seconds=compact_seconds(data['seconds'])), args.out)
    print(f'Wrote {args.out}')


if __name__ == '__main__':
    main()
