import { cx } from '../lib/cx';

export function Dot(props: { class: string }) {
    return <i aria-hidden="true" class={cx('size-2 flex-none rounded-full', props.class)} />;
}
