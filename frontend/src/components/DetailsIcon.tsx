import { cx } from '../lib/cx';

// Opens what it sits beside in the inspector.
export function DetailsIcon(props: { class?: string }) {
    return (
        <svg
            class={cx('size-4', props.class)}
            viewBox="0 0 16 16"
            fill="none"
            stroke="currentColor"
            stroke-width="1.4"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
        >
            <rect x="1.75" y="2.75" width="12.5" height="10.5" rx="2" />
            <path d="M9.75 2.75v10.5" />
            <path d="M11.5 6h1M11.5 8h1" />
        </svg>
    );
}
