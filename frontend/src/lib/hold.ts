export function holdList<T>(held: readonly T[], current: readonly T[], key: (item: T) => string | number): T[] {
    const currentByKey = new Map(current.map(item => [key(item), item]));
    return held.map(item => currentByKey.get(key(item)) ?? item);
}
