import { lensApplication, type Lens, type LensKey, type LensStation } from './flow-lens';
import { isGroupKey } from './format';
import { roundedPath, type Point } from './paths';

export const CARD_WIDTH = 248;
export const MESSAGE_HEIGHT = 50;
export const GROUP_WIDTH = 248;
export const GROUP_HEIGHT = 30;
export const STATION_WIDTH = 34;
export const STATION_HEIGHT = 16;
export const JOURNEY_HEAD = 34;
const COLUMN_GAP = 72;
// A gap an event enters a medium in is wider, so the station and its rate fit between the columns.
const STATION_GAP = 104;
const ROW_GAP = 14;
const LANE_PADDING = 18;
const LANE_HEIGHT = 80;
const MEDIUM_LANE_HEIGHT = 46;
// Room for an entry point's mark and a card's counts of steps it does not draw.
const LEFT = 56;
const RIGHT = 64;

export interface JourneyLane {
    id: string;
    // An application's lane, or a medium's.
    application: string | null;
    medium: string | null;
    top: number;
    height: number;
}

export interface JourneyCard {
    key: LensKey;
    lane: string;
    x: number;
    y: number;
    width: number;
    height: number;
}

export interface JourneyStation {
    station: LensStation;
    x: number;
    y: number;
}

// A drawn path: a step between two cards, an event into its medium, or a medium out to a group that read the event there. Its ends are
// card keys or station IDs, and it counts the step or the send it draws.
export interface JourneyWire {
    id: string;
    from: string;
    to: string;
    d: string;
    counts: { type: 'edge'; id: string } | { type: 'send'; id: string };
    delivery: string | null;
}

export interface JourneyColumnHead {
    label: string;
    x: number;
    selected: boolean;
}

export interface Journey {
    lanes: readonly JourneyLane[];
    cards: readonly JourneyCard[];
    positions: ReadonlyMap<LensKey, JourneyCard>;
    stations: readonly JourneyStation[];
    wires: readonly JourneyWire[];
    heads: readonly JourneyColumnHead[];
    width: number;
    height: number;
}

const cardWidth = (key: LensKey) => (isGroupKey(key) ? GROUP_WIDTH : CARD_WIDTH);
const cardHeight = (key: LensKey) => (isGroupKey(key) ? GROUP_HEIGHT : MESSAGE_HEIGHT);
const appLane = (application: string) => `application:${application}`;
const mediumLane = (medium: string) => `medium:${medium}`;

// The lens on a board of lanes: one for each application it touches, in the order its flow reaches them, and one for each medium an event of
// it enters, below the first application writing to it. Each card stands in the column the lens gives it; cards of one lane and column stack
// in the order of the cards they come from, so the arrows cross as little as one pass can arrange.
export function layoutJourney(lens: Lens): Journey {
    const nodes = [...lens.nodes.values()];
    const applications = new Map<string, { minColumn: number; first: number }>();
    for (const node of nodes) {
        const application = lensApplication(node.key);
        const known = applications.get(application);
        applications.set(application, {
            minColumn: Math.min(known?.minColumn ?? Infinity, node.column),
            first: Math.min(known?.first ?? Infinity, node.order),
        });
    }
    const appOrder = [...applications].sort(([, a], [, b]) => a.minColumn - b.minColumn || a.first - b.first).map(([application]) => application);
    const media = new Map<string, number>();
    for (const station of lens.stations) {
        const publisher = appOrder.indexOf(lensApplication(station.event));
        media.set(station.medium, Math.min(media.get(station.medium) ?? Infinity, publisher));
    }
    const laneOrder: { id: string; application: string | null; medium: string | null }[] = [];
    appOrder.forEach((application, index) => {
        laneOrder.push({ id: appLane(application), application, medium: null });
        for (const [medium] of [...media].filter(([, publisher]) => publisher === index).sort(([a], [b]) => a.localeCompare(b))) {
            laneOrder.push({ id: mediumLane(medium), application: null, medium });
        }
    });

    const columns = nodes.map(node => node.column);
    const minColumn = Math.min(...columns);
    const maxColumn = Math.max(...columns);
    const stationAfter = new Set(lens.stations.map(station => Math.floor(station.column)));
    // A column is as wide as its widest card, so a column of processing groups alone is narrower.
    const columnWidth = new Map<number, number>();
    for (const node of nodes) columnWidth.set(node.column, Math.max(columnWidth.get(node.column) ?? 0, cardWidth(node.key)));
    const widthOf = (column: number) => columnWidth.get(column) ?? CARD_WIDTH;
    const columnX = new Map<number, number>();
    let x = LEFT;
    for (let column = minColumn; column <= maxColumn; column++) {
        columnX.set(column, x);
        x += widthOf(column) + (stationAfter.has(column) ? STATION_GAP : COLUMN_GAP);
    }
    const gapCenter = (column: number) => columnX.get(column)! + widthOf(column) + (stationAfter.has(column) ? STATION_GAP : COLUMN_GAP) / 2;
    const width = columnX.get(maxColumn)! + widthOf(maxColumn) + (stationAfter.has(maxColumn) ? STATION_GAP : 0) + RIGHT;

    const cells = new Map<string, LensKey[]>();
    const cellOf = (lane: string, column: number) => `${lane}|${column}`;
    for (const node of nodes) {
        const cell = cellOf(appLane(lensApplication(node.key)), node.column);
        cells.set(cell, [...(cells.get(cell) ?? []), node.key]);
    }
    const stationCells = new Map<string, LensStation[]>();
    for (const station of lens.stations) {
        const cell = cellOf(mediumLane(station.medium), station.column);
        stationCells.set(cell, [...(stationCells.get(cell) ?? []), station]);
    }
    const stackHeight = (heights: readonly number[]) => heights.reduce((sum, height) => sum + height, 0) + ROW_GAP * Math.max(0, heights.length - 1);
    const lanes: JourneyLane[] = [];
    let top = JOURNEY_HEAD;
    for (const lane of laneOrder) {
        const stacks =
            lane.medium === null
                ? [...cells].filter(([cell]) => cell.startsWith(`${lane.id}|`)).map(([, keys]) => stackHeight(keys.map(cardHeight)))
                : [...stationCells].filter(([cell]) => cell.startsWith(`${lane.id}|`)).map(([, stations]) => stackHeight(stations.map(() => STATION_HEIGHT)));
        const height = Math.max(lane.medium === null ? LANE_HEIGHT : MEDIUM_LANE_HEIGHT, Math.max(0, ...stacks) + LANE_PADDING * 2);
        lanes.push({ ...lane, top, height });
        top += height;
    }
    const laneById = new Map(lanes.map(lane => [lane.id, lane]));

    // Columns left to right, so every card's parents stand placed before it.
    const positions = new Map<LensKey, JourneyCard>();
    const center = (key: LensKey) => {
        const card = positions.get(key);
        return card ? card.y + card.height / 2 : null;
    };
    const parentsOf = new Map<LensKey, LensKey[]>();
    for (const edge of lens.edges) parentsOf.set(edge.child, [...(parentsOf.get(edge.child) ?? []), edge.parent]);
    const pull = (key: LensKey) => {
        const centers = (parentsOf.get(key) ?? []).flatMap(parent => center(parent) ?? []);
        return centers.length ? centers.reduce((sum, value) => sum + value, 0) / centers.length : null;
    };
    for (let column = minColumn; column <= maxColumn; column++) {
        for (const lane of lanes) {
            const keys = cells.get(cellOf(lane.id, column));
            if (!keys) continue;
            const ordered = [...keys].sort((a, b) => (pull(a) ?? Infinity) - (pull(b) ?? Infinity) || lens.nodes.get(a)!.order - lens.nodes.get(b)!.order);
            let y = lane.top + (lane.height - stackHeight(ordered.map(cardHeight))) / 2;
            for (const key of ordered) {
                positions.set(key, { key, lane: lane.id, x: columnX.get(column)!, y, width: cardWidth(key), height: cardHeight(key) });
                y += cardHeight(key) + ROW_GAP;
            }
        }
    }

    const stations: JourneyStation[] = [];
    for (const [cell, placed] of stationCells) {
        const lane = laneById.get(cell.slice(0, cell.lastIndexOf('|')))!;
        const ordered = [...placed].sort((a, b) => (center(a.event) ?? 0) - (center(b.event) ?? 0));
        let y = lane.top + (lane.height - stackHeight(ordered.map(() => STATION_HEIGHT))) / 2;
        for (const station of ordered) {
            stations.push({ station, x: gapCenter(Math.floor(station.column)) - STATION_WIDTH / 2, y });
            y += STATION_HEIGHT + ROW_GAP;
        }
    }

    const wires: JourneyWire[] = [];
    const right = (card: JourneyCard): Point => ({ x: card.x + card.width, y: card.y + card.height / 2 });
    const left = (card: JourneyCard): Point => ({ x: card.x, y: card.y + card.height / 2 });
    for (const edge of lens.edges) {
        if (edge.medium !== null) continue;
        const from = right(positions.get(edge.parent)!);
        const to = left(positions.get(edge.child)!);
        // Beside a station rather than through it, so a step that skips the medium does not look like it enters it.
        const column = lens.nodes.get(edge.parent)!.column;
        const trunk = Math.min(stationAfter.has(column) ? from.x + 22 : gapCenter(column), to.x - 12);
        wires.push({
            id: edge.id,
            from: edge.parent,
            to: edge.child,
            d: roundedPath([from, { x: trunk, y: from.y }, { x: trunk, y: to.y }, to]),
            counts: { type: 'edge', id: edge.id },
            delivery: edge.delivery,
        });
    }
    // An event drops into its station on the medium's lane, and each group that read it there rises or drops out of it.
    for (const { station, x: stationX, y: stationY } of stations) {
        const event = positions.get(station.event)!;
        const from = right(event);
        const middle = stationX + STATION_WIDTH / 2;
        const below = stationY > from.y;
        wires.push({
            id: station.id,
            from: station.event,
            to: station.id,
            d: roundedPath([from, { x: middle, y: from.y }, { x: middle, y: below ? stationY : stationY + STATION_HEIGHT }]),
            counts: { type: 'send', id: station.id },
            delivery: null,
        });
        for (const edge of station.readers) {
            const to = left(positions.get(edge.child)!);
            const out = to.y > stationY + STATION_HEIGHT / 2 ? stationY + STATION_HEIGHT : stationY;
            wires.push({
                id: edge.id,
                from: station.id,
                to: edge.child,
                d: roundedPath([{ x: middle, y: out }, { x: middle, y: to.y }, to]),
                counts: { type: 'edge', id: edge.id },
                delivery: null,
            });
        }
    }

    const heads: JourneyColumnHead[] = [];
    if (minColumn < 0) heads.push({ label: 'Caused by', x: columnX.get(minColumn)!, selected: false });
    heads.push({ label: 'Selected', x: columnX.get(0)!, selected: true });
    if (maxColumn > 0) heads.push({ label: 'Leads to', x: columnX.get(1)!, selected: false });

    const cards = [...positions.values()];
    return { lanes, cards, positions, stations, wires, heads, width, height: top + 24 };
}

// The cards, stations, and wires on a path through the card or station: everything it came from and everything it led to.
export function journeyPath(journey: Journey, through: string): { ends: Set<string>; wires: Set<string> } {
    const ends = new Set<string>([through]);
    const wires = new Set<string>();
    for (const forward of [true, false]) {
        const pending = [through];
        while (pending.length) {
            const end = pending.pop()!;
            for (const wire of journey.wires) {
                if ((forward ? wire.from : wire.to) !== end) continue;
                wires.add(wire.id);
                const next = forward ? wire.to : wire.from;
                if (ends.has(next)) continue;
                ends.add(next);
                pending.push(next);
            }
        }
    }
    return { ends, wires };
}
