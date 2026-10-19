export function stepTab<T>(tabs: readonly T[], current: T, key: string): T | null {
    if (!tabs.length) return null;
    const index = Math.max(0, tabs.indexOf(current));
    switch (key) {
        case 'ArrowRight':
            return tabs[(index + 1) % tabs.length];
        case 'ArrowLeft':
            return tabs[(index - 1 + tabs.length) % tabs.length];
        case 'Home':
            return tabs[0];
        case 'End':
            return tabs.at(-1)!;
        default:
            return null;
    }
}
