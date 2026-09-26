// Exports every generated diagram and screen mock-up to PNG without draw.io desktop: the draw.io
// viewer (viewer-static.min.js, fetched once from viewer.diagrams.net into the OS temp directory)
// renders each .drawio in headless Chromium through Playwright, and the page is cropped to the
// drawing plus a border, the way draw.io's own export crops.
//
//   node docs/diagrams/export_png.js            every picture below
//   node docs/diagrams/export_png.js links      only the ones whose name contains "links"
//
// Needs Playwright (`npm i -g playwright`, or NODE_PATH pointing at an installation) with Chromium,
// and the fonts the drawings name: Roboto Mono (the phone's monospace; Consolas, named first for
// Windows, is not on Linux) and a Helvetica stand-in (Liberation Sans). A missing font falls back
// silently and the mock-ups no longer measure like the phone, so install them first
// (fonts.google.com: Roboto Mono; `apt install fonts-liberation`).
//
// The border is in drawing units and chosen so each canvas keeps the size draw.io desktop gave it
// (make_diagrams.py explains why the phone mock-ups must all share one).
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const https = require("https");

const ROOT = path.resolve(__dirname, "..", "..");
const VIEWER_URL = "https://viewer.diagrams.net/js/viewer-static.min.js";

const FLOW = { scale: 1.5, border: 14 };
const TARGETS = [
  ...["architecture", "audio-route", "links", "mesh", "packet-flow", "talk-keys",
    "screen-main", "screen-on-air", "screen-status", "screens", "screens-ask", "screens-detail", "screens-quickstart"]
    .map((n) => ({ src: `docs/diagrams/${n}.drawio`, out: `docs/images/${n}.png`, ...FLOW })),
  { src: "docs/diagrams/screen-settings.drawio", out: "docs/images/screen-settings.png", scale: 1, border: 17 },
  ...["announcement", "how-it-fits"].map((n) => ({
    src: `sk-plugin/docs/diagrams/${n}.drawio`, out: `sk-plugin/public/screenshots/${n}.png`, scale: 1, border: 0
  }))
];

function playwright() {
  try { return require("playwright"); } catch (_) {}
  const global = require("child_process").execSync("npm root -g").toString().trim();
  return require(path.join(global, "playwright"));
}

function viewer() {
  const cached = path.join(os.tmpdir(), "drawio-viewer-static.min.js");
  if (fs.existsSync(cached)) return Promise.resolve(fs.readFileSync(cached, "utf8"));
  return new Promise((resolve, reject) => {
    https.get(VIEWER_URL, (res) => {
      if (res.statusCode !== 200) return reject(new Error(`${VIEWER_URL}: HTTP ${res.statusCode}`));
      const parts = [];
      res.on("data", (d) => parts.push(d));
      res.on("end", () => {
        const js = Buffer.concat(parts).toString("utf8");
        fs.writeFileSync(cached, js);
        resolve(js);
      });
    }).on("error", reject);
  });
}

(async () => {
  const only = process.argv[2];
  const targets = TARGETS.filter((t) => !only || t.src.includes(only));
  if (!targets.length) throw new Error(`nothing matches "${only}"`);
  const js = await viewer();
  const browser = await playwright().chromium.launch();
  try {
    for (const t of targets) {
      const page = await browser.newPage({ viewport: { width: 3000, height: 3000 }, deviceScaleFactor: t.scale });
      await page.setContent('<html><body style="margin:0;background:#fff"><div id="g" style="margin:60px"></div></body></html>');
      await page.addScriptTag({ content: js });
      const xml = fs.readFileSync(path.join(ROOT, t.src), "utf8");
      const b = await page.evaluate(async (xml) => {
        await document.fonts.ready;
        const doc = mxUtils.parseXml(xml);
        const v = new GraphViewer(document.getElementById("g"), doc.documentElement,
          { highlight: "none", nav: false, toolbar: "", lightbox: false, border: 0 });
        v.graph.container.style.overflow = "visible";
        const r = v.graph.getGraphBounds();
        const c = v.graph.container.getBoundingClientRect();
        return { x: c.left + r.x, y: c.top + r.y, w: r.width, h: r.height };
      }, xml);
      await page.waitForTimeout(300);
      await page.screenshot({
        path: path.join(ROOT, t.out),
        clip: { x: b.x - t.border, y: b.y - t.border, width: b.w + 2 * t.border, height: b.h + 2 * t.border }
      });
      console.log("wrote", t.out);
      await page.close();
    }
  } finally {
    await browser.close();
  }
})().catch((e) => { console.error(e.message); process.exit(1); });
