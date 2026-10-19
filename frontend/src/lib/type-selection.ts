import { parseTypeKey, shortName } from './format';
import type { MessageKind, TypeKey } from './types';

export interface TypeSelection {
    kinds: readonly MessageKind[];
    types: readonly TypeKey[];
}

export const everyType = (): TypeSelection => ({ kinds: [], types: [] });

export const selectsEveryType = (selection: TypeSelection) => !selection.kinds.length && !selection.types.length;

export function selectionMatches(selection: TypeSelection, kind: MessageKind, key: TypeKey): boolean {
    return selectsEveryType(selection) || selection.kinds.includes(kind) || selection.types.includes(key);
}

export const typeSelected = (selection: TypeSelection, key: TypeKey) => selection.kinds.includes(parseTypeKey(key).kind) || selection.types.includes(key);

export function kindSelected(selection: TypeSelection, kind: MessageKind): boolean | 'mixed' {
    if (selection.kinds.includes(kind)) return true;
    return selection.types.some(key => parseTypeKey(key).kind === kind) ? 'mixed' : false;
}

export function toggleKind(selection: TypeSelection, kind: MessageKind): TypeSelection {
    return {
        kinds: selection.kinds.includes(kind) ? selection.kinds.filter(other => other !== kind) : [...selection.kinds, kind],
        types: selection.types.filter(key => parseTypeKey(key).kind !== kind),
    };
}

export function toggleType(selection: TypeSelection, key: TypeKey, known: readonly TypeKey[]): TypeSelection {
    const { kind } = parseTypeKey(key);
    if (selection.kinds.includes(kind)) {
        const siblings = known.filter(other => other !== key && parseTypeKey(other).kind === kind && !selection.types.includes(other));
        return { kinds: selection.kinds.filter(other => other !== kind), types: [...selection.types, ...siblings] };
    }
    return { kinds: selection.kinds, types: selection.types.includes(key) ? selection.types.filter(other => other !== key) : [...selection.types, key] };
}

export function keepKnownTypes(selection: TypeSelection, known: ReadonlySet<TypeKey>): TypeSelection {
    const types = selection.types.filter(key => known.has(key));
    return types.length === selection.types.length ? selection : { kinds: selection.kinds, types };
}

const KIND_ALL: Record<MessageKind, string> = { COMMAND: 'All commands', QUERY: 'All queries', EVENT: 'All events' };

export const selectionNames = (selection: TypeSelection): string[] => [
    ...selection.kinds.map(kind => KIND_ALL[kind]),
    ...selection.types.map(key => shortName(parseTypeKey(key).messageType)),
];

export function selectionKinds(selection: TypeSelection, kinds: readonly MessageKind[]): MessageKind[] {
    return kinds.filter(kind => selection.kinds.includes(kind) || selection.types.some(key => parseTypeKey(key).kind === kind));
}

export const allOfKindLabel = (kind: MessageKind) => KIND_ALL[kind];

// An application's scope ignores the chosen types of other applications but keeps them, so the system scope still applies them.
export function selectionInScope(selection: TypeSelection, scope: string | null): TypeSelection {
    if (scope === null) return selection;
    const types = selection.types.filter(key => parseTypeKey(key).application === scope);
    return types.length === selection.types.length ? selection : { kinds: selection.kinds, types };
}

// Choosing every type in an application's scope clears only what the scope shows.
export function everyTypeInScope(selection: TypeSelection, scope: string | null): TypeSelection {
    if (scope === null) return everyType();
    return { kinds: [], types: selection.types.filter(key => parseTypeKey(key).application !== scope) };
}
