// Screenshots views of the saturation page, each named and chosen by its query string, as the proof a pull request shows.
//
// Usage: NODE_PATH=<dir>/node_modules node dashboard_screenshots.cjs <dashboard.html> <out-dir> <name>=<query> [<name>=<query> ...]
// Example: ... build/saturation-page/dashboard.html build/pr-shots change="view=change&runs=7,8" ladder="view=ladder&scenario=budget-256mb&runs=7,8"
//
// Playwright resolves from NODE_PATH. Chromium is Playwright's own, or the executable CHROMIUM names. The page holds its data inlined, so it opens
// from disk; a page written without data, or a script error on it, fails the run rather than producing an empty screenshot.
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { chromium } = require('playwright');

const TEMPLATE_ONLY = 'This is the page template, without data';
const VIEWPORT = { width: 1440, height: 900 };

async function main() {
  const [page, outDir, ...shots] = process.argv.slice(2);
  if (!page || !outDir || shots.length === 0 || shots.some((shot) => !/^[\w-]+=/.test(shot))) {
    console.error('Usage: node dashboard_screenshots.cjs <dashboard.html> <out-dir> <name>=<query> [<name>=<query> ...]');
    process.exit(2);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  try {
    const context = await browser.newContext({ viewport: VIEWPORT, colorScheme: 'light' });
    const tab = await context.newPage();
    const errors = [];
    tab.on('pageerror', (error) => errors.push(error.message));
    for (const shot of shots) {
      const name = shot.slice(0, shot.indexOf('='));
      const query = shot.slice(shot.indexOf('=') + 1);
      await tab.setViewportSize(VIEWPORT);
      await tab.goto(`${pathToFileURL(path.resolve(page))}?${query}`, { waitUntil: 'load' });
      const text = await tab.innerText('body');
      if (text.includes(TEMPLATE_ONLY)) {
        throw new Error(`${page} is the template without data; write it with dashboard.py --out first`);
      }
      if (errors.length) {
        throw new Error(`The page failed for ${query}: ${errors.join('; ')}`);
      }
      // The page fits the window and scrolls its panels within it; grow the window by what they hide, so the screenshot holds every row.
      const hidden = await tab.evaluate(() => Math.max(0, ...[...document.querySelectorAll('*')]
        .filter((element) => /auto|scroll/.test(getComputedStyle(element).overflowY))
        .map((element) => element.scrollHeight - element.clientHeight)));
      if (hidden > 0) {
        await tab.setViewportSize({ width: VIEWPORT.width, height: VIEWPORT.height + hidden });
        await tab.waitForFunction(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve(true)))));
      }
      const out = path.join(outDir, `${name}.png`);
      await tab.screenshot({ path: out, fullPage: true });
      console.log(`${out}  <- ?${query}`);
    }
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
