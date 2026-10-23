import { groupKey, isInProcessDelivery, isTypeKey, parseGroupKey, parseTypeKey, shortName } from './format';
import { NO_EDGES, type ApplicationState, type FlowEdges } from './state';
import type { GroupKey, Link, TypeKey } from './types';

// A card of a flow: a message type, or a processing group between the events it handles and the messages it dispatches.
export type LensKey = TypeKey | GroupKey;

// How far the lens reaches up- and downstream of its focus; "all" follows the flow to its ends.
export type LensHops = 1 | 2 | 3 | 'all';
export const LENS_HOPS: readonly LensHops[] = [1, 2, 3, 'all'];

// A step of a flow, counted over the history whether or not the hub still retains its invocations.
export interface LensEdge {
    id: string;
    parent: LensKey;
    child: LensKey;
    invocations: number;
    lastSecond: number;
    // Dispatching a message is a hop; handing an event to a processing group is not, as the group dispatches nothing of its own yet.
    hop: boolean;
    // The medium a processing group read the event from, named by the group's delivery, or null.
    medium: string | null;
    // How the event bus handed the event to the group within its publication, or null.
    delivery: string | null;
}

// The events of one type an application sent to a medium: what its sinks of that delivery sent, or else what the readers read.
export interface LensSend {
    id: string;
    event: TypeKey;
    medium: string;
    invocations: number;
    lastSecond: number;
}

export interface LensGraph {
    keys: ReadonlySet<LensKey>;
    edges: ReadonlyMap<string, LensEdge>;
    down: ReadonlyMap<LensKey, readonly LensEdge[]>;
    up: ReadonlyMap<LensKey, readonly LensEdge[]>;
    sends: ReadonlyMap<string, LensSend>;
    // Changes when a card, an arrow, or a medium appears, not when the counts move.
    signature: string;
}

export type LensSource = Pick<ApplicationState, 'name' | 'types' | 'groups' | 'crossings'>;

export const lensApplication = (key: LensKey) => (isTypeKey(key) ? parseTypeKey(key).application : parseGroupKey(key).application);
export const lensName = (key: LensKey) => (isTypeKey(key) ? shortName(parseTypeKey(key).messageType) : parseGroupKey(key).processingGroup);
export const edgeId = (parent: LensKey, child: LensKey) => `${parent}>${child}`;
export const sendId = (event: TypeKey, medium: string) => `${event}|${medium}`;

const push = <K, V>(map: Map<K, V[]>, key: K, value: V) => {
    const values = map.get(key);
    if (values) values.push(value);
    else map.set(key, [value]);
};

// Every application's types and processing groups with the steps between them, within and across applications. An event's effects run
// through the groups that handled it, so the event's own links within its application are left to the groups' links; a crossing is a message
// another application dispatched again or handed on, and is kept whatever its parent. A group that reads its events from elsewhere names the
// medium by its delivery, so applications naming the same delivery share one medium.
export function lensGraph(applications: readonly LensSource[], flows: ReadonlyMap<string, FlowEdges>): LensGraph {
    const keys = new Set<LensKey>();
    const deliveries = new Map<GroupKey, string>();
    for (const application of applications) {
        for (const key of application.types.keys()) keys.add(key);
        for (const group of application.groups) {
            const key = groupKey(application.name, group.processingGroup);
            keys.add(key);
            deliveries.set(key, group.delivery);
        }
    }

    const edges = new Map<string, LensEdge>();
    const add = (edge: Omit<LensEdge, 'id'>) => {
        keys.add(edge.parent);
        keys.add(edge.child);
        const id = edgeId(edge.parent, edge.child);
        edges.set(id, { id, ...edge });
    };
    const dispatched = (link: Link) =>
        add({ parent: link.parent, child: link.child, invocations: link.invocations, lastSecond: link.lastSecond, hop: true, medium: null, delivery: null });
    const sends = new Map<string, LensSend>();
    for (const application of applications) {
        const flow = flows.get(application.name) ?? NO_EDGES;
        for (const link of flow.links) if (parseTypeKey(link.parent).kind !== 'EVENT') dispatched(link);
        for (const link of application.crossings) dispatched(link);
        for (const route of flow.routes) {
            const delivery = deliveries.get(route.group) ?? null;
            const medium = delivery !== null && !isInProcessDelivery(delivery) ? delivery : null;
            add({
                parent: route.event,
                child: route.group,
                invocations: route.invocations,
                lastSecond: route.lastSecond,
                hop: false,
                medium,
                delivery: medium === null ? delivery : null,
            });
        }
        for (const link of flow.groupLinks) {
            add({ parent: link.group, child: link.child, invocations: link.invocations, lastSecond: link.lastSecond, hop: true, medium: null, delivery: null });
        }
        for (const route of flow.sinkRoutes) {
            if (isInProcessDelivery(route.delivery)) continue;
            const id = sendId(route.event, route.delivery);
            sends.set(id, { id, event: route.event, medium: route.delivery, invocations: route.invocations, lastSecond: route.lastSecond });
        }
    }
    // A reader can read what no sink reported sending, such as beyond the hub's caps; the medium then counts what its busiest reader read.
    const reported = new Set(sends.keys());
    for (const edge of edges.values()) {
        if (edge.medium === null) continue;
        const event = edge.parent as TypeKey;
        const id = sendId(event, edge.medium);
        const known = sends.get(id);
        if (reported.has(id) || (known && known.invocations >= edge.invocations)) continue;
        sends.set(id, { id, event, medium: edge.medium, invocations: edge.invocations, lastSecond: edge.lastSecond });
    }

    const down = new Map<LensKey, LensEdge[]>();
    const up = new Map<LensKey, LensEdge[]>();
    for (const edge of edges.values()) {
        push(down, edge.parent, edge);
        push(up, edge.child, edge);
    }
    return {
        keys,
        edges,
        down,
        up,
        sends,
        signature: [[...keys].sort().join(' '), [...edges.keys()].sort().join(' '), [...sends.keys()].sort().join(' ')].join('\n'),
    };
}

export type LensSide = 'up' | 'focus' | 'down';

export interface LensNode {
    key: LensKey;
    // Steps from the focus: negative upstream, positive downstream, each card at least one step after every card it is drawn from.
    column: number;
    side: LensSide;
    hops: number;
    // In the order the lens found it, nearest first.
    order: number;
}

// An event entering a medium, drawn half a step after the event, with the drawn steps of the groups that read it there.
export interface LensStation {
    id: string;
    event: TypeKey;
    medium: string;
    column: number;
    readers: readonly LensEdge[];
}

export interface Lens {
    focus: LensKey;
    nodes: ReadonlyMap<LensKey, LensNode>;
    // The steps drawn, each into a later column than its parent's.
    edges: readonly LensEdge[];
    stations: readonly LensStation[];
    // The steps of a card that are not drawn: beyond the hops, or back to a card at an earlier column.
    hiddenIn: ReadonlyMap<LensKey, readonly LensEdge[]>;
    hiddenOut: ReadonlyMap<LensKey, readonly LensEdge[]>;
    // The cards within the hops beyond the most the lens draws.
    truncated: number;
}

export const LENS_LIMIT = 400;

// The cards within the hops of the start in one direction, by the fewest hops, nearest first.
function reach(graph: LensGraph, start: LensKey, along: 'down' | 'up', hops: number): Map<LensKey, number> {
    const adjacent = along === 'down' ? graph.down : graph.up;
    const reached = new Map<LensKey, number>([[start, 0]]);
    const queue: LensKey[] = [start];
    // Steps into a processing group cost nothing, so the search takes them first: a breadth-first search with zero-cost steps at the front.
    while (queue.length) {
        const key = queue.shift()!;
        const distance = reached.get(key)!;
        for (const edge of adjacent.get(key) ?? []) {
            const next = along === 'down' ? edge.child : edge.parent;
            const cost = edge.hop ? 1 : 0;
            const known = reached.get(next);
            if (distance + cost > hops || (known !== undefined && known <= distance + cost)) continue;
            reached.set(next, distance + cost);
            if (cost) queue.push(next);
            else queue.unshift(next);
        }
    }
    return reached;
}

// The longest number of steps from the start to each card of one side, along steps that do not close a loop; a loop's closing step is left out.
function columns(graph: LensGraph, start: LensKey, along: 'down' | 'up', side: ReadonlySet<LensKey>): Map<LensKey, number> {
    const adjacent = along === 'down' ? graph.down : graph.up;
    const next = (edge: LensEdge) => (along === 'down' ? edge.child : edge.parent);
    const finished: LensKey[] = [];
    const state = new Map<LensKey, 'open' | 'done'>();
    const forward = new Map<LensKey, LensKey[]>();
    const visit = (key: LensKey) => {
        state.set(key, 'open');
        for (const edge of adjacent.get(key) ?? []) {
            const target = next(edge);
            if (!side.has(target) || state.get(target) === 'open') continue;
            push(forward, key, target);
            if (!state.has(target)) visit(target);
        }
        state.set(key, 'done');
        finished.push(key);
    };
    visit(start);
    const depth = new Map<LensKey, number>([[start, 0]]);
    for (const key of finished.reverse()) {
        for (const target of forward.get(key) ?? []) depth.set(target, Math.max(depth.get(target) ?? 0, depth.get(key)! + 1));
    }
    return depth;
}

// The focus with what caused it and what it led to within the hops, every card in a column of its own lane.
export function lens(graph: LensGraph, focus: LensKey, hops: LensHops, limit = LENS_LIMIT): Lens {
    const most = hops === 'all' ? Infinity : hops;
    const downstream = reach(graph, focus, 'down', most);
    const upstream = reach(graph, focus, 'up', most);
    const found = new Map<LensKey, { side: LensSide; hops: number; order: number }>();
    let order = 0;
    found.set(focus, { side: 'focus', hops: 0, order: order++ });
    for (const [key, distance] of downstream) if (key !== focus) found.set(key, { side: 'down', hops: distance, order: order++ });
    for (const [key, distance] of upstream) {
        const known = found.get(key);
        if (key === focus || (known && known.hops <= distance)) continue;
        found.set(key, { side: 'up', hops: distance, order: known?.order ?? order++ });
    }
    const kept = [...found].sort(([, a], [, b]) => a.hops - b.hops || a.order - b.order).slice(0, limit);
    const included = new Map(kept);

    const downSide = new Set([...included].filter(([, node]) => node.side !== 'up').map(([key]) => key));
    const upSide = new Set([...included].filter(([, node]) => node.side !== 'down').map(([key]) => key));
    const downColumns = columns(graph, focus, 'down', downSide);
    const upColumns = columns(graph, focus, 'up', upSide);
    const nodes = new Map<LensKey, LensNode>();
    for (const [key, node] of included) {
        const column = node.side === 'focus' ? 0 : node.side === 'down' ? (downColumns.get(key) ?? 1) : -(upColumns.get(key) ?? 1);
        nodes.set(key, { key, column, side: node.side, hops: node.hops, order: node.order });
    }

    const edges: LensEdge[] = [];
    const hiddenIn = new Map<LensKey, LensEdge[]>();
    const hiddenOut = new Map<LensKey, LensEdge[]>();
    for (const key of nodes.keys()) {
        for (const edge of graph.down.get(key) ?? []) {
            const child = nodes.get(edge.child);
            if (child && child.column > nodes.get(key)!.column) edges.push(edge);
            else push(hiddenOut, key, edge);
        }
        for (const edge of graph.up.get(key) ?? []) {
            const parent = nodes.get(edge.parent);
            if (!parent || parent.column >= nodes.get(key)!.column) push(hiddenIn, key, edge);
        }
    }

    // A station for each medium a drawn event entered: with the groups drawn reading it, or alone when no group read it at all.
    const stations = new Map<string, Omit<LensStation, 'readers'> & { readers: LensEdge[] }>();
    const station = (event: TypeKey, medium: string) => {
        const id = sendId(event, medium);
        let known = stations.get(id);
        if (!known) stations.set(id, (known = { id, event, medium, column: nodes.get(event)!.column + 0.5, readers: [] }));
        return known;
    };
    for (const edge of edges) if (edge.medium !== null) station(edge.parent as TypeKey, edge.medium).readers.push(edge);
    for (const send of graph.sends.values()) {
        const read = (graph.down.get(send.event) ?? []).some(edge => edge.medium === send.medium);
        if (!read && nodes.has(send.event)) station(send.event, send.medium);
    }

    return { focus, nodes, edges, stations: [...stations.values()], hiddenIn, hiddenOut, truncated: found.size - included.size };
}

// Every card in causal order: each after the cards it follows from, along steps that do not close a loop, and in the order a walk from the
// flows' starts in name order finds them.
export function causalOrder(graph: LensGraph): LensKey[] {
    const starts = [...graph.keys].filter(key => !(graph.up.get(key) ?? []).length).sort();
    const rest = [...graph.keys].sort();
    const found = new Map<LensKey, number>();
    const state = new Map<LensKey, 'open' | 'done'>();
    const finished: LensKey[] = [];
    const forward = new Map<LensKey, LensKey[]>();
    const visit = (key: LensKey) => {
        state.set(key, 'open');
        found.set(key, found.size);
        for (const edge of graph.down.get(key) ?? []) {
            if (state.get(edge.child) === 'open') continue;
            push(forward, key, edge.child);
            if (!state.has(edge.child)) visit(edge.child);
        }
        state.set(key, 'done');
        finished.push(key);
    };
    for (const key of [...starts, ...rest]) if (!state.has(key)) visit(key);
    const depth = new Map<LensKey, number>();
    for (const key of finished.reverse()) {
        const own = depth.get(key) ?? 0;
        depth.set(key, own);
        for (const target of forward.get(key) ?? []) depth.set(target, Math.max(depth.get(target) ?? 0, own + 1));
    }
    return [...graph.keys].sort((a, b) => depth.get(a)! - depth.get(b)! || found.get(a)! - found.get(b)!);
}
