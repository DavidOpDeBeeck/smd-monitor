export interface Point {
    x: number;
    y: number;
}

const CORNER = 10;

// A path through the points with each corner rounded, leaving out repeated points and points on a straight run.
export function roundedPath(points: readonly Point[]): string {
    const distinct = points.filter((point, index) => index === 0 || Math.hypot(point.x - points[index - 1].x, point.y - points[index - 1].y) > 0.5);
    const clean = distinct.filter((point, index) => {
        if (index === 0 || index === distinct.length - 1) return true;
        const [previous, next] = [distinct[index - 1], distinct[index + 1]];
        return Math.abs((point.x - previous.x) * (next.y - point.y) - (point.y - previous.y) * (next.x - point.x)) > 0.5;
    });
    let d = `M${clean[0].x},${clean[0].y}`;
    for (let index = 1; index < clean.length - 1; index++) {
        const [previous, corner, next] = [clean[index - 1], clean[index], clean[index + 1]];
        const before = Math.hypot(corner.x - previous.x, corner.y - previous.y);
        const after = Math.hypot(next.x - corner.x, next.y - corner.y);
        const radius = Math.min(CORNER, before / 2, after / 2);
        const start = { x: corner.x + ((previous.x - corner.x) / before) * radius, y: corner.y + ((previous.y - corner.y) / before) * radius };
        const end = { x: corner.x + ((next.x - corner.x) / after) * radius, y: corner.y + ((next.y - corner.y) / after) * radius };
        d += ` L${start.x},${start.y} Q${corner.x},${corner.y} ${end.x},${end.y}`;
    }
    const last = clean.at(-1)!;
    return `${d} L${last.x},${last.y}`;
}
