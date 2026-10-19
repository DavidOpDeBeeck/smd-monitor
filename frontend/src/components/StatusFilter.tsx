import { STATUS_LABEL, STATUSES } from '../lib/format';
import type { StatusSelection } from '../lib/status-selection';
import type { DispatchStatus } from '../lib/types';
import { Segmented } from './controls';

type Value = 'ALL' | DispatchStatus;

const OPTIONS: readonly { value: Value; label: string; title: string }[] = [
    { value: 'ALL', label: 'All', title: 'Every outcome' },
    ...STATUSES.map(status => ({ value: status, label: STATUS_LABEL[status], title: `Only ${STATUS_LABEL[status].toLowerCase()}` })),
];

// Choosing every status is the same as choosing none, so the field shows one status or all of them.
export function StatusFilter(props: { selection: StatusSelection; onChange: (selection: StatusSelection) => void }) {
    const value = (): Value => (props.selection.length === 1 ? props.selection[0] : 'ALL');
    return <Segmented label="Status" value={value()} options={OPTIONS} onChange={next => props.onChange(next === 'ALL' ? [] : [next])} />;
}
