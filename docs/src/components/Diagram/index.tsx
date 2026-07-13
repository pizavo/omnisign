import type {ReactNode} from 'react';
import {useCallback, useMemo, useRef, useState} from 'react';
import Mermaid from '@theme/Mermaid';
import Overlay from '@site/src/components/Overlay';
import styles from './styles.module.css';

interface DiagramProps {
    source: string;
}

/**
 * Forces natural-size rendering, overriding `useMaxWidth` from `themeConfig.mermaid`.
 *
 * Mermaid emits `width="100%"` plus an inline `max-width` style when `useMaxWidth` is on, and plain
 * pixel `width`/`height` attributes when it is off. The enlarged copy wants the latter: the overlay
 * sizes the diagram itself, and a stylesheet can override an attribute but not an inline style.
 */
const NATURAL_SIZE_DIRECTIVE =
    '%%{init: {"flowchart": {"useMaxWidth": false}, "sequence": {"useMaxWidth": false}} }%%';

const FRONT_MATTER = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/;

/**
 * Renders a Mermaid diagram from the raw text of a `.mmd` file under `diagrams/`.
 *
 * Those files are the single source of truth: `pnpm run diagrams` renders them to SVG and PNG
 * for the thesis, and this component renders the same text on the site. Import one with the
 * `.mmd` extension — the `mmd-source-loader` plugin in `docusaurus.config.ts` hands it over as
 * a string:
 *
 * ```mdx
 * import Diagram from '@site/src/components/Diagram';
 * import moduleDependencies from '@site/diagrams/04-module-dependencies.mmd';
 *
 * <Diagram source={moduleDependencies} />
 * ```
 *
 * The diagram scales to the content column, which leaves the labels on the wider ones small.
 * Clicking it opens the same diagram in an overlay, fitted to the viewport and zoomable from there.
 *
 * The `title:` front matter is never drawn by Mermaid itself; it is lifted out and reused as the
 * figure's caption, the overlay's heading, and the trigger's accessible name. Any other front
 * matter, such as a `config:` block tuning `nodeSpacing`, is preserved.
 */
export default function Diagram({source}: DiagramProps): ReactNode {
    const [enlarged, setEnlarged] = useState(false);
    const [natural, setNatural] = useState<{width: number; height: number}>();
    const triggerRef = useRef<HTMLButtonElement>(null);

    const title = useMemo(() => extractTitle(source), [source]);
    const inlineSource = useMemo(() => stripTitle(source), [source]);
    const enlargedSource = useMemo(() => withNaturalSize(inlineSource), [inlineSource]);

    const open = useCallback(() => {
        const box = triggerRef.current?.querySelector('.docusaurus-mermaid-container svg')?.viewBox
            ?.baseVal;
        if (box?.width && box.height) {
            setNatural({width: box.width, height: box.height});
        }
        setEnlarged(true);
    }, []);

    const close = useCallback(() => {
        setEnlarged(false);
        triggerRef.current?.focus();
    }, []);

    return (
        <figure className={styles.figure}>
            <button
                ref={triggerRef}
                type="button"
                className={styles.trigger}
                onClick={open}
                aria-label={title ? `Enlarge diagram: ${title}` : 'Enlarge diagram'}>
                <Mermaid value={inlineSource}/>
                <span aria-hidden="true" className={styles.hint}>
          <ZoomInIcon/>
          Enlarge
        </span>
            </button>
            {title && <figcaption className={styles.caption}>{title}</figcaption>}
            {enlarged && (
                <Overlay title={title} natural={natural} onClose={close}>
                    <Mermaid value={enlargedSource}/>
                </Overlay>
            )}
        </figure>
    );
}

/**
 * The Tabler `zoom-in` glyph, inlined rather than taken from `AppIcon`.
 *
 * `AppIcon` renders the *desktop app's* drawables, and the app has no magnifier: adding one to
 * `composeResources/drawable/` would ship a resource the app never draws, and would imply to a
 * reader that OmniSign has a magnifier button somewhere. This is site chrome, so it lives with the
 * component that uses it. The path data comes from the same Tabler outline family as the app's
 * icons, so it still looks native beside them.
 *
 * The label is a modality-neutral verb — the diagrams are at their least legible on a phone, which
 * is exactly where "click" would be the wrong instruction.
 */
function ZoomInIcon(): ReactNode {
    return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
             className={styles.hintIcon}>
            <path stroke="none" d="M0 0h24v24H0z" fill="none"/>
            <path d="M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0"/>
            <path d="M7 10l6 0"/>
            <path d="M10 7l0 6"/>
            <path d="M21 21l-6 -6"/>
        </svg>
    );
}

/** Reads the `title:` value out of the diagram's YAML front matter, if it has one. */
function extractTitle(source: string): string | undefined {
    const match = FRONT_MATTER.exec(source);
    return match?.[1]
        .split(/\r?\n/)
        .find((line) => /^title:\s/.test(line))
        ?.replace(/^title:\s*/, '')
        .trim();
}

/**
 * Strips a `title:` line from the diagram's YAML front matter, removing the whole front-matter
 * block when `title:` was all it contained.
 */
function stripTitle(source: string): string {
    const match = FRONT_MATTER.exec(source);
    if (!match) {
        return source;
    }

    const body = source.slice(match[0].length);
    const kept = match[1].split(/\r?\n/).filter((line) => !/^title:\s/.test(line));

    if (kept.every((line) => line.trim() === '')) {
        return body;
    }

    return `---\n${kept.join('\n')}\n---\n${body}`;
}

/** Inserts the natural-size directive after the front matter, or at the top when there is none. */
function withNaturalSize(source: string): string {
    const match = FRONT_MATTER.exec(source);
    if (!match) {
        return `${NATURAL_SIZE_DIRECTIVE}\n${source}`;
    }
    return `${match[0]}${NATURAL_SIZE_DIRECTIVE}\n${source.slice(match[0].length)}`;
}
