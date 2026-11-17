// PDF §5.9 performance budget: each remote's INITIAL JavaScript ≤ 200 KB gzip (hard CI gate).
// Lazy chunks (KIE editors, formio builder) are reported but not counted — see ADR-010.
import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { basename, join } from "node:path";
import { gzipSync } from "node:zlib";

const LIMIT = 200 * 1024;
const apps = { shell: "dist", settlements: "dist", compliance: "dist", "process-studio": "dist", "form-builder": "dist", analytics: "dist/browser" };
const lazy = /(kie|bpmn|dmn|formio|builder|editor|monaco|echarts-lazy)/i;
let failed = false;

function walk(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

for (const [app, out] of Object.entries(apps)) {
  const dir = join("apps", app, out);
  if (!existsSync(dir)) { console.log(`- ${app}: not built`); continue; }
  let initial = 0, deferred = 0;
  for (const f of walk(dir).filter((f) => f.endsWith(".js"))) {
    const gz = gzipSync(readFileSync(f)).length;
    // Lazy = Rspack async chunks (static/js/async/), Angular lazy chunks (chunk-*.js), or named lazy bundles.
    const name = basename(f);
    const isLazy = /[\/]async[\/]/.test(f) || /^chunk-/.test(name) || lazy.test(name);
    if (isLazy) deferred += gz; else initial += gz;
  }
  const ok = initial <= LIMIT;
  failed ||= !ok;
  console.log(`${ok ? "✓" : "✗"} ${app}: initial ${(initial / 1024).toFixed(1)} KB gz (limit 200), lazy ${(deferred / 1024).toFixed(1)} KB gz`);
}
process.exit(failed ? 1 : 0);
