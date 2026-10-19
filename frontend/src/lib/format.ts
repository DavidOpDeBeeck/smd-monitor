import type { Activity, DispatchStatus, GroupKey, HandlingKey, MessageKind, TypeKey } from './types';

export const KINDS: readonly MessageKind[] = ['COMMAND', 'QUERY', 'EVENT'];
export const perKind = <T>(value: (kind: MessageKind) => T) => Object.fromEntries(KINDS.map(kind => [kind, value(kind)])) as Record<MessageKind, T>;
export const STATUSES: readonly DispatchStatus[] = ['COMPLETED', 'FAILED'];
export const KIND_LABEL: Record<MessageKind, string> = { COMMAND: 'Command', QUERY: 'Query', EVENT: 'Event' };
export const STATUS_LABEL: Record<DispatchStatus, string> = { COMPLETED: 'Completed', FAILED: 'Failed' };

export const shortName = (name: string) => name.split(/[.$]/).pop() ?? name;

// An application's name as a title: its words, capitalised, instead of dashes, underscores, and dots.
export const applicationTitle = (name: string) =>
    name
        .split(/[-_.\s]+/)
        .filter(word => word.length)
        .map(word => word[0].toUpperCase() + word.slice(1))
        .join(' ');

export const activityName = (message: Activity) => message.processingGroup ?? shortName(message.messageType);
export const typeKey = (application: string, kind: MessageKind, messageType: string): TypeKey => `${application}::${kind}:${messageType}`;
export const handlingKey = (type: TypeKey, processingGroup: string): HandlingKey => `${type}@${processingGroup}`;
// A failure cause as the hub names it: the type, the exception type, and the stage, a dispatch or the handling of its processing group.
export const failureKey = (type: TypeKey, handling: boolean, processingGroup: string | null, failureType: string) =>
    `${type}|${failureType}|${handling ? `handling:${processingGroup}` : 'dispatch'}`;
export const groupKey = (application: string, processingGroup: string): GroupKey => `${application}::GROUP:${processingGroup}`;
export const threadLabel = (name: string | null) => name || 'virtual thread';

// The deliveries SMD names; an application's own source names its own or is custom. Only the event bus delivers within the publication.
const DELIVERY: Record<string, { label: string; title: string; inProcess: boolean }> = {
    sync: { label: 'sync', title: 'Handles each event on the publishing thread while it is published', inProcess: true },
    'async-await': { label: 'async await', title: 'Handles each event on a worker thread while the publication waits for it', inProcess: true },
    'async-fire-and-forget': {
        label: 'fire and forget',
        title: 'Handles each event on a worker thread without the publication waiting for it',
        inProcess: true,
    },
    'event-store': { label: 'event store', title: 'Handles each event after polling it from the event store', inProcess: false },
};

export const deliveryLabel = (delivery: string) => DELIVERY[delivery]?.label ?? delivery;
export const deliveryTitle = (delivery: string) => DELIVERY[delivery]?.title ?? `Receives its events from the application's own source, ${delivery}`;
export const isInProcessDelivery = (delivery: string) => DELIVERY[delivery]?.inProcess ?? false;

// A medium is drawn as the event store's database, or as a pipe for any other source an application reads events from.
export type MediumShape = 'store' | 'pipe';
export const mediumShape = (delivery: string): MediumShape => (delivery === 'event-store' ? 'store' : 'pipe');

// The application ends at the first "::" followed by a kind. A message type is a class name and holds no '@', so a handling's processing group
// follows the first '@' after it.
const KEY_PREFIX = /^(.+?)::(COMMAND|QUERY|EVENT):/;

export function isTypeKey(value: string): value is TypeKey {
    const prefix = KEY_PREFIX.exec(value);
    return prefix !== null && value.length > prefix[0].length && !value.includes('@', prefix[0].length);
}

const GROUP_KEY = /^(.+?)::GROUP:./;

export const isGroupKey = (value: string): value is GroupKey => GROUP_KEY.test(value);

export function parseGroupKey(key: GroupKey): { application: string; processingGroup: string } {
    const application = GROUP_KEY.exec(key)![1];
    return { application, processingGroup: key.slice(`${application}::GROUP:`.length) };
}

export function parseTypeKey(key: TypeKey): { application: string; kind: MessageKind; messageType: string } {
    const prefix = KEY_PREFIX.exec(key)!;
    return { application: prefix[1], kind: prefix[2] as MessageKind, messageType: key.slice(prefix[0].length) };
}

export function parseHandlingKey(key: HandlingKey): { typeKey: TypeKey; processingGroup: string } {
    const separator = key.indexOf('@', KEY_PREFIX.exec(key)![0].length);
    return { typeKey: key.slice(0, separator) as TypeKey, processingGroup: key.slice(separator + 1) };
}

export function duration(millis: number | null | undefined): string {
    if (millis === null || millis === undefined || Number.isNaN(millis)) return '—';
    if (millis < 1) return `${Math.max(0, millis * 1000).toFixed(0)} µs`;
    if (millis < 10) return `${millis.toFixed(2)} ms`;
    if (millis < 1000) return `${millis.toFixed(millis < 100 ? 1 : 0)} ms`;
    if (millis < 60_000) return `${(millis / 1000).toFixed(millis < 10_000 ? 2 : 1)} s`;
    return `${Math.floor(millis / 60_000)}m ${Math.round((millis % 60_000) / 1000)}s`;
}

// A round duration such as an axis tick or a threshold: 200 µs, 1 ms, 2.5 s.
export function roundDuration(millis: number): string {
    if (millis < 1) return `${Math.round(millis * 1000)} µs`;
    if (millis < 1000) return `${Number(millis.toPrecision(3))} ms`;
    return `${Number((millis / 1000).toPrecision(3))} s`;
}

export function clock(millis: number, precise = true): string {
    const date = new Date(millis);
    const pad = (value: number, size = 2) => String(value).padStart(size, '0');
    const base = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
    return precise ? `${base}.${pad(date.getMilliseconds(), 3)}` : base;
}

export function calendarDay(millis: number, now: number, locale?: string): string | null {
    const date = new Date(millis);
    const today = new Date(now);
    if (date.toDateString() === today.toDateString()) return null;
    const year = date.getFullYear() === today.getFullYear() ? undefined : 'numeric';
    return date.toLocaleDateString(locale, { day: 'numeric', month: 'short', year });
}

export function ago(millis: number): string {
    const seconds = Math.max(0, millis / 1000);
    if (seconds < 1) return 'now';
    if (seconds < 60) return `${Math.floor(seconds)}s ago`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
    return `${Math.floor(seconds / 3600)}h ago`;
}

// A reporting instance sent a span within the hub's silent-after limit, so counting its seconds would only flicker as spans arrive.
export const lastSpanAgo = (millis: number, silent: boolean) => (silent ? ago(millis) : 'now');

export function count(value: number): string {
    if (value < 10_000) return value.toLocaleString();
    if (value < 1_000_000) return `${(value / 1000).toFixed(value < 100_000 ? 1 : 0)}k`;
    return `${(value / 1_000_000).toFixed(1)}M`;
}

export function perSecond(value: number): string {
    if (!value) return '0';
    if (value < 0.1) return value.toFixed(2);
    return value < 10 ? value.toFixed(1) : Math.round(value).toLocaleString();
}

export function rate(perSecond: number): string {
    if (!perSecond) return '0/s';
    if (perSecond < 1) return `${(perSecond * 60).toFixed(perSecond * 60 < 10 ? 1 : 0)}/min`;
    return `${perSecond < 10 ? perSecond.toFixed(1) : Math.round(perSecond)}/s`;
}

export const wholeCount = (value: number) => count(Math.round(value));

// Binary units, as the JVM sizes its heap: 512 MB is -Xmx512m.
export function bytes(value: number): string {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let unit = 0;
    let scaled = Math.max(0, value);
    while (scaled >= 1024 && unit < units.length - 1) {
        scaled /= 1024;
        unit++;
    }
    const digits = unit === 0 || scaled >= 100 ? 0 : scaled >= 10 ? 1 : 2;
    return `${scaled.toFixed(digits)} ${units[unit]}`;
}

export const share = (value: number) => `${(value * 100).toFixed(value < 0.1 ? 1 : 0)}%`;

export const plural = (amount: number, noun: string) => (amount === 1 ? noun : `${noun}s`);

// How far back the hub keeps per-second counts or covers its live figures, as in "the last 2 minutes" or "last 90 s".
export function historySpan(seconds: number): string {
    if (seconds % 3600 === 0) return `${seconds / 3600} ${plural(seconds / 3600, 'hour')}`;
    return seconds % 60 === 0 ? `${seconds / 60} ${plural(seconds / 60, 'minute')}` : `${seconds} seconds`;
}

export function shortHistorySpan(seconds: number): string {
    if (seconds % 3600 === 0) return `${seconds / 3600} h`;
    return seconds % 60 === 0 ? `${seconds / 60} min` : `${seconds} s`;
}

// The window of the live figures, as in "last minute" or "last 30 seconds".
export function lastPeriod(seconds: number): string {
    if (seconds === 60) return 'last minute';
    if (seconds === 3600) return 'last hour';
    return `last ${historySpan(seconds)}`;
}

export const capitalized = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

export function percent(part: number, total: number): string {
    if (!total) return '0%';
    const value = (part / total) * 100;
    return `${value < 1 && value > 0 ? value.toFixed(1) : Math.round(value)}%`;
}

// Leaves out the reds, which mark failures.
export function hashHue(text: string): number {
    let hash = 0;
    for (let index = 0; index < text.length; index++) hash = (hash * 31 + text.charCodeAt(index)) | 0;
    return 45 + (Math.abs(hash) % 275);
}

export function sparklinePoints(values: readonly number[], width: number, scale?: number): { line: string; area: string; max: number; step: number } {
    const max = Math.max(1, scale ?? 0, ...values);
    const step = width / Math.max(1, values.length - 2);
    const points = values.map((value, index) => `${((index - 1) * step).toFixed(1)},${value.toFixed(3)}`).join(' ');
    return { line: points, area: `${(-step).toFixed(1)},${-max} ${points} ${width},${-max}`, max, step };
}
