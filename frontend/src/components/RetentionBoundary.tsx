import { Show } from 'solid-js';
import { completeSince } from '../lib/dashboard';
import { clock } from '../lib/format';
import type { ApplicationState } from '../lib/state';

// Where the retained invocations stop being complete: the hub dropped or shed some of the applications' invocations, so it retains every one
// that started since then, and only failed and slowest ones before.
export function RetentionBoundary(props: { applications: readonly ApplicationState[]; class?: string }) {
    const since = () => completeSince(props.applications);
    return (
        <Show when={since()}>
            {at => (
                <p
                    class={props.class ?? 'text-xs text-warning'}
                    title="Under load or memory pressure the hub keeps every recent invocation, and of older ones the failed and the slowest per type. Raise smd.monitor.hub.memory to keep more."
                >
                    Complete since {clock(at(), false)}; failures and slowest only before
                </p>
            )}
        </Show>
    );
}
