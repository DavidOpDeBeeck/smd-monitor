import { createMemo, For, Index } from 'solid-js';
import { axisPosition, stepStop, stopAt, THRESHOLD_PRESETS, THRESHOLD_STOPS } from '../lib/duration-threshold';
import { cx } from '../lib/cx';
import { count, roundDuration } from '../lib/format';
import { ToggleChip } from './controls';
import { FIELD_LABEL } from './popover';

const AXIS_LABELS = ['10 µs', '1 ms', '100 ms', '10 s'];

// A minimum time over a histogram of the times it chooses from: drag or click the histogram, step it with the arrow keys, or pick a preset.
// Whoever opens it owns closing it; Escape and Enter bubble up to them.
export function ThresholdPanel(props: {
    label: string;
    value: number;
    buckets: readonly number[];
    kept: number;
    total: number;
    onChange: (millis: number) => void;
    track?: (element: HTMLDivElement) => void;
}) {
    let track!: HTMLDivElement;
    const position = () => axisPosition(props.value);
    const histogram = createMemo(() => {
        const max = Math.max(1, ...props.buckets);
        return props.buckets.map(value => (value ? Math.max(6, Math.sqrt(value / max) * 100) : 0));
    });
    const change = (millis: number) => {
        if (millis !== props.value) props.onChange(millis);
    };
    const fromPointer = (event: PointerEvent) => {
        const rect = track.getBoundingClientRect();
        change(stopAt((event.clientX - rect.left) / rect.width));
    };
    const keydown = (event: KeyboardEvent) => {
        const step: Record<string, () => number> = {
            ArrowRight: () => stepStop(props.value, 1),
            ArrowUp: () => stepStop(props.value, 1),
            ArrowLeft: () => stepStop(props.value, -1),
            ArrowDown: () => stepStop(props.value, -1),
            Home: () => THRESHOLD_STOPS[0],
            End: () => THRESHOLD_STOPS.at(-1)!,
        };
        if (!step[event.key]) return;
        event.preventDefault();
        change(step[event.key]());
    };

    return (
        <>
            <div class="flex items-baseline justify-between gap-3">
                <span class={FIELD_LABEL}>{props.label}</span>
                <span class="text-xs text-muted tabular-nums">
                    {count(props.kept)} of {count(props.total)} messages
                </span>
            </div>
            <div class={cx('mt-0.5 font-mono text-xl font-semibold', props.value ? 'text-fg' : 'text-muted')}>
                {props.value ? roundDuration(props.value) : 'Any duration'}
            </div>
            <div
                ref={element => {
                    track = element;
                    props.track?.(element);
                }}
                role="slider"
                tabIndex={0}
                aria-label={props.label}
                aria-valuemin={0}
                aria-valuemax={THRESHOLD_STOPS.at(-1)}
                aria-valuenow={props.value}
                aria-valuetext={props.value ? `${props.label} ${roundDuration(props.value)}` : 'Any duration'}
                onKeyDown={keydown}
                onPointerDown={event => {
                    track.setPointerCapture(event.pointerId);
                    fromPointer(event);
                }}
                onPointerMove={event => {
                    if (track.hasPointerCapture(event.pointerId)) fromPointer(event);
                }}
                class="group relative mt-3 h-16 cursor-ew-resize touch-none outline-none"
            >
                <div class="absolute inset-0 flex items-end gap-px">
                    <Index each={histogram()}>
                        {(height, index) => (
                            <span
                                class={cx(
                                    'flex-1 rounded-t-[2px] transition-[height,background-color] duration-200',
                                    (index + 0.5) / histogram().length >= position() ? 'bg-accent/70' : 'bg-line-strong',
                                )}
                                style={{ height: `${height()}%` }}
                            />
                        )}
                    </Index>
                </div>
                <div class="absolute inset-y-0 left-0 bg-surface/55" style={{ width: `${position() * 100}%` }} />
                <div class="pointer-events-none absolute -inset-y-1 w-0.5 -translate-x-1/2 bg-accent" style={{ left: `${position() * 100}%` }}>
                    <span class="absolute -top-1 left-1/2 size-2.5 -translate-x-1/2 rounded-full border-2 border-surface bg-accent shadow-card transition-shadow group-focus-visible:shadow-[0_0_0_4px_color-mix(in_srgb,var(--color-accent)_30%,transparent)]" />
                </div>
            </div>
            <div class="mt-1.5 flex justify-between font-mono text-2xs text-faint">
                <For each={AXIS_LABELS}>{label => <span>{label}</span>}</For>
            </div>
            <div class="mt-3 flex flex-wrap gap-1" role="group" aria-label="Presets">
                <For each={THRESHOLD_PRESETS}>
                    {preset => (
                        <ToggleChip pressed={props.value === preset} onToggle={() => change(preset)}>
                            {preset ? roundDuration(preset) : 'Any'}
                        </ToggleChip>
                    )}
                </For>
            </div>
        </>
    );
}
