// @ts-check
/**
 * Render every Mermaid source in `docs/diagrams/` to an SVG and a high-resolution PNG.
 *
 * Those `.mmd` files are the single source of truth: the docs site imports them directly
 * (see `src/components/Diagram`), and this script produces the raster/vector copies the
 * thesis and its slide deck embed. Output goes to the repository-root `diagrams/` directory,
 * which is deliberately git-ignored — the images are build products, the sources are not.
 *
 * Run it after editing any `.mmd`:
 *
 * ```
 * pnpm run diagrams
 * ```
 *
 * `mermaid-cli` is invoked through `npx` rather than added as a dependency, because it pulls
 * a headless Chromium that neither `pnpm install` nor the docs CI build has any use for.
 *
 * Rendering always passes `mermaid-config.json`, which disables `htmlLabels`. Without it
 * Mermaid emits flowchart labels as HTML inside `<foreignObject>`, which only a browser
 * draws — PowerPoint, LibreOffice, and most SVG converters silently drop them, leaving the
 * boxes and arrows with no text. This script fails if any rendered SVG still contains one.
 */
import {execFileSync} from 'node:child_process';
import {readFileSync, readdirSync, mkdirSync} from 'node:fs';
import {join, dirname, basename} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SOURCE_DIR = join(here, '..', 'diagrams');
const OUT_DIR = join(here, '..', '..', 'diagrams');
const CONFIG = join(SOURCE_DIR, 'mermaid-config.json');

const onWindows = process.platform === 'win32';
const LAUNCHER = onWindows ? 'cmd' : 'npx';
const LAUNCHER_PREFIX = onWindows ? ['/c', 'npx'] : [];

/**
 * Invoke `mmdc` via npx with the shared Mermaid config.
 *
 * Windows reaches npx through `cmd /c` rather than `shell: true`, because Node refuses to
 * execute a `.cmd` outside a shell (EINVAL) and passing an argument array with `shell: true`
 * is deprecated — the arguments are concatenated rather than escaped (DEP0190).
 */
function mmdc(input, output, extraArgs = []) {
  execFileSync(
    LAUNCHER,
    [...LAUNCHER_PREFIX, '-y', '-p', '@mermaid-js/mermaid-cli', 'mmdc', '-i', input, '-o', output, '-c', CONFIG, ...extraArgs],
    {stdio: ['ignore', 'ignore', 'inherit']},
  );
}

/** Fail loudly if a rendered SVG kept its `foreignObject` labels. */
function assertNativeText(svgPath) {
  const svg = readFileSync(svgPath, 'utf8');
  if (svg.includes('foreignObject')) {
    throw new Error(`${basename(svgPath)} still contains <foreignObject> labels — check mermaid-config.json`);
  }
}

mkdirSync(OUT_DIR, {recursive: true});

const sources = readdirSync(SOURCE_DIR).filter((f) => f.endsWith('.mmd')).sort();
if (sources.length === 0) {
  throw new Error(`no .mmd sources found in ${SOURCE_DIR}`);
}

for (const source of sources) {
  const name = basename(source, '.mmd');
  const input = join(SOURCE_DIR, source);
  const svg = join(OUT_DIR, `${name}.svg`);
  const png = join(OUT_DIR, `${name}.png`);

  mmdc(input, svg);
  assertNativeText(svg);
  mmdc(input, png, ['-s', '3', '-b', 'white']);

  console.log(`  ${name}  ->  svg + png`);
}

console.log(`\nRendered ${sources.length} diagrams into ${OUT_DIR}`);
