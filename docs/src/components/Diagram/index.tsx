import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import Mermaid from '@theme/Mermaid';
import styles from './styles.module.css';

interface DiagramProps {
  source: string;
}

/**
 * Forces natural-size rendering, overriding `useMaxWidth` from `themeConfig.mermaid`.
 *
 * Mermaid emits `width="100%"` plus a `max-width` style when `useMaxWidth` is on, and explicit
 * pixel `width`/`height` attributes when it is off. The enlarged copy wants the latter so it can
 * scroll inside the overlay at full resolution.
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
 * Clicking it opens the same diagram at natural size in a scrollable overlay.
 *
 * The `title:` front matter is never drawn by Mermaid itself; it is lifted out and reused as the
 * figure's caption, the overlay's heading, and the trigger's accessible name. Any other front
 * matter, such as a `config:` block tuning `nodeSpacing`, is preserved.
 */
export default function Diagram({source}: DiagramProps): ReactNode {
  const [enlarged, setEnlarged] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const title = useMemo(() => extractTitle(source), [source]);
  const inlineSource = useMemo(() => stripTitle(source), [source]);
  const enlargedSource = useMemo(() => withNaturalSize(inlineSource), [inlineSource]);

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
        onClick={() => setEnlarged(true)}
        aria-label={title ? `Enlarge diagram: ${title}` : 'Enlarge diagram'}>
        <Mermaid value={inlineSource} />
        <span aria-hidden="true" className={styles.hint}>
          Click to enlarge
        </span>
      </button>
      {title && <figcaption className={styles.caption}>{title}</figcaption>}
      {enlarged && <Overlay title={title} source={enlargedSource} onClose={close} />}
    </figure>
  );
}

interface OverlayProps {
  title?: string;
  source: string;
  onClose: () => void;
}

/** Full-viewport overlay holding the diagram at natural size, scrollable in both axes. */
function Overlay({title, source, onClose}: OverlayProps): ReactNode {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKeyDown);

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  return (
    <div
      className={styles.backdrop}
      role="dialog"
      aria-modal="true"
      aria-label={title ?? 'Enlarged diagram'}
      onClick={onClose}>
      <div className={styles.header}>
        <span className={styles.title}>{title}</span>
        <button
          ref={closeRef}
          type="button"
          className={styles.close}
          onClick={onClose}
          aria-label="Close enlarged diagram">
          &times;
        </button>
      </div>
      <div
        className={styles.body}
        onClick={(event) => {
          event.stopPropagation();
        }}>
        <Mermaid value={source} />
      </div>
    </div>
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
