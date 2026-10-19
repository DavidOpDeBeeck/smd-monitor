import type { ApplicationHealth } from '../lib/applications';
import type { DispatchStatus, MessageKind } from '../lib/types';

export const KIND_TONE: Record<
    MessageKind,
    {
        bg: string;
        fill: string;
        ink: string;
        chip: string;
        cardBar: string;
        cardBorder: string;
        spark: string;
        sparkArea: string;
        bar: string;
    }
> = {
    COMMAND: {
        bg: 'bg-command',
        fill: 'fill-command',
        ink: 'text-command-ink',
        chip: 'bg-command-soft text-command-ink',
        cardBar: 'before:bg-command',
        cardBorder: 'border-command/30 hover:border-command/60 focus-visible:border-command/60',
        spark: 'stroke-command',
        sparkArea: 'fill-command/14',
        bar: 'bg-command/70',
    },
    EVENT: {
        bg: 'bg-event',
        fill: 'fill-event',
        ink: 'text-event-ink',
        chip: 'bg-event-soft text-event-ink',
        cardBar: 'before:bg-event',
        cardBorder: 'border-event/30 hover:border-event/60 focus-visible:border-event/60',
        spark: 'stroke-event',
        sparkArea: 'fill-event/14',
        bar: 'bg-event/70',
    },
    QUERY: {
        bg: 'bg-query',
        fill: 'fill-query',
        ink: 'text-query-ink',
        chip: 'bg-query-soft text-query-ink',
        cardBar: 'before:bg-query',
        cardBorder: 'border-query/30 hover:border-query/60 focus-visible:border-query/60',
        spark: 'stroke-query',
        sparkArea: 'fill-query/14',
        bar: 'bg-query/70',
    },
};

export const HANDLING_TONE = {
    bg: 'bg-event/35 shadow-[inset_0_0_0_1px_var(--color-event)]',
    chip: 'border border-event/45 text-event-ink',
};

// The border tells how a group receives its events: solid while they are published, dashed on worker threads, dotted from the event store.
export const DELIVERY_TONE: Record<string, string> = {
    sync: 'border-solid',
    'async-await': 'border-dashed',
    'async-fire-and-forget': 'border-dashed',
    'event-store': 'border-dotted',
};

export const deliveryTone = (delivery: string) => DELIVERY_TONE[delivery] ?? 'border-solid';

// An event's line straight into a processing group tells how the group receives it: solid on the publishing thread, dashed on a worker
// thread the publication waits for, and dotted on a worker thread it does not wait for.
export const DELIVERY_STROKE: Record<string, string> = {
    'async-await': '[stroke-dasharray:7_4]',
    'async-fire-and-forget': '[stroke-dasharray:0.1_4.5] [stroke-linecap:round]',
};

export const deliveryStroke = (delivery: string) => DELIVERY_STROKE[delivery] ?? '';

// Red marks failures only: an instance that stopped reporting may simply have shut down.
export const REPORTING_TONE = {
    reporting: { dot: 'bg-query', text: 'text-muted' },
    silent: { dot: 'shadow-[inset_0_0_0_1.5px_var(--color-faint)]', text: 'text-faint' },
};

export const reportingTone = (silent: boolean) => REPORTING_TONE[silent ? 'silent' : 'reporting'];

export const HEALTH_TONE: Record<ApplicationHealth, { dot: string; text: string }> = {
    reporting: REPORTING_TONE.reporting,
    partial: { dot: 'bg-warning', text: 'text-warning' },
    silent: REPORTING_TONE.silent,
};

export const STATUS_TEXT: Record<DispatchStatus, string> = {
    COMPLETED: 'text-muted',
    FAILED: 'font-semibold text-failed',
};
