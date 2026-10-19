import { createMemo, Show } from 'solid-js';
import { useMonitorApp } from '../app/monitor-app';
import type { DropdownOption } from '../lib/dropdown';
import { count } from '../lib/format';
import { MultiSelect } from './MultiSelect';

// The system scope of more than one application narrows by application; an application's scope hides the field and ignores the choice.
export function ApplicationFilter(props: { selection: readonly string[]; onChange: (selection: readonly string[]) => void; class?: string }) {
    const app = useMonitorApp();
    const retained = (name: string) => app.system().applications.find(application => application.name === name)?.retained ?? 0;
    // Keeps an application that stopped reporting while it is chosen, so it can be cleared.
    const applications = createMemo(() =>
        [...new Set([...app.system().applications.map(application => application.name), ...props.selection])].sort((a, b) => a.localeCompare(b)),
    );
    // An application has a name, so the empty value stands for all of them.
    const options = createMemo((): DropdownOption<string>[] => [
        { value: '', label: 'All applications', selected: !props.selection.length },
        ...applications().map(name => ({ value: name, label: name, hint: count(retained(name)), selected: props.selection.includes(name) })),
    ]);
    const pick = (name: string) => {
        if (name === '') return props.onChange([]);
        props.onChange(props.selection.includes(name) ? props.selection.filter(other => other !== name) : [...props.selection, name]);
    };
    return (
        <Show when={app.scope() === null && applications().length > 1}>
            <MultiSelect
                label="Application"
                options={options()}
                chosen={props.selection}
                placeholder="Filter applications…"
                onPick={pick}
                class={props.class}
            />
        </Show>
    );
}
