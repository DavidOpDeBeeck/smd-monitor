// Screenshots views of the benchmarks page, each named and chosen by its query string, as the proof a pull request shows.
//
// Usage: NODE_PATH=<dir>/node_modules node screenshots.cjs <page.html> <out-dir> <name>=<query> [<name>=<query> ...]
// Example: ... build/benchmarks/page.html build/pr-shots change="view=change" busy="view=steps&scenario=budget-256mb&measure=busy"
//
// Each view is taken as a figure (figure=1): the title, the series, the chart and its key, 1,000 pixels wide, as a pull request shows it without
// scaling it down, at twice the pixels so it stays sharp. Pass figure=0 in a query for the whole page.
//
// Playwright resolves from NODE_PATH. Chromium is Playwright's own, or the executable CHROMIUM names. The page holds its data inlined, so it opens
// from disk; a page written without data, or a script error on it, fails the run rather than producing an empty screenshot.
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const { chromium } = require('playwright');

const TEMPLATE_ONLY = 'This is the page template, without data';
const PAGE = { width: 1440, height: 900 };
const FIGURE = { width: 1000, height: 600 };

async function main() {
  const [page, outDir, ...shots] = process.argv.slice(2);
  if (!page || !outDir || shots.length === 0 || shots.some((shot) => !/^[\w-]+=/.test(shot))) {
    console.error('Usage: node screenshots.cjs <page.html> <out-dir> <name>=<query> [<name>=<query> ...]');
    process.exit(2);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
  try {
    const context = await browser.newContext({ viewport: FIGURE, deviceScaleFactor: 2, colorScheme: 'light' });
    const tab = await context.newPage();
    const errors = [];
    tab.on('pageerror', (error) => errors.push(error.message));
    for (const shot of shots) {
      const name = shot.slice(0, shot.indexOf('='));
      const given = shot.slice(shot.indexOf('=') + 1);
      const query = /(^|&)figure=/.test(given) ? given : `${given}&figure=1`;
      const viewport = /(^|&)figure=1/.test(query) ? FIGURE : PAGE;
      await tab.setViewportSize(viewport);
      await tab.goto(`${pathToFileURL(path.resolve(page))}?${query}`, { waitUntil: 'load' });
      const text = await tab.innerText('body');
      if (text.includes(TEMPLATE_ONLY)) {
        throw new Error(`${page} is the template without data; write it with ./gradlew benchmarkPage first`);
      }
      if (errors.length) {
        throw new Error(`The page failed for ${query}: ${errors.join('; ')}`);
      }
      // The page fits the window and scrolls its panels within it; grow the window by what they hide, so the screenshot holds every row and
      // every column.
      const hidden = await tab.evaluate(() => {
        const scrolling = [...document.querySelectorAll('*')].map((element) => [element, getComputedStyle(element)]);
        const most = (axis, overflow) => Math.max(0, ...scrolling.filter(([, style]) => /auto|scroll/.test(style[overflow]))
          .map(([element]) => (axis === 'x' ? element.scrollWidth - element.clientWidth : element.scrollHeight - element.clientHeight)));
        return { x: most('x', 'overflowX'), y: most('y', 'overflowY') };
      });
      if (hidden.x > 0 || hidden.y > 0) {
        await tab.setViewportSize({ width: viewport.width + hidden.x, height: viewport.height + hidden.y });
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
