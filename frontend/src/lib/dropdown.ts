export interface DropdownOption<T extends string> {
    value: T;
    label: string;
    hint?: string;
    selected: boolean | 'mixed';
    parent?: T;
}

type DropdownNavigation = 'ArrowDown' | 'ArrowUp';

export function filterOptions<T extends string>(options: readonly DropdownOption<T>[], query: string): DropdownOption<T>[] {
    const search = query.trim().toLowerCase();
    if (!search) return [...options];
    const matching = new Set(options.filter(option => option.label.toLowerCase().includes(search)).map(option => option.value));
    for (const option of options) if (matching.has(option.value) && option.parent !== undefined) matching.add(option.parent);
    return options.filter(option => matching.has(option.value));
}

export function stepOption<T>(values: readonly T[], current: T | null, key: DropdownNavigation): T | null {
    if (!values.length) return null;
    const index = current === null ? -1 : values.indexOf(current);
    if (index < 0) return key === 'ArrowDown' ? values[0] : values.at(-1)!;
    return values[Math.max(0, Math.min(values.length - 1, index + (key === 'ArrowDown' ? 1 : -1)))];
}
