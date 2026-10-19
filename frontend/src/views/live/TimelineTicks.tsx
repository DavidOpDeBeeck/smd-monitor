import { Index } from 'solid-js';
import type { TimelineTick } from '../../lib/timeline';

const right = (offset: number, span: number) => `${(offset / span) * 100}%`;

export function TickLabels(props: { ticks: TimelineTick[]; span: number }) {
    return (
        <Index each={props.ticks}>
            {tick => (
                <span
                    class="absolute top-2 border-r border-line-strong pr-1 font-mono text-2xs font-medium whitespace-nowrap"
                    style={{ right: right(tick().offset, props.span) }}
                >
                    {tick().label}
                </span>
            )}
        </Index>
    );
}

export function TickGuides(props: { ticks: TimelineTick[]; span: number }) {
    return (
        <Index each={props.ticks}>
            {tick => <span class="pointer-events-none absolute inset-y-0 border-r border-dashed" style={{ right: right(tick().offset, props.span) }} />}
        </Index>
    );
}
