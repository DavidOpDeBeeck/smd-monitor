import { createEffect, createMemo, createSignal, For, on, Show } from 'solid-js';
import { selectedSequence, useMonitorApp } from '../../app/monitor-app';
import { Empty } from '../../components/empty';
import { Loading } from '../../components/loading';
import { Glyph } from '../../components/Glyph';
import type { ListHold } from '../../components/hold';
import { STATUS_TEXT } from '../../components/tones';
import { createElementSize } from '../../components/size';
import { cx } from '../../lib/cx';
import { clock, duration, roundDuration, shortName, STATUS_LABEL, threadLabel } from '../../lib/format';
import { FAILED_LATENCY_TICKS, LATENCY_TICKS, nearestPoint, scatterBaseline, scatterY, type PlottedPoint } from '../../lib/latency-plot';
import { failureTypeOf } from '../../lib/model';
import { spanLabel, timelineTicks, traceMarks, type Mark } from '../../lib/timeline';
import type { MessageKind } from '../../lib/types';
import { TickGuides, TickLabels } from './TimelineTicks';

const COLUMNS = 'grid grid-cols-[76px_minmax(0,1fr)]';
const DOT_RADIUS = 3.5;
const HIT_RADIUS = 9;
const AGE_FADE = 0.65;
const DIMMED = 0.12;

type Hover = { message: Mark; x: number; y: number };

interface Palette {
    kind: Record<MessageKind, string>;
    failed: string;
    accent: string;
    fg: string;
    line: string;
    lineStrong: string;
}

function readPalette(element: Element): Palette {
    const style = getComputedStyle(element);
    const color = (name: string) => style.getPropertyValue(`--color-${name}`).trim();
    return {
        kind: { COMMAND: color('command'), EVENT: color('event'), QUERY: color('query') },
        failed: color('failed'),
        accent: color('accent'),
        fg: color('fg'),
        line: color('line'),
        lineStrong: color('line-strong'),
    };
}

function kindPath(context: CanvasRenderingContext2D, kind: MessageKind, x: number, y: number, radius: number) {
    context.beginPath();
    if (kind === 'COMMAND') {
        const reach = radius * 1.3;
        context.moveTo(x, y - reach);
        context.lineTo(x + reach, y);
        context.lineTo(x, y + reach);
        context.lineTo(x - reach, y);
        context.closePath();
    } else if (kind === 'EVENT') {
        context.rect(x - radius, y - radius, radius * 2, radius * 2);
    } else {
        context.arc(x, y, radius, 0, Math.PI * 2);
    }
}

function horizontalLine(context: CanvasRenderingContext2D, y: number, width: number, color: string) {
    context.strokeStyle = color;
    context.lineWidth = 1;
    context.beginPath();
    context.moveTo(0, Math.round(y) + 0.5);
    context.lineTo(width, Math.round(y) + 0.5);
    context.stroke();
}

export function LatencyScatter(props: { marks: Mark[]; loading: boolean; time: number; trace: ReadonlySet<number> | null; hold: ListHold }) {
    const app = useMonitorApp();
    const live = app.live;
    let track!: HTMLDivElement;
    let canvas!: HTMLCanvasElement;
    const size = createElementSize(() => track);
    const [hover, setHover] = createSignal<Hover | null>(null);
    let points: PlottedPoint<Mark>[] = [];

    const ticks = createMemo(() => timelineTicks(live.span(), size().width));
    const highlighted = createMemo(() => {
        const current = hover();
        return current ? traceMarks(props.marks, current.message.sequence) : props.trace;
    });
    const axisLabels = createMemo(() => {
        const height = size().height;
        return [
            ...LATENCY_TICKS.map(tick => ({ y: scatterY(tick, false, height), text: roundDuration(tick), failed: false })),
            ...FAILED_LATENCY_TICKS.map(tick => ({ y: scatterY(tick, true, height), text: roundDuration(tick), failed: true })),
        ];
    });

    function drawScatter(context: CanvasRenderingContext2D, palette: Palette, width: number, height: number) {
        const scale = width / live.span();
        const baseline = scatterBaseline(height);
        context.fillStyle = palette.failed;
        context.globalAlpha = 0.04;
        context.fillRect(0, baseline, width, height - baseline);
        context.globalAlpha = 1;
        for (const tick of LATENCY_TICKS) horizontalLine(context, scatterY(tick, false, height), width, palette.line);
        for (const tick of FAILED_LATENCY_TICKS) horizontalLine(context, scatterY(tick, true, height), width, palette.line);
        horizontalLine(context, baseline, width, palette.lineStrong);

        points = [];
        for (let index = props.marks.length - 1; index >= 0; index--) {
            const message = props.marks[index];
            const x = width - (props.time - message.startedAtMillis) * scale;
            if (x < -DOT_RADIUS) continue;
            points.push({ item: message, x, y: scatterY(message.outcome.durationMillis, message.outcome.status === 'FAILED', height) });
        }
        const trace = highlighted();
        if (trace) {
            const plotted = new Map(points.map(point => [point.item.sequence, point]));
            context.strokeStyle = palette.fg;
            context.globalAlpha = 0.45;
            for (const point of points) {
                if (!trace.has(point.item.sequence)) continue;
                const ancestor = point.item.markedAncestor;
                const from = ancestor !== null && plotted.get(ancestor);
                if (!from) continue;
                context.beginPath();
                context.moveTo(from.x, from.y);
                context.lineTo(point.x, point.y);
                context.stroke();
            }
        }
        const selected = selectedSequence(app.selection());
        const ordered = trace
            ? [...points.filter(point => !trace.has(point.item.sequence)), ...points.filter(point => trace.has(point.item.sequence))]
            : points;
        for (const { item: message, x, y } of ordered) {
            const age = (props.time - message.startedAtMillis) / live.span();
            const faded = 1 - AGE_FADE * Math.min(1, Math.max(0, age));
            const alpha = trace && !trace.has(message.sequence) ? faded * DIMMED : faded;
            const color = message.outcome.status === 'FAILED' ? palette.failed : palette.kind[message.kind];
            context.globalAlpha = alpha;
            kindPath(context, message.kind, x, y, DOT_RADIUS);
            context.fillStyle = color;
            context.fill();
            if (message.sequence === selected) {
                context.globalAlpha = 1;
                context.strokeStyle = palette.accent;
                context.lineWidth = 2;
                context.beginPath();
                context.arc(x, y, DOT_RADIUS + 4, 0, Math.PI * 2);
                context.stroke();
            }
        }
        context.globalAlpha = 1;
    }

    createEffect(() => {
        const { width, height } = size();
        if (!width || !height) return;
        const ratio = devicePixelRatio || 1;
        if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
            canvas.width = Math.round(width * ratio);
            canvas.height = Math.round(height * ratio);
        }
        const context = canvas.getContext('2d');
        if (!context) return;
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        context.clearRect(0, 0, width, height);
        const palette = readPalette(canvas);
        drawScatter(context, palette, width, height);
    });

    function pointAt(event: PointerEvent): Hover | null {
        const bounds = track.getBoundingClientRect();
        const x = event.clientX - bounds.left;
        const y = event.clientY - bounds.top;
        const point = nearestPoint(points, x, y, HIT_RADIUS);
        return point ? { message: point.item, x: point.x, y: point.y } : null;
    }

    function inspect() {
        const current = hover();
        if (current) app.select({ type: 'message', sequence: current.message.sequence });
    }

    createEffect(on(size, () => setHover(null), { defer: true }));

    return (
        <div class="flex min-h-0 flex-1 flex-col">
            <div class={cx(COLUMNS, 'min-h-[30px] border-b bg-surface-2 text-xs text-muted')}>
                <div class="flex items-center px-3 font-semibold tracking-[.05em] uppercase">Latency</div>
                <div class="relative">
                    <TickLabels ticks={ticks()} span={live.span()} />
                </div>
            </div>
            <div class={cx(COLUMNS, 'min-h-0 flex-1')}>
                <div class="relative border-r">
                    <For each={axisLabels()}>
                        {label => (
                            <span
                                class={cx(
                                    'absolute right-2 -translate-y-1/2 font-mono text-2xs font-medium whitespace-nowrap',
                                    label.failed ? 'text-failed' : 'text-muted',
                                )}
                                style={{ top: `${label.y}px` }}
                            >
                                {label.text}
                            </span>
                        )}
                    </For>
                    <span
                        class="absolute right-2 text-2xs font-semibold tracking-[.05em] text-failed uppercase"
                        style={{ top: `${scatterBaseline(size().height) + 3}px` }}
                    >
                        Failed
                    </span>
                </div>
                <div
                    ref={track}
                    onPointerDown={props.hold.press}
                    onPointerLeave={event => {
                        if (event.pointerType === 'mouse') setHover(null);
                    }}
                    onPointerMove={event => setHover(pointAt(event))}
                    onClick={inspect}
                    class={cx('relative overflow-hidden', hover() && 'cursor-pointer')}
                >
                    <TickGuides ticks={ticks()} span={live.span()} />
                    <canvas
                        ref={canvas}
                        role="img"
                        aria-label={`Latency scatter of ${props.marks.length} invocations in the last ${spanLabel(live.span())}, failures below the time axis`}
                        class="absolute inset-0 size-full"
                    />
                    <Show when={hover()}>
                        {current => (
                            <div
                                aria-hidden="true"
                                class="pointer-events-none absolute z-2 flex w-max max-w-72 flex-col gap-0.5 rounded-md border border-line-strong bg-surface px-2.5 py-1.5 text-xs shadow-lift"
                                style={{
                                    left: `${current().x + (current().x > size().width - 260 ? -12 : 12)}px`,
                                    top: `${current().y + (current().y > size().height - 110 ? -12 : 12)}px`,
                                    transform: `translate(${current().x > size().width - 260 ? '-100%' : '0'}, ${current().y > size().height - 110 ? '-100%' : '0'})`,
                                }}
                            >
                                <Show when={hover()?.message}>
                                    {message => (
                                        <>
                                            <span class="flex items-center gap-1.5 font-semibold text-fg">
                                                <Glyph kind={message().kind} />
                                                <span class="truncate">{shortName(message().messageType)}</span>
                                            </span>
                                            <span class="text-muted">
                                                <span class={STATUS_TEXT[message().outcome.status]}>{STATUS_LABEL[message().outcome.status]}</span> ·{' '}
                                                {duration(message().outcome.durationMillis)} · {clock(message().startedAtMillis)}
                                            </span>
                                            <span class="truncate text-muted">
                                                <Show when={app.scope() === null && app.system().applications.length > 1}>{message().application} · </Show>
                                                {threadLabel(message().threadName)}
                                            </span>
                                            <Show when={failureTypeOf(message())}>
                                                {failure => <span class="truncate font-semibold text-failed">{shortName(failure())}</span>}
                                            </Show>
                                        </>
                                    )}
                                </Show>
                            </div>
                        )}
                    </Show>
                    <Show when={props.marks.length === 0}>
                        <Show when={!props.loading} fallback={<Loading title="Loading invocations" class="pointer-events-none absolute inset-x-0 top-1/4" />}>
                            <Empty
                                title={`Nothing in the last ${spanLabel(live.span())}`}
                                onClear={live.active() ? live.clear : undefined}
                                class="absolute inset-x-0 top-1/4"
                            >
                                Invocations appear here as they happen.
                            </Empty>
                        </Show>
                    </Show>
                </div>
            </div>
        </div>
    );
}
