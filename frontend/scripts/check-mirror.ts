// Replays recorded frame messages through mirror.ts and checks that the mirror equals the frame the hub snapshotted at every epoch, and that a
// delta whose base the mirror lacks asks for a snapshot. Run by the hub's tests: node scripts/check-mirror.ts <recording.json>.
import { readFileSync } from 'node:fs';
import { receive, type Mirror } from '../src/lib/mirror.ts';
import type { FrameMessage, MonitorFrame } from '../src/lib/wire.ts';

interface Recording {
    messages: FrameMessage[];
    // The snapshot of every message's epoch.
    frames: MonitorFrame[];
}

const recording = JSON.parse(readFileSync(process.argv[2], 'utf8')) as Recording;
let mirror: Mirror | null = null;
recording.messages.forEach((message, index) => {
    mirror = receive(mirror, message);
    if (mirror === null) throw new Error(`Message ${index} of epoch ${message.epoch} found no base`);
    const difference = differs('', mirror.frame, recording.frames[index]);
    if (difference !== null) throw new Error(`The mirror of epoch ${message.epoch} differs from its snapshot at ${difference}`);
});
const last = recording.messages.at(-1);
if (last?.type === 'delta' && receive({ epoch: last.base - 1, frame: recording.frames[0] }, last) !== null) {
    throw new Error('A delta whose base the mirror lacks was applied');
}
console.log(`Mirrored ${recording.messages.length} messages`);

// The path of the first difference, or null when equal.
function differs(path: string, actual: unknown, expected: unknown): string | null {
    if (Array.isArray(expected)) {
        if (!Array.isArray(actual) || actual.length !== expected.length) return path;
        for (let index = 0; index < expected.length; index++) {
            const difference = differs(`${path}/${index}`, actual[index], expected[index]);
            if (difference !== null) return difference;
        }
        return null;
    }
    if (expected !== null && typeof expected === 'object') {
        if (actual === null || typeof actual !== 'object' || Array.isArray(actual)) return path;
        const fields = new Set([...Object.keys(actual), ...Object.keys(expected)]);
        for (const field of fields) {
            const difference = differs(`${path}/${field}`, (actual as Record<string, unknown>)[field], (expected as Record<string, unknown>)[field]);
            if (difference !== null) return difference;
        }
        return null;
    }
    return actual === expected ? null : path;
}
