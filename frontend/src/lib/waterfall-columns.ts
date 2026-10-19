import { BAR_LABEL_GUTTER } from './traces';

export type WaterfallColumn = 'application' | 'instance' | 'thread' | 'start' | 'wait' | 'duration';

export interface WaterfallColumnSpec {
    key: WaterfallColumn;
    label: string;
    title: string;
    width: number;
    numeric: boolean;
}

export const WATERFALL_COLUMNS: readonly WaterfallColumnSpec[] = [
    { key: 'application', label: 'Application', title: 'The application it ran in; faded where it matches its parent', width: 120, numeric: false },
    { key: 'instance', label: 'Instance', title: 'The instance it ran on; faded where it matches its parent', width: 112, numeric: false },
    { key: 'thread', label: 'Thread', title: 'The thread it ran on; faded where it matches its parent', width: 168, numeric: false },
    { key: 'start', label: 'Start', title: 'When it started, from the start of the trace', width: 80, numeric: true },
    { key: 'wait', label: 'Wait', title: 'How long after its parent returned it started: the dotted line on the timeline', width: 80, numeric: true },
    { key: 'duration', label: 'Duration', title: 'How long it ran', width: 96, numeric: true },
];

const SPECS = new Map(WATERFALL_COLUMNS.map(spec => [spec.key, spec]));

export const MESSAGE_WIDTH = 240;
const MIN_MESSAGE_WIDTH = 160;
const MIN_COLUMN_WIDTH = 56;
const MAX_WIDTH = 640;
const MIN_TRACK = 200;

export interface WaterfallTableColumn {
    key: WaterfallColumn;
    width: number;
}

// Every column in its order; the message column comes first and the timeline last.
export interface WaterfallTable {
    messageWidth: number;
    columns: readonly WaterfallTableColumn[];
}

export interface TableColumn extends WaterfallColumnSpec {
    width: number;
}

export const specOf = (key: WaterfallColumn) => SPECS.get(key)!;

export function defaultWaterfallTable(): WaterfallTable {
    return {
        messageWidth: MESSAGE_WIDTH,
        columns: WATERFALL_COLUMNS.map(spec => ({ key: spec.key, width: spec.width })),
    };
}

const clampWidth = (width: number, min: number) => Math.round(Math.min(MAX_WIDTH, Math.max(min, width)));

// A stored table from an older version keeps what it knows: unknown columns drop out and new ones join at the end.
export function parseWaterfallTable(stored: string | null): WaterfallTable {
    const fallback = defaultWaterfallTable();
    if (stored === null) return fallback;
    try {
        const parsed = JSON.parse(stored) as Partial<WaterfallTable>;
        const columns: WaterfallTableColumn[] = [];
        for (const column of Array.isArray(parsed.columns) ? parsed.columns : []) {
            const spec = SPECS.get(column?.key);
            if (!spec || columns.some(known => known.key === spec.key)) continue;
            columns.push({ key: spec.key, width: clampWidth(Number(column.width) || spec.width, MIN_COLUMN_WIDTH) });
        }
        for (const spec of WATERFALL_COLUMNS) {
            if (!columns.some(column => column.key === spec.key)) columns.push({ key: spec.key, width: spec.width });
        }
        return { messageWidth: clampWidth(Number(parsed.messageWidth) || MESSAGE_WIDTH, MIN_MESSAGE_WIDTH), columns };
    } catch {
        return fallback;
    }
}

export const tableColumns = (table: WaterfallTable): TableColumn[] => table.columns.map(column => ({ ...specOf(column.key), width: column.width }));

// Moves a column in front of another, or to the end when there is none.
export function moveColumn(table: WaterfallTable, key: WaterfallColumn, before: WaterfallColumn | null): WaterfallTable {
    if (key === before) return table;
    const moved = table.columns.find(column => column.key === key)!;
    const rest = table.columns.filter(column => column.key !== key);
    const index = before === null ? rest.length : rest.findIndex(column => column.key === before);
    return { ...table, columns: [...rest.slice(0, index), moved, ...rest.slice(index)] };
}

export function resizeColumn(table: WaterfallTable, key: WaterfallColumn | 'message', width: number): WaterfallTable {
    if (key === 'message') return { ...table, messageWidth: clampWidth(width, MIN_MESSAGE_WIDTH) };
    return { ...table, columns: table.columns.map(column => (column.key === key ? { ...column, width: clampWidth(width, MIN_COLUMN_WIDTH) } : column)) };
}

export function waterfallLayout(table: WaterfallTable) {
    const widths = [table.messageWidth, ...table.columns.map(column => column.width)];
    const fixed = widths.reduce((sum, width) => sum + width, 0);
    return {
        template: [...widths.map(width => `${width}px`), 'minmax(0, 1fr)'].join(' '),
        trackLeft: `${fixed}px`,
        minWidth: `${fixed + MIN_TRACK + BAR_LABEL_GUTTER}px`,
    };
}
