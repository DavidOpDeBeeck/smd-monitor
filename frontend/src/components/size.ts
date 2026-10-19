import { createSignal, onCleanup, onMount, type Accessor } from 'solid-js';

interface Size {
    width: number;
    height: number;
}

export function createElementSize(element: () => HTMLElement): Accessor<Size> {
    const [size, setSize] = createSignal<Size>({ width: 0, height: 0 }, { equals: (a, b) => a.width === b.width && a.height === b.height });
    onMount(() => {
        const target = element();
        const measure = () => setSize({ width: target.clientWidth, height: target.clientHeight });
        measure();
        const observer = new ResizeObserver(measure);
        observer.observe(target);
        onCleanup(() => observer.disconnect());
    });
    return size;
}
