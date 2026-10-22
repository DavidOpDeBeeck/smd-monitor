// Whether two values of an answer are equal: primitives by value, arrays and plain objects by their elements and fields.
export function sameData(a: unknown, b: unknown): boolean {
    if (a === b) return true;
    if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object' || Array.isArray(a) !== Array.isArray(b)) return false;
    if (Array.isArray(a)) {
        const other = b as unknown[];
        return a.length === other.length && a.every((value, index) => sameData(value, other[index]));
    }
    const fields = Object.keys(a);
    const other = b as Record<string, unknown>;
    return (
        fields.length === Object.keys(other).length && fields.every(field => field in other && sameData((a as Record<string, unknown>)[field], other[field]))
    );
}

// Whether two objects hold the very same values in the same fields.
export function sameFields(a: object, b: object): boolean {
    const fields = Object.keys(a);
    const other = b as Record<string, unknown>;
    return fields.length === Object.keys(other).length && fields.every(field => field in other && (a as Record<string, unknown>)[field] === other[field]);
}

// The next items, each replaced by the previous item of its key when the two are the same, so whatever renders an item sees it unchanged; the
// previous array itself when every item and their order stayed the same.
export function shareUnchanged<T>(previous: T[], next: T[], key: (item: T) => string | number, same: (a: T, b: T) => boolean = sameData): T[] {
    if (!previous.length) return next;
    const byKey = new Map(previous.map(item => [key(item), item]));
    const shared = next.map(item => {
        const kept = byKey.get(key(item));
        return kept !== undefined && (kept === item || same(kept, item)) ? kept : item;
    });
    return shared.length === previous.length && shared.every((item, index) => item === previous[index]) ? previous : shared;
}
