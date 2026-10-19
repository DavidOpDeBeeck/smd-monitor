import type { FrameMessage, JsonValue, MonitorFrame } from './wire';

// The page's copy of the hub's latest frame (W2, W3): a snapshot replaces it, and a delta applies the changes from the frame of its base epoch.
// The series of a frame are the lists whose field name ends in "PerSecond"; every one ends at the frame's end second, so a delta first moves
// every series by its shift, the new seconds zero, and then applies its changes: a value, or an array such as positional statistics, replaces
// a value; an object changes the fields it names; {"$": value} replaces a value whole; {"$s": [[index, value], ...]} sets seconds of a series;
// and {"$k": {...}} changes a list of records by their previous positions, removing "r", changing "c", appending "a", and reordering by "o". A
// frame never changes once mirrored; applying a delta builds a new one.
export interface Mirror {
    epoch: number;
    frame: MonitorFrame;
}

// The mirror after the message, or null when a delta's base is not the mirrored epoch: the page then needs a snapshot.
export function receive(mirror: Mirror | null, message: FrameMessage): Mirror | null {
    if (message.type === 'snapshot') return { epoch: message.epoch, frame: message.frame };
    if (mirror === null || mirror.epoch !== message.base) return null;
    return { epoch: message.epoch, frame: applyChanges(shifted(mirror.frame, message.shift), message.changes) as MonitorFrame };
}

type JsonObject = { [field: string]: JsonValue };

const SERIES = 'PerSecond';

function shifted(value: unknown, shift: number): unknown {
    if (Array.isArray(value)) return value.map(element => shifted(element, shift));
    if (value !== null && typeof value === 'object') {
        const result: Record<string, unknown> = {};
        for (const [field, element] of Object.entries(value)) {
            result[field] = field.endsWith(SERIES) && Array.isArray(element) ? moved(element as number[], shift) : shifted(element, shift);
        }
        return result;
    }
    return value;
}

function moved(series: readonly number[], shift: number): number[] {
    return series.map((_, index) => (index + shift < series.length ? series[index + shift] : 0));
}

function applyChanges(previous: unknown, change: JsonValue): unknown {
    if (change === null || typeof change !== 'object' || Array.isArray(change)) return change;
    const fields = Object.keys(change);
    if (fields.length === 1 && fields[0] === '$') return change.$;
    if (fields.length === 1 && fields[0] === '$s') {
        const series = [...(previous as number[])];
        for (const [index, value] of change.$s as [number, number][]) series[index] = value;
        return series;
    }
    if (fields.length === 1 && fields[0] === '$k') return applyKeyed(previous as unknown[], change.$k as JsonObject);
    const result: Record<string, unknown> = { ...(previous as Record<string, unknown>) };
    for (const field of fields) result[field] = applyChanges(result[field], change[field]);
    return result;
}

function applyKeyed(previous: unknown[], change: JsonObject): unknown[] {
    const removed = new Set((change.r as number[] | undefined) ?? []);
    const changed = (change.c as JsonObject | undefined) ?? {};
    const records: unknown[] = [];
    previous.forEach((record, position) => {
        if (removed.has(position)) return;
        const recordChange = changed[String(position)];
        records.push(recordChange === undefined ? record : applyChanges(record, recordChange));
    });
    records.push(...((change.a as unknown[] | undefined) ?? []));
    const order = change.o as number[] | undefined;
    return order ? order.map(position => records[position]) : records;
}
