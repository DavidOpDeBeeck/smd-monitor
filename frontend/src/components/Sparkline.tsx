import { createMemo, Show } from 'solid-js';
import { cx } from '../lib/cx';
import { sparklinePoints } from '../lib/format';
import { provisionalSeconds, scrollable } from '../lib/throughput';
import { ProvisionalShade } from './ProvisionalShade';
import { animateSeries, createSeriesTween } from './scrolling';

// A series of seconds ending at endsAt. The seconds from provisional-from on, which may still grow as their spans arrive late, are shaded.

export function Sparkline(props: {
    values: readonly number[];
    endsAt: number | undefined;
    width: number;
    height: number;
    scale?: number;
    provisionalFrom?: number | null;
    class?: string;
    lineClass: string;
    areaClass?: string;
}) {
    let scroller!: SVGGElement;
    let scaler!: SVGGElement;
    const values = createSeriesTween(
        () => scrollable(props.values),
        () => props.endsAt,
    );
    const scale = () => props.scale ?? Math.max(...props.values);
    const points = createMemo(() => sparklinePoints(values(), props.width, scale()));
    // The last value sits on the right edge, so the shade starts half a step before the first provisional value.
    const shadeFrom = () => {
        const seconds = provisionalSeconds(props.endsAt, props.provisionalFrom, props.values.length);
        return seconds ? Math.max(0, props.width - (seconds - 0.5) * points().step) : props.width;
    };
    animateSeries({
        scroller: () => scroller,
        scaler: () => scaler,
        endsAt: () => props.endsAt,
        step: () => points().step,
        max: () => points().max,
        bottom: () => props.height - 1,
        extent: () => props.height - 2,
    });
    return (
        <svg
            class={props.class}
            viewBox={`0 0 ${props.width} ${props.height}`}
            width={props.width}
            height={props.height}
            preserveAspectRatio="none"
            aria-hidden="true"
        >
            <ProvisionalShade from={shadeFrom()} width={props.width} height={props.height} />
            <g ref={scroller}>
                <g ref={scaler}>
                    <Show when={props.areaClass}>{areaClass => <polygon points={points().area} class={areaClass()} />}</Show>
                    <polyline points={points().line} class={cx('fill-none [vector-effect:non-scaling-stroke]', props.lineClass)} />
                </g>
            </g>
        </svg>
    );
}
