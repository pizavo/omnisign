import {useEffect, useId, useMemo, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import {usePluginData} from '@docusaurus/useGlobalData';
import styles from './styles.module.css';

interface GlossaryEntry {
  term: string;
  definition?: string;
  abbreviation?: string;
  id?: string;
}

interface GlossaryGlobalData {
  terms?: GlossaryEntry[];
  routePath?: string;
}

interface GlossaryTermProps {
  term: string;
  definition?: string;
  abbreviation?: string;
  routePath?: string;
  children?: ReactNode;
}

interface TooltipPosition {
  top: number;
  left: number;
}

/** Distance between the trigger and the tooltip, and the minimum gap to the viewport edge. */
const GAP = 8;

/**
 * Overrides the `GlossaryTerm` component shipped by `docusaurus-plugin-glossary`.
 *
 * Three deliberate departures from the bundled implementation:
 *
 * 1. The anchor is a Docusaurus [Link], so the router handles it as a client-side navigation and
 *    `baseUrl` is applied. `addBaseUrl` skips a path that already starts with `baseUrl`, so the
 *    plugin's `routePath` may carry the prefix it needs for `addRoute` without being prefixed twice.
 * 2. The tooltip is mounted only while it is shown. The bundled component renders every definition
 *    eagerly, which duplicates the text once per occurrence — invisible to readers, but the search
 *    indexer reads the HTML and does not honour `visibility: hidden`.
 * 3. The tooltip's id comes from [useId] rather than the term. Repeated occurrences of one term
 *    otherwise share a single DOM id, and `aria-describedby` resolves only to the first of them.
 *
 * `data-noBrokenLinkCheck` is required because the plugin's glossary page emits its term anchors as
 * raw `id` attributes. Docusaurus registers anchors through `useBrokenLinks().collectAnchor`, so it
 * never learns of them and would report every link here as broken.
 *
 * The pointer handlers sit on the wrapper, not on the [Link]: for an internal, non-anchor target
 * `Link` renders a React Router link and assigns its own prefetching `onMouseEnter` after spreading
 * the caller's props, silently discarding one passed to it. `onFocus` must stay on the anchor,
 * which is the focusable element.
 */
export default function GlossaryTerm({
  term,
  definition,
  abbreviation,
  routePath,
  children,
}: GlossaryTermProps): ReactNode {
  const [visible, setVisible] = useState(false);
  const [position, setPosition] = useState<TooltipPosition | null>(null);
  const triggerRef = useRef<HTMLAnchorElement>(null);
  const tooltipRef = useRef<HTMLSpanElement>(null);
  const tooltipId = useId();

  const globalData = usePluginData('docusaurus-plugin-glossary') as GlossaryGlobalData | undefined;

  const entry = useMemo(
    () => globalData?.terms?.find((candidate) => candidate.term.toLowerCase() === term.toLowerCase()),
    [globalData, term],
  );

  const text = definition || entry?.definition;
  const longForm = abbreviation || entry?.abbreviation;
  const target = routePath || globalData?.routePath || '/glossary';

  /**
   * Mirrors the glossary page's own `term.id || slug(term)` rule. A term such as `PKCS#11` must
   * carry an explicit id, or the derived slug would put a second `#` into the fragment.
   */
  const termId = entry?.id || term.toLowerCase().replace(/\s+/g, '-');

  useEffect(() => {
    if (!visible) {
      setPosition(null);
      return undefined;
    }

    const update = () => {
      const trigger = triggerRef.current;
      const tooltip = tooltipRef.current;
      if (!trigger || !tooltip) {
        return;
      }

      const anchor = trigger.getBoundingClientRect();
      const box = tooltip.getBoundingClientRect();
      const fitsAbove = anchor.top >= box.height + GAP;
      const fitsBelow = window.innerHeight - anchor.bottom >= box.height + GAP;
      const top = fitsAbove || !fitsBelow ? anchor.top - box.height - GAP : anchor.bottom + GAP;
      const centred = anchor.left + anchor.width / 2 - box.width / 2;
      const left = Math.min(Math.max(GAP, centred), window.innerWidth - box.width - GAP);

      setPosition({top: Math.max(GAP, top), left});
    };

    const frame = requestAnimationFrame(update);
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);

    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', update, true);
      window.removeEventListener('resize', update);
    };
  }, [visible]);

  return (
    <span
      className={styles.wrapper}
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}>
      <Link
        ref={triggerRef}
        to={`${target}#${termId}`}
        className={styles.term}
        data-noBrokenLinkCheck
        aria-describedby={visible && text ? tooltipId : undefined}
        onFocus={() => setVisible(true)}
        onBlur={() => setVisible(false)}>
        {children ?? term}
      </Link>
      {visible && text && (
        <span
          ref={tooltipRef}
          id={tooltipId}
          role="tooltip"
          className={styles.tooltip}
          style={position ? {top: position.top, left: position.left} : {opacity: 0}}>
          <strong className={styles.title}>{term}</strong>
          {longForm ? `(${longForm}). ` : ''}
          {text}
        </span>
      )}
    </span>
  );
}
