import type { NeighbourApplication } from './scope';

export const NODE_HEIGHT = 36;
export const NODE_GAP = 8;

// Where a link meets its neighbour's row, from the top of the diagram, and how thick it is drawn.
export interface NeighbourLink {
    name: string;
    y: number;
    width: number;
}

export interface Neighbourhood {
    height: number;
    centre: number;
    upstream: NeighbourLink[];
    downstream: NeighbourLink[];
}

const MIN_WIDTH = 1.5;
const MAX_WIDTH = 6;

// The neighbours in rows on either side of the application in scope, each side centred on the taller one, with a row kept for an empty side.
// A link is as thick as the square root of its share of the busiest link, so a quiet one stays visible beside a busy one.
export function neighbourhood(upstream: readonly NeighbourApplication[], downstream: readonly NeighbourApplication[]): Neighbourhood {
    const columnHeight = (rows: number) => rows * NODE_HEIGHT + (rows - 1) * NODE_GAP;
    const height = columnHeight(Math.max(upstream.length, downstream.length, 1));
    const busiest = Math.max(1, ...upstream.map(each => each.invocations), ...downstream.map(each => each.invocations));
    const links = (neighbours: readonly NeighbourApplication[]) => {
        const top = (height - columnHeight(neighbours.length)) / 2;
        return neighbours.map((neighbour, index) => ({
            name: neighbour.name,
            y: top + index * (NODE_HEIGHT + NODE_GAP) + NODE_HEIGHT / 2,
            width: MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * Math.sqrt(neighbour.invocations / busiest),
        }));
    };
    return { height, centre: height / 2, upstream: links(upstream), downstream: links(downstream) };
}
