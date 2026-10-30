// Takes the README's screenshots of the demo, each in light and dark, at twice the pixels so they stay sharp.
//
// Usage: NODE_PATH=<dir>/node_modules node capture.cjs <out-dir> [<name> ...]
// Example: ./gradlew demo, wait five minutes so Activity spans its history, then ... docs/screenshots/capture.cjs docs/screenshots
//
// Playwright resolves from NODE_PATH; HUB names another hub than http://127.0.0.1:8080. Shrink the screenshots to 256 colours before committing them,
// with pngquant or alike: the page's flat colours lose nothing visible, and the files shrink to less than half.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const HUB = process.env.HUB ?? 'http://127.0.0.1:8080';
const VIEWPORT = { width: 1600, height: 1000 };
// Flow's lens is wide: from the event that caused the selected command to the groups its events reach, cut below its last lane.
const VIEWPORTS = { flow: { width: 1800, height: 980 } };
const AUTHORIZE_PAYMENT = `payments::COMMAND:${encodeURIComponent('app.dodb.smd.monitor.hub.demo.payments.Payments$AuthorizePaymentCommand')}`;

const settle = (page, millis = 1500) => page.waitForTimeout(millis);

const SHOTS = {
    overview: async page => {
        await page.goto(`${HUB}/#/overview`);
    },
    application: async page => {
        await page.goto(`${HUB}/?scope=payments#/overview`);
    },
    flow: async page => {
        await page.goto(`${HUB}/?type=${AUTHORIZE_PAYMENT}#/flow`);
        await settle(page);
        await page.keyboard.press('Escape');
        await page.getByRole('radiogroup', { name: 'Hops up- and downstream' }).getByRole('radio', { name: '2', exact: true }).click();
        await settle(page, 2000);
        await page.evaluate(() => {
            const board = document.querySelector('[aria-label="Flow"]').closest('.overflow-auto');
            const cause = [...board.querySelectorAll('*')].find(element => element.childElementCount === 0 && element.textContent === 'StockReservedEvent');
            const offset = cause.getBoundingClientRect().left - board.getBoundingClientRect().left;
            board.scrollTo({ top: 0, left: board.scrollLeft + offset - 200, behavior: 'instant' });
        });
    },
    traces: async page => {
        // The message column wide enough for the deepest names, as someone would drag it.
        const table = {
            messageWidth: 380,
            columns: [
                { key: 'application', width: 104 },
                { key: 'instance', width: 104 },
                { key: 'thread', width: 112 },
                { key: 'start', width: 72 },
                { key: 'wait', width: 64 },
                { key: 'duration', width: 84 },
            ],
        };
        await page.addInitScript(stored => localStorage.setItem('smd-monitor.waterfall-table', stored), JSON.stringify(table));
        await page.goto(`${HUB}/#/traces`);
        await settle(page);
        // A declined payment: storefront cancels the order, and inventory releases its stock.
        await page.getByLabel('Filter traces').fill('CancelOrderCommand');
        await settle(page, 2500);
        await page.locator('[data-trace-open]').first().click();
    },
    failures: async page => {
        await page.goto(`${HUB}/#/failures`);
    },
    live: async page => {
        await page.goto(`${HUB}/#/live`);
    },
    inspector: async page => {
        await page.goto(`${HUB}/#/overview`);
        await settle(page);
        await page.getByText('AuthorizePaymentCommand', { exact: true }).first().click();
        await settle(page);
        await page.getByRole('heading', { name: 'Message types' }).evaluate(heading => heading.closest('section').scrollIntoView({ block: 'start' }));
    },
};

async function main() {
    const [outDir, ...given] = process.argv.slice(2);
    const names = given.length ? given : Object.keys(SHOTS);
    if (!outDir || names.some(name => !SHOTS[name])) {
        console.error(`Usage: node capture.cjs <out-dir> [<name> ...], where a name is one of ${Object.keys(SHOTS).join(', ')}`);
        process.exit(2);
    }
    fs.mkdirSync(outDir, { recursive: true });
    const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
    try {
        for (const name of names) {
            for (const scheme of ['light', 'dark']) {
                const viewport = VIEWPORTS[name] ?? VIEWPORT;
                const page = await browser.newPage({ viewport, deviceScaleFactor: 2, colorScheme: scheme });
                await SHOTS[name](page);
                // Let the frames and the motion settle, and keep focus rings and hover out of the picture.
                await settle(page, 3000);
                await page.evaluate(() => document.activeElement instanceof HTMLElement && document.activeElement.blur());
                await page.mouse.move(0, viewport.height - 1);
                await settle(page, 1200);
                await page.screenshot({ path: path.join(outDir, `${name}-${scheme}.png`) });
                await page.close();
                console.log(`${name}-${scheme}`);
            }
        }
    } finally {
        await browser.close();
    }
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
