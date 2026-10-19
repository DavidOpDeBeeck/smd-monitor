import tailwindcss from '@tailwindcss/vite';
import { defineConfig, loadEnv, type Plugin } from 'vite';
import solid from 'vite-plugin-solid';

// Each build of the page gets an ID, which it writes beside itself for the hub to name in its frames and answers; a page whose build differs
// from the hub's reloads. The development server's page is the development build, which never reloads for that.
function buildId(build: string): Plugin {
    return {
        name: 'smd-monitor-build-id',
        apply: 'build',
        generateBundle() {
            this.emitFile({ type: 'asset', fileName: 'build-id', source: build });
        },
    };
}

export default defineConfig(({ command, mode }) => {
    const target = loadEnv(mode, '.', 'SMD_MONITOR_').SMD_MONITOR_TARGET || 'http://127.0.0.1:8080';
    const build = command === 'build' ? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` : 'development';
    return {
        base: command === 'build' ? './' : '/',
        define: { __SMD_MONITOR_BUILD__: JSON.stringify(build) },
        plugins: [solid(), tailwindcss(), buildId(build)],
        build: {
            outDir: '../build/generated/monitor-ui/smd-monitor',
            emptyOutDir: true,
            modulePreload: { polyfill: false },
            rolldownOptions: {
                output: { entryFileNames: 'app.js', assetFileNames: 'style.css' },
            },
        },
        server: {
            open: '/',
            proxy: {
                '/live': { target, ws: true, changeOrigin: true, rewriteWsOrigin: true },
                '/api': { target, changeOrigin: true },
            },
        },
    };
});
