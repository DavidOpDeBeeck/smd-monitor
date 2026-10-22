// A list the hub pages newest first, as far as it was loaded, with the cursor of the page that follows it.
export interface Paged<Item> {
    items: Item[];
    next: string | null;
}

// The list with its first page as the hub answers it now, followed by what the list held beyond that page's last item. Without that item among
// the held ones, the first page leaves a gap behind it, so the list starts over from the first page; a first page the hub has nothing after is the
// whole list. An item the first page holds is kept once.
export function withFirstPage<Item>(held: Paged<Item>, first: Paged<Item>, key: (item: Item) => string | number): Paged<Item> {
    const last = first.items.at(-1);
    const end = last === undefined ? -1 : held.items.findIndex(item => key(item) === key(last));
    if (first.next === null || end < 0 || end === held.items.length - 1) return first;
    const fresh = new Set(first.items.map(key));
    return { items: [...first.items, ...held.items.slice(end + 1).filter(item => !fresh.has(key(item)))], next: held.next };
}
