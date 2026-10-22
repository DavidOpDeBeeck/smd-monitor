import { batch, createMemo, createSignal, onCleanup, type Accessor } from 'solid-js';
import { receive, type Mirror } from '../lib/mirror';
import { outdated } from '../lib/protocol';
import { emptyState, mergeFrame, type MonitorState } from '../lib/state';
import { seriesEnd } from '../lib/throughput';
import type { FrameMessage, MonitorFrame } from '../lib/wire';

const CLOCK_INTERVAL = 250;
const HEARTBEAT_CHECK_INTERVAL = 5000;
// The hub sends a frame every second.
const HEARTBEAT_TIMEOUT = 10_000;

export type ConnectionStatus = 'connecting' | 'live' | 'paused' | 'reconnecting' | 'paused-offline';

interface FrozenView {
    state: MonitorState;
    now: number;
}

export interface MonitorStore {
    live: Accessor<MonitorState>;
    view: Accessor<MonitorState>;
    now: Accessor<number>;
    secondNow: Accessor<number>;
    // Where the per-second figures of the view end.
    end: Accessor<number>;
    // The hub's time this instant, for what moves every animation frame rather than every clock tick.
    currentTime(): number;
    paused: Accessor<boolean>;
    // Whether the page is visible; a hidden page asks the hub nothing.
    visible: Accessor<boolean>;
    status: Accessor<ConnectionStatus>;
    togglePause(): void;
    connect(): void;
}

// One connection receives the whole system; the scope is applied in the page, so changing it never reconnects.
export function createMonitorStore(): MonitorStore {
    const [state, setState] = createSignal(emptyState(performance.now()));
    const [frozen, setFrozen] = createSignal<FrozenView | null>(null);
    const [connected, setConnected] = createSignal(false);
    const [everConnected, setEverConnected] = createSignal(false);
    const [disconnectedAt, setDisconnectedAt] = createSignal<number | null>(null);
    const [clock, setClock] = createSignal(performance.now());
    const [visible, setVisible] = createSignal(!document.hidden);
    let socket: WebSocket | null = null;
    // The frame the hub sent last on this connection, which its next delta changes.
    let mirror: Mirror | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let retryDelay = 500;
    let lastReceived = 0;

    const serverTime = (current: MonitorState, until: number) => current.capturedAt + Math.max(0, until - current.receivedAt);
    const liveNow = () => serverTime(state(), connected() ? performance.now() : (disconnectedAt() ?? state().receivedAt));

    const view = createMemo(() => frozen()?.state ?? state());
    const now = createMemo(() => frozen()?.now ?? serverTime(state(), connected() ? clock() : (disconnectedAt() ?? state().receivedAt)));
    const secondNow = createMemo(() => seriesEnd(now()));
    const end = createMemo(() => view().end);
    const status = createMemo<ConnectionStatus>(() => {
        if (!everConnected() && !connected()) return 'connecting';
        if (!connected()) return frozen() ? 'paused-offline' : 'reconnecting';
        return frozen() ? 'paused' : 'live';
    });

    function merge(frame: MonitorFrame) {
        const receivedAt = performance.now();
        const current = state();
        const next = mergeFrame(frame, receivedAt);
        lastReceived = Date.now();
        retryDelay = 500;
        batch(() => {
            if (next.hubSession !== current.hubSession) setFrozen(null);
            setState(next);
            setClock(receivedAt);
            setDisconnectedAt(null);
            setConnected(true);
            setEverConnected(true);
        });
    }

    function disconnected() {
        if (!connected()) return;
        batch(() => {
            setDisconnectedAt(performance.now());
            setConnected(false);
        });
    }

    function connect() {
        clearTimeout(reconnectTimer);
        const url = new URL('live', document.baseURI);
        url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const current = new WebSocket(url);
        socket = current;
        mirror = null;
        lastReceived = Date.now();
        current.onmessage = event => {
            if (current !== socket) return;
            try {
                const message = JSON.parse(event.data) as FrameMessage;
                if (outdated(message.protocol, message.build)) {
                    location.reload();
                    return;
                }
                const next = receive(mirror, message);
                if (next === null) {
                    // A delta of a frame this connection did not mirror: a new connection starts with a snapshot.
                    reconnect();
                    return;
                }
                mirror = next;
                merge(next.frame);
            } catch (failure) {
                console.warn('Discarding SMD monitor frame', failure);
                reconnect();
            }
        };
        current.onerror = () => current.close();
        current.onclose = () => {
            if (current !== socket) return;
            disconnected();
            reconnectTimer = setTimeout(connect, retryDelay);
            retryDelay = Math.min(retryDelay * 2, 10_000);
        };
    }

    function reconnect() {
        const stale = socket;
        socket = null;
        stale?.close();
        disconnected();
        connect();
    }

    function close() {
        clearTimeout(reconnectTimer);
        const current = socket;
        socket = null;
        current?.close();
        disconnected();
    }

    function togglePause() {
        setFrozen(frozen() ? null : { state: state(), now: liveNow() });
    }

    const ticking = setInterval(() => {
        if (connected() && !frozen() && visible()) setClock(performance.now());
    }, CLOCK_INTERVAL);
    const heartbeat = setInterval(() => {
        if (socket && connected() && Date.now() - lastReceived > HEARTBEAT_TIMEOUT) reconnect();
    }, HEARTBEAT_CHECK_INTERVAL);
    const pageShown = (event: PageTransitionEvent) => {
        if (event.persisted) connect();
    };
    const visibilityChanged = () => setVisible(!document.hidden);
    window.addEventListener('pagehide', close);
    window.addEventListener('pageshow', pageShown);
    document.addEventListener('visibilitychange', visibilityChanged);
    onCleanup(() => {
        clearInterval(ticking);
        clearInterval(heartbeat);
        window.removeEventListener('pagehide', close);
        window.removeEventListener('pageshow', pageShown);
        document.removeEventListener('visibilitychange', visibilityChanged);
        close();
    });

    return {
        live: state,
        view,
        now,
        secondNow,
        end,
        currentTime: () => frozen()?.now ?? liveNow(),
        paused: () => frozen() !== null,
        visible,
        status,
        togglePause,
        connect,
    };
}
