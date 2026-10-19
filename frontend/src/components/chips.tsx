import type { JSX } from 'solid-js';
import { cx } from '../lib/cx';
import { KIND_LABEL, deliveryLabel, deliveryTitle, hashHue, threadLabel } from '../lib/format';
import type { DispatchStatus, MessageKind } from '../lib/types';
import { Glyph } from './Glyph';
import { deliveryTone, HANDLING_TONE, KIND_TONE } from './tones';

const PILL = 'inline-flex items-center gap-1.5 rounded-full px-[9px] py-0.5 text-xs font-bold tracking-[.04em] whitespace-nowrap uppercase';

export function KindChip(props: { kind: MessageKind }) {
    return (
        <span class={cx(PILL, KIND_TONE[props.kind].chip)}>
            <Glyph kind={props.kind} />
            {KIND_LABEL[props.kind]}
        </span>
    );
}

const STATUS_TONE: Record<DispatchStatus, string> = {
    FAILED: 'bg-failed-soft text-failed',
    COMPLETED: 'bg-sunken text-muted',
};

export function StatusChip(props: { status: DispatchStatus; children: JSX.Element }) {
    return <span class={cx(PILL, STATUS_TONE[props.status])}>{props.children}</span>;
}

export function AsyncChip(props: { title?: string }) {
    return (
        <span
            title={props.title}
            class="inline-block rounded-[5px] border border-dashed border-accent px-1.5 font-mono text-2xs leading-4 font-bold text-accent uppercase"
        >
            async
        </span>
    );
}

export function GroupChip(props: { name: string; class?: string }) {
    return (
        <span
            title={`Processing group ${props.name}`}
            class={cx(
                'inline-block max-w-[170px] overflow-hidden rounded-[5px] px-1.5 align-middle font-mono text-xs leading-4 font-medium text-ellipsis whitespace-nowrap',
                HANDLING_TONE.chip,
                props.class,
            )}
        >
            {props.name}
        </span>
    );
}

export function DeliveryChip(props: { delivery: string; class?: string }) {
    return (
        <span
            title={deliveryTitle(props.delivery)}
            class={cx(
                'inline-block max-w-[130px] overflow-hidden rounded-[5px] border border-line-strong px-1.5 align-middle font-mono text-2xs leading-4 font-bold text-ellipsis whitespace-nowrap text-muted uppercase',
                deliveryTone(props.delivery),
                props.class,
            )}
        >
            {deliveryLabel(props.delivery)}
        </span>
    );
}

export function InstanceChip(props: { name: string; class?: string }) {
    return (
        <span
            title={`Instance ${props.name}`}
            class={cx(
                'inline-block max-w-[150px] overflow-hidden rounded-[5px] border border-line-strong px-1.5 align-middle font-mono text-xs leading-4 font-medium text-ellipsis whitespace-nowrap text-muted',
                props.class,
            )}
        >
            {props.name}
        </span>
    );
}

export function ThreadChip(props: { name: string; class?: string }) {
    return (
        <span
            title={`Thread ${threadLabel(props.name)}`}
            style={{ '--hue': `${hashHue(props.name)}` }}
            class={cx(
                'inline-block max-w-[170px] overflow-hidden rounded-[5px] px-[7px] py-px align-middle font-mono text-xs font-medium text-ellipsis whitespace-nowrap hue-chip',
                props.class,
            )}
        >
            {threadLabel(props.name)}
        </span>
    );
}

// An application's name beside what ran in it; a crossing, marked, is where a trace entered the application from another one.
export function ApplicationChip(props: { name: string; crossing?: boolean; title?: string; class?: string }) {
    return (
        <span
            title={props.title ?? `Application ${props.name}`}
            class={cx(
                'inline-block max-w-[130px] overflow-hidden rounded-[5px] px-1.5 align-middle text-xs leading-4 font-semibold text-ellipsis whitespace-nowrap',
                props.crossing ? 'text-accent shadow-[inset_0_0_0_1px_currentColor]' : 'bg-sunken text-muted',
                props.class,
            )}
        >
            {props.name}
        </span>
    );
}

// A value in a colour of its own, the same wherever it shows: full where it differs from its parent's or has no parent to compare with, faded where it matches.
// The hue hashes from the value unless the caller assigns one, as it does for applications.
export function HueBadge(props: { value: string; hue?: number; parent: string | undefined; label: string; title: string; class?: string }) {
    return (
        <span
            title={props.title}
            style={{ '--hue': `${props.hue ?? hashHue(props.value)}` }}
            class={cx('min-w-0 truncate rounded-[5px] px-1.5 py-px font-medium hue-chip', props.parent === props.value && 'opacity-40', props.class)}
        >
            {props.label}
        </span>
    );
}
