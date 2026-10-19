import { createMemo, createSignal, Index, Show } from 'solid-js';
import { Glyph } from '../../components/Glyph';
import { ProvisionalShade } from '../../components/ProvisionalShade';
import { animateSeries, createSeriesTween } from '../../components/scrolling';
import { Tweened } from '../../components/tween';
import { KIND_TONE } from '../../components/tones';
import { cx } from '../../lib/cx';
import type { ActivityRow } from '../../lib/dashboard';
import { clock, count, historySpan, lastPeriod, perSecond, shortHistorySpan, wholeCount } from '../../lib/format';
import { provisionalSeconds, SECOND } from '../../lib/throughput';
import type { MessageKind } from '../../lib/types';

const WIDTH = 1000;
const HEIGHT = 40;
const LABEL_WIDTH = 150;
const SEPARATED = 'stroke-surface stroke-1 [vector-effect:non-scaling-stroke]';
const MIN_SEPARATED_STEP = 2;
const LABEL_ROOM = 0.15;
// Room the "now" label keeps at the end of the axis.
const NOW_WIDTH = '2rem';

function RowGlyph(props: { kind: MessageKind | null }) {
    return (
        <Show when={props.kind} fallback={<i aria-hidden="true" class="inline-block size-[9px] flex-none rounded-[2px] bg-failed" />}>
            {kind => <Glyph kind={kind()} />}
        </Show>
    );
}

function SeriesBars(props: {
    values: readonly number[];
    endsAt: number;
    windowSeconds: number;
    provisionalFrom: number | null;
    kind: MessageKind | null;
    hovered: number | null;
}) {
    let scroller!: SVGGElement;
    let scaler!: SVGGElement;
    const step = () => WIDTH / Math.max(1, props.values.length - 1);
    const max = createMemo(() => Math.max(1, ...props.values));
    const values = createSeriesTween(
        () => props.values,
        () => props.endsAt,
    );
    const windowFrom = () => props.values.length - props.windowSeconds;
    // A gap between bars would cover narrower bars entirely.
    const separated = () => step() >= MIN_SEPARATED_STEP;
    const fill = () => (props.kind ? KIND_TONE[props.kind].fill : 'fill-failed');
    const shadeFrom = () => WIDTH - provisionalSeconds(props.endsAt, props.provisionalFrom, props.values.length - 1) * step();
    animateSeries({
        scroller: () => scroller,
        scaler: () => scaler,
        endsAt: () => props.endsAt,
        step,
        max,
        bottom: () => HEIGHT,
        extent: () => HEIGHT - 3,
    });
    return (
        <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" aria-hidden="true" class="block h-10 w-full overflow-hidden">
            <g ref={scroller}>
                <Show when={props.hovered}>{index => <rect x={(index() - 1) * step()} y="0" width={step()} height={HEIGHT} class="fill-fg/10" />}</Show>
                <g ref={scaler}>
                    <Index each={values()}>
                        {(value, index) => (
                            <rect
                                x={(index - 1) * step()}
                                width={step()}
                                y="0"
                                height={value()}
                                class={cx(fill(), separated() && SEPARATED, index < windowFrom() && 'opacity-35')}
                            />
                        )}
                    </Index>
                </g>
            </g>
            <ProvisionalShade from={shadeFrom()} width={WIDTH} height={HEIGHT} />
            <line x1="0" x2={WIDTH} y1={HEIGHT - 0.5} y2={HEIGHT - 0.5} class="stroke-line-strong stroke-1 [vector-effect:non-scaling-stroke]" />
        </svg>
    );
}

// The seconds from provisional-from on are shaded: they may still grow, as spans of them arrive that late.
export function ThroughputChart(props: { rows: readonly ActivityRow[]; endsAt: number; windowSeconds: number; provisionalFrom: number | null }) {
    let axis!: HTMLDivElement;
    const [hovered, setHovered] = createSignal<number | null>(null);
    const length = () => props.rows[0]?.values.length ?? 0;
    const clamp = (index: number) => Math.min(length() - 1, Math.max(1, index));
    const hover = (event: PointerEvent) => {
        const bounds = axis.getBoundingClientRect();
        const fraction = (event.clientX - bounds.left) / bounds.width;
        setHovered(fraction < 0 || fraction > 1 ? null : clamp(Math.floor(fraction * (length() - 1)) + 1));
    };
    const keydown = (event: KeyboardEvent) => {
        if (event.key === 'Escape') {
            setHovered(null);
            return;
        }
        const current = hovered() ?? length() - 1;
        const next = ({ ArrowLeft: current - 1, ArrowRight: current + 1, Home: 1, End: length() - 1 } as Record<string, number>)[event.key];
        if (next === undefined) return;
        event.preventDefault();
        setHovered(clamp(next));
    };
    const hoveredTime = (index: number) => props.endsAt - (length() - index) * SECOND;
    const position = (index: number) => (index - 0.5) / (length() - 1);
    const windowAt = () => (length() - 1 - props.windowSeconds) / (length() - 1);
    // Too close to now for its label to fit after it, so the label ends at it instead.
    const besideNow = () => windowAt() > 1 - LABEL_ROOM;
    const rate = (values: readonly number[]) => values.slice(-props.windowSeconds).reduce((sum, value) => sum + value, 0) / Math.max(1, props.windowSeconds);

    return (
        <div
            tabIndex={0}
            role="group"
            aria-label={`Per-second activity over the last ${historySpan(length() - 1)}. Use the arrow keys to read one second.`}
            onKeyDown={keydown}
            onBlur={() => setHovered(null)}
            class="relative rounded-md focus-visible:outline-offset-4"
        >
            <div class="grid gap-y-1.5" style={{ 'grid-template-columns': `${LABEL_WIDTH}px minmax(0, 1fr)`, 'column-gap': '12px' }}>
                <Index each={props.rows}>
                    {row => (
                        <>
                            <div class="flex min-w-0 flex-col justify-center">
                                <span class="flex items-center gap-1.5 truncate text-sm font-medium">
                                    <RowGlyph kind={row().kind} />
                                    {row().label}
                                </span>
                                <span
                                    class="font-mono text-xs whitespace-nowrap text-muted tabular-nums"
                                    title={`Average per second over the ${lastPeriod(props.windowSeconds)}; peak is the busiest second shown, the top of this row's scale`}
                                >
                                    <Tweened value={rate(row().values)} format={value => `${perSecond(value)}/s`} />
                                    <span class="ml-1.5 text-faint">
                                        peak <Tweened value={Math.max(0, ...row().values)} format={wholeCount} />
                                    </span>
                                </span>
                            </div>
                            <div onPointerMove={hover} onPointerLeave={() => setHovered(null)}>
                                <SeriesBars
                                    values={row().values}
                                    endsAt={props.endsAt}
                                    windowSeconds={props.windowSeconds}
                                    provisionalFrom={props.provisionalFrom}
                                    kind={row().kind}
                                    hovered={hovered()}
                                />
                            </div>
                        </>
                    )}
                </Index>
                <div />
                <div ref={axis} class="relative h-4 font-mono text-2xs text-faint" aria-hidden="true">
                    <span class="absolute left-0">{shortHistorySpan(length() - 1)} ago</span>
                    <Show when={props.windowSeconds < length() - 1}>
                        <span class="absolute inset-y-0 border-l border-line-strong" style={{ left: `${windowAt() * 100}%` }} />
                        <span
                            class={cx('absolute whitespace-nowrap text-muted', besideNow() ? 'pr-1' : 'pl-1')}
                            style={besideNow() ? { right: `max(${(1 - windowAt()) * 100}%, ${NOW_WIDTH})` } : { left: `${windowAt() * 100}%` }}
                        >
                            {lastPeriod(props.windowSeconds)}
                        </span>
                    </Show>
                    <span class="absolute right-0">now</span>
                </div>
            </div>
            <Show when={hovered()}>
                {index => (
                    <div
                        role="status"
                        class="pointer-events-none absolute top-full z-10 mt-1 grid min-w-[180px] -translate-x-1/2 gap-0.5 rounded-md border bg-surface px-2.5 py-2 text-sm shadow-lift"
                        style={{ left: `calc(${LABEL_WIDTH + 12}px + (100% - ${LABEL_WIDTH + 12}px) * ${Math.min(0.9, Math.max(0.1, position(index())))})` }}
                    >
                        <span class="mb-0.5 font-mono text-xs text-muted">
                            {clock(hoveredTime(index()), false)}
                            <Show when={props.provisionalFrom !== null && hoveredTime(index()) >= props.provisionalFrom}>
                                <span class="ml-1.5 font-sans text-warning">may still grow</span>
                            </Show>
                        </span>
                        <Index each={props.rows}>
                            {row => (
                                <span class="flex items-center gap-2">
                                    <RowGlyph kind={row().kind} />
                                    <span class="flex-1">{row().label}</span>
                                    <span class="font-mono">{count(row().values[index()])}</span>
                                </span>
                            )}
                        </Index>
                    </div>
                )}
            </Show>
        </div>
    );
}
