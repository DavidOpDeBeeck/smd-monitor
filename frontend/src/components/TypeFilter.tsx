import { createEffect, createMemo, For, Show } from 'solid-js';
import { useMonitorApp } from '../app/monitor-app';
import { cx } from '../lib/cx';
import type { DropdownOption } from '../lib/dropdown';
import { count, isTypeKey, KINDS, parseTypeKey, shortName } from '../lib/format';
import {
    allOfKindLabel,
    everyTypeInScope,
    keepKnownTypes,
    kindSelected,
    selectionKinds,
    selectionInScope,
    selectionNames,
    selectsEveryType,
    toggleKind,
    toggleType,
    typeSelected,
    type TypeSelection,
} from '../lib/type-selection';
import type { MessageKind, TypeKey } from '../lib/types';
import { Glyph } from './Glyph';
import { MultiSelect } from './MultiSelect';

type KindValue = `kind:${MessageKind}`;
type Value = '' | KindValue | TypeKey;

const kindValue = (kind: MessageKind): KindValue => `kind:${kind}`;
// An application can be named kind, so a type key can start like a kind's value.
const isKindValue = (value: Value): value is KindValue => value.startsWith('kind:') && !isTypeKey(value);
const kindOf = (value: KindValue) => value.slice('kind:'.length) as MessageKind;

function KindGlyphs(props: { kinds: readonly MessageKind[] }) {
    return (
        <span class="inline-flex flex-none items-center gap-0.5">
            <For each={props.kinds}>{kind => <Glyph kind={kind} class={cx(props.kinds.length > 1 && 'size-1.5')} />}</For>
        </span>
    );
}

export function TypeFilter(props: { selection: TypeSelection; onChange: (selection: TypeSelection) => void; class?: string }) {
    const app = useMonitorApp();
    const types = createMemo(() => [...app.scoped().types.values()].sort((a, b) => shortName(a.messageType).localeCompare(shortName(b.messageType))));
    // The selection holds types of every application, so it outlives a scope; an application's scope shows and changes only its own.
    const known = createMemo(() => [...app.system().types.keys()]);
    const shownSelection = createMemo(() => selectionInScope(props.selection, app.scope()));
    const named = () => app.scope() === null && app.system().applications.length > 1;
    const retained = (key: TypeKey) => app.system().types.get(key)?.retained ?? 0;
    const options = createMemo(() => {
        const selection = shownSelection();
        const options: DropdownOption<Value>[] = [{ value: '', label: 'All types', selected: selectsEveryType(selection) }];
        for (const kind of KINDS) {
            const ofKind = types().filter(type => type.kind === kind);
            if (!ofKind.length) continue;
            const parent = kindValue(kind);
            const ofKindRetained = ofKind.reduce((sum, type) => sum + retained(type.key), 0);
            options.push({ value: parent, label: allOfKindLabel(kind), hint: count(ofKindRetained), selected: kindSelected(selection, kind) });
            for (const type of ofKind) {
                options.push({
                    value: type.key,
                    // The system scope names the application, since type names repeat across applications.
                    label: named() ? `${shortName(type.messageType)} · ${type.application}` : shortName(type.messageType),
                    hint: count(retained(type.key)),
                    selected: typeSelected(selection, type.key),
                    parent,
                });
            }
        }
        return options;
    });

    createEffect(() => {
        const kept = keepKnownTypes(props.selection, new Set(known()));
        if (kept !== props.selection) props.onChange(kept);
    });

    const pick = (value: Value) => {
        if (value === '') props.onChange(everyTypeInScope(props.selection, app.scope()));
        else if (isKindValue(value)) props.onChange(toggleKind(props.selection, kindOf(value)));
        else props.onChange(toggleType(props.selection, value, known()));
    };

    return (
        <MultiSelect
            label="Type"
            options={options()}
            chosen={selectionNames(shownSelection())}
            placeholder="Filter types…"
            onPick={pick}
            leading={
                <Show when={!selectsEveryType(shownSelection())}>
                    <KindGlyphs kinds={selectionKinds(shownSelection(), KINDS)} />
                </Show>
            }
            icon={value => (
                <Show when={value} fallback={<KindGlyphs kinds={KINDS} />}>
                    <Glyph kind={isKindValue(value) ? kindOf(value) : parseTypeKey(value as TypeKey).kind} />
                </Show>
            )}
            class={props.class}
        />
    );
}
