import { Key } from '@solid-primitives/keyed';
import { createMemo, on, Show } from 'solid-js';
import { selectedApplication, selectedSequence, selectedTypeKey, useMonitorApp } from '../../app/monitor-app';
import { Empty } from '../../components/empty';
import { Glyph } from '../../components/Glyph';
import type { ListHold } from '../../components/hold';
import { createElementSize } from '../../components/size';
import { cx } from '../../lib/cx';
import { count, parseTypeKey, shortName } from '../../lib/format';
import { liveTypeFilter } from '../../lib/live';
import { laneGroups, laneRows, laneSignature, timelineTicks, type LaneRow } from '../../lib/timeline';
import type { Activity, TypeKey } from '../../lib/types';
import { TIMELINE_LANE_HEIGHT, TimelineMark } from './TimelineMark';
import { TickGuides, TickLabels } from './TimelineTicks';

const COLUMNS = 'grid grid-cols-[220px_minmax(0,1fr)] max-md:grid-cols-[140px_minmax(0,1fr)]';

const laneKey = (row: LaneRow) => (row.type === 'lane' ? row.key : null);
const headerApplication = (row: LaneRow) => (row.type === 'application' ? row.application : null);

function ApplicationHeader(props: { name: string }) {
    const app = useMonitorApp();
    return (
        <div
            class={cx('flex items-end border-b bg-surface-2 px-3 pb-1', selectedApplication(app.selection()) === props.name && 'bg-accent/10')}
            style={{ height: `${TIMELINE_LANE_HEIGHT}px` }}
        >
            <button
                type="button"
                title={`Inspect ${props.name}`}
                onClick={() => app.select({ type: 'application', name: props.name })}
                class="truncate text-2xs font-semibold tracking-[.05em] text-muted uppercase hover:text-fg hover:underline"
            >
                {props.name}
            </button>
        </div>
    );
}

function LaneLabel(props: { typeKey: TypeKey; count: number }) {
    const app = useMonitorApp();
    const type = () => parseTypeKey(props.typeKey);
    return (
        <div
            class={cx('flex items-center gap-2 border-b pr-3 pl-2', selectedTypeKey(app.selection()) === props.typeKey && 'bg-accent/10')}
            style={{ height: `${TIMELINE_LANE_HEIGHT}px` }}
        >
            <button
                type="button"
                title={type().messageType}
                onClick={() => app.select({ type: 'type', key: props.typeKey })}
                class="group/select flex min-w-0 items-center gap-[7px] rounded-sm px-1 py-0.5 text-left"
            >
                <Glyph kind={type().kind} />
                <span class="truncate font-semibold group-hover/select:underline">{shortName(type().messageType)}</span>
            </button>
            <span class="ml-auto font-mono text-2xs text-muted">{count(props.count)}</span>
        </div>
    );
}

// One lane per message type in causal order within each application; the system scope heads each application's lanes with its name.
export function TimelineLanes(props: { marks: Activity[]; time: number; trace: ReadonlySet<number> | null; hold: ListHold }) {
    const app = useMonitorApp();
    const live = app.live;
    let axis!: HTMLDivElement;
    const size = createElementSize(() => axis);
    const width = () => size().width;

    const signature = createMemo(() => laneSignature(app.scoped().applications));
    const groups = createMemo(on(signature, () => laneGroups(app.scoped().applications)));
    const keepsType = createMemo(() => liveTypeFilter(live.filters, app.scope()));
    const rows = createMemo(() =>
        laneRows(groups(), app.scope() === null, key => {
            const { application, kind } = parseTypeKey(key);
            return keepsType()(application, kind, key);
        }),
    );
    const laneIndex = createMemo(() => new Map(rows().flatMap((row, index) => (row.type === 'lane' ? [[row.key, index] as const] : []))));
    const scale = createMemo(() => width() / live.span());
    const origin = createMemo(
        on([scale, () => props.time], ([currentScale, current], previousInput, previous: number | undefined) =>
            previous === undefined || previousInput?.[0] !== currentScale || current - previous > live.span() ? current : previous,
        ),
    );
    const counts = createMemo(() => {
        const result = new Map<TypeKey, number>();
        for (const message of props.marks) {
            const key = message.typeKey;
            result.set(key, (result.get(key) ?? 0) + 1);
        }
        return result;
    });
    const ticks = createMemo(() => timelineTicks(live.span(), width()));
    const laneCount = () => laneIndex().size;
    const hasLanes = () => groups().some(group => group.lanes.length > 0);

    return (
        <div class="relative min-h-0 flex-1 overflow-y-auto">
            <div class={cx(COLUMNS, 'sticky top-0 z-2 min-h-[30px] border-b bg-surface-2 text-xs text-muted')}>
                <div class="flex items-center px-3 font-semibold tracking-[.05em] uppercase">Message type</div>
                <div ref={axis} class="relative">
                    <TickLabels ticks={ticks()} span={live.span()} />
                </div>
            </div>
            <div class={COLUMNS}>
                <div class="border-r">
                    <Key each={rows()} by="id">
                        {row => (
                            <Show when={laneKey(row())} fallback={<ApplicationHeader name={headerApplication(row())!} />}>
                                {key => <LaneLabel typeKey={key()} count={counts().get(key()) ?? 0} />}
                            </Show>
                        )}
                    </Key>
                </div>
                <div onPointerDown={props.hold.press} class="relative overflow-hidden" style={{ height: `${rows().length * TIMELINE_LANE_HEIGHT}px` }}>
                    <Key each={rows()} by="id">
                        {(row, index) => (
                            <div
                                class={cx(
                                    'absolute inset-x-0 border-b',
                                    laneKey(row()) === null ? 'bg-surface-2' : selectedTypeKey(app.selection()) === laneKey(row()) && 'bg-accent/10',
                                )}
                                style={{ top: `${index() * TIMELINE_LANE_HEIGHT}px`, height: `${TIMELINE_LANE_HEIGHT}px` }}
                            />
                        )}
                    </Key>
                    <TickGuides ticks={ticks()} span={live.span()} />
                    <div
                        aria-hidden="true"
                        class="absolute inset-y-0 left-0 will-change-transform"
                        style={{ transform: `translateX(${width() - (props.time - origin()) * scale()}px)` }}
                    >
                        <Key each={props.marks} by="sequence">
                            {message => {
                                const lane = () => laneIndex().get(message().typeKey);
                                return (
                                    <Show when={lane() !== undefined}>
                                        <TimelineMark
                                            message={message()}
                                            lane={lane()!}
                                            origin={origin()}
                                            scale={scale()}
                                            selected={selectedSequence(app.selection()) === message().sequence}
                                            dimmed={props.trace !== null && !props.trace.has(message().sequence)}
                                        />
                                    </Show>
                                );
                            }}
                        </Key>
                    </div>
                </div>
            </div>
            <Show when={laneCount() === 0}>
                <Show
                    when={hasLanes()}
                    fallback={
                        <Empty title="Nothing on the lanes yet" class="py-14">
                            Every bus invocation appears here as it happens, on a lane per message type.
                        </Empty>
                    }
                >
                    <Empty title="No matching types" onClear={live.clear} class="py-14">
                        Choose other types or applications to see their lanes.
                    </Empty>
                </Show>
            </Show>
        </div>
    );
}
