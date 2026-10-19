// The version of the wire format this page reads, and its build (W6). A frame or answer of another version, or of another build of the page
// than the hub serves now, means the hub was upgraded: the page reloads. The development build reloads only for another version.
export const PROTOCOL = 1;
export const BUILD = __SMD_MONITOR_BUILD__;
const DEVELOPMENT = 'development';

export function outdated(protocol: number | string | null, build: string | null): boolean {
    if (protocol === null || build === null) return false;
    if (Number(protocol) !== PROTOCOL) return true;
    return BUILD !== DEVELOPMENT && build !== DEVELOPMENT && build !== BUILD;
}
