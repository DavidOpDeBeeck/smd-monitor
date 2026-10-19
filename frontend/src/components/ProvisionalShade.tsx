import { Show } from 'solid-js';

// Shades the last seconds of a chart that may still grow, from the left edge of the first of them to the right edge of the chart.
export function ProvisionalShade(props: { from: number; width: number; height: number }) {
    return (
        <Show when={props.from < props.width}>
            <rect x={props.from} y="0" width={props.width - props.from} height={props.height} class="fill-warning/12 transition-[x,width] motion-clock" />
            <line
                x1={props.from}
                x2={props.from}
                y1="0"
                y2={props.height}
                class="stroke-warning/60 stroke-1 transition-[x1,x2] motion-clock [stroke-dasharray:2_2] [vector-effect:non-scaling-stroke]"
            />
        </Show>
    );
}
