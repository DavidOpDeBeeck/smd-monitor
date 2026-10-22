"""Keeps the saturation runs in docs/saturation and writes the saturation page from them.

The runs, steps, seconds and scenarios files hold every run added so far; setup and profile describe how they were measured. With --add, the results of
one run that SaturationRun wrote (<scenario>.json and <scenario>.csv in build/saturation) are merged into them first: the run becomes the next Run N, or
replaces itself when it is already there. --name gives the run added a short name the page shows instead of Run N, and --rename names a run kept by its
number. Either way the page is written again as one self-contained HTML file, the data inlined, that opens from disk.

Usage: python -I docs/saturation/dashboard.py [--add build/saturation [--only <scenario,...>] [--name <name>]] [--rename <N>=<name> ...] [--keep-seconds 3]
       [--out build/saturation/dashboard.html]
"""
import argparse
import csv
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
DATASETS = ['runs', 'steps', 'seconds', 'scenarios', 'setup', 'profile']
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
        })
        for step in result['steps']:
            steps.append({'run': run_id, 'scenario': result['scenario'], 'rate': step['rate'],
                          **{field: step[source] for field, source in STEP_FIELDS.items()}})
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
    parser.add_argument('--name', help='a short name for the run --add adds, which the page shows instead of Run N; a run added again keeps its name')
    parser.add_argument('--rename', action='append', default=[], help='<N>=<name>: names Run N of the runs kept; <N>= takes its name away')
    parser.add_argument('--keep-seconds', type=int, default=3, help='how many of the latest runs keep their seconds')
    parser.add_argument('--step-seconds', type=int, default=30, help="SaturationScenario's STEP, for results that record neither steps' seconds nor stepS")
    parser.add_argument('--out', type=Path, default=ROOT / 'build' / 'saturation' / 'dashboard.html')
    args = parser.parse_args()

    data = {name: read_json(HERE / f'{name}.json') for name in DATASETS}
    data['seconds'] = expand_seconds(data['seconds'])
    if args.name and not args.add:
        fail('--name names the run --add adds; name a run kept with --rename <N>=<name>')
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
