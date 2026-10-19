import { createEffect, createSignal, on, onCleanup, Show, type Accessor } from 'solid-js';
import { duration } from '../lib/format';
import { easeFrames, prefersReducedMotion } from './motion';

function createTween(target: Accessor<number>): Accessor<number> {
    const [value, setValue] = createSignal(target());
    let cancel = () => {};
    createEffect(
        on(
            target,
            next => {
                cancel();
                const from = value();
                if (prefersReducedMotion() || from === next || !Number.isFinite(from) || !Number.isFinite(next)) {
                    setValue(next);
                    return;
                }
                cancel = easeFrames(eased => setValue(from + (next - from) * eased));
            },
            { defer: true },
        ),
    );
    onCleanup(() => cancel());
    return value;
}

export function Tweened(props: { value: number; format: (value: number) => string }) {
    const value = createTween(() => props.value);
    return <>{props.format(value())}</>;
}

export function TweenedDuration(props: { millis: number | null }) {
    return (
        <Show when={props.millis !== null} fallback="—">
            <Tweened value={props.millis!} format={duration} />
        </Show>
    );
}
