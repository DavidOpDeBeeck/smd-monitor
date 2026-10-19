export function readPreference(name: string): string | null {
    try {
        return localStorage.getItem(`smd-monitor.${name}`);
    } catch {
        return null;
    }
}

export function writePreference(name: string, value: string) {
    try {
        localStorage.setItem(`smd-monitor.${name}`, value);
    } catch {}
}

export async function copyText(text: string): Promise<boolean> {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        window.prompt('Copy to clipboard', text);
        return false;
    }
}
