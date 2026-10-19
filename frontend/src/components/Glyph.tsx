import { Show } from 'solid-js';
import { cx } from '../lib/cx';
import type { MediumShape } from '../lib/format';
import type { Activity, MessageKind } from '../lib/types';

const SHAPE: Record<MessageKind, string> = {
    COMMAND: 'rotate-45 scale-[.86] rounded-[1.5px] bg-command',
    EVENT: 'rounded-[2px] bg-event',
    QUERY: 'rounded-full bg-query',
};

export function Glyph(props: { kind: MessageKind; class?: string }) {
    return <i aria-hidden="true" class={cx('inline-block size-[9px] flex-none align-[-0.5px]', SHAPE[props.kind], props.class)} />;
}

export function ActivityGlyph(props: { message: Pick<Activity, 'kind' | 'handling'> }) {
    return (
        <Show when={props.message.handling} fallback={<Glyph kind={props.message.kind} />}>
            <HandlingGlyph />
        </Show>
    );
}

// A medium events wait in until a processing group reads them: a database for the event store, a pipe for any other source.
export function MediumGlyph(props: { shape: MediumShape; class?: string }) {
    return (
        <svg aria-hidden="true" viewBox="0 0 12 11" class={cx('inline-block h-[11px] w-3 flex-none fill-none stroke-event stroke-[1.4]', props.class)}>
            <Show
                when={props.shape === 'store'}
                fallback={
                    <>
                        <path d="M3,1.5 H9.5 M3,9.5 H9.5 M3,1.5 C1.6,1.5 1,3.5 1,5.5 C1,7.5 1.6,9.5 3,9.5" />
                        <ellipse cx="9.5" cy="5.5" rx="1.75" ry="4" />
                    </>
                }
            >
                <ellipse cx="6" cy="2.5" rx="4.5" ry="1.75" />
                <path d="M1.5,2.5 V8.5 C1.5,9.5 3.5,10.25 6,10.25 C8.5,10.25 10.5,9.5 10.5,8.5 V2.5" />
            </Show>
        </svg>
    );
}

// A processing group's handling is the hollow shape of its event; only events have handlings.
export function HandlingGlyph() {
    return <i aria-hidden="true" class="inline-block size-[9px] flex-none rounded-[2px] border-[1.5px] border-event align-[-0.5px]" />;
}
