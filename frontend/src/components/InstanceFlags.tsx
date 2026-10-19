import { Show } from 'solid-js';
import { duration } from '../lib/format';
import { lagging } from '../lib/instances';

const FLAG = 'flex-none rounded-[5px] border border-warning/50 px-1.5 py-px text-xs font-medium text-warning';

// What an instance's spans tell of its clock and its exporter: a clock ahead of the hub's, whose spans the hub refuses, and an export lag beyond
// the settle, whose spans still change seconds the page already showed.
export function InstanceFlags(props: { clockAhead: boolean; exportLagMillis: number | null; settleSeconds: number }) {
    return (
        <>
            <Show when={props.clockAhead}>
                <span class={FLAG} title="The hub refused spans of this instance that started more than 30 seconds after the hub's clock in the last minute">
                    clock ahead
                </span>
            </Show>
            <Show when={lagging(props.exportLagMillis, props.settleSeconds)}>
                <span
                    class={FLAG}
                    title={`Its spans reach the hub ${duration(props.exportLagMillis)} after they end (p99), later than the ${props.settleSeconds} s the window waits, so they revise settled seconds`}
                >
                    export lag {duration(props.exportLagMillis)}
                </span>
            </Show>
        </>
    );
}
