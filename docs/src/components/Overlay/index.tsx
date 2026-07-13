import {useEffect, useLayoutEffect, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import clsx from 'clsx';
import styles from './styles.module.css';

interface Size {
  width: number;
  height: number;
}

interface OverlayProps {
  /** Heading for the overlay bar, and the dialog's accessible name. */
  title?: string;
  /**
   * Intrinsic size of the content — an image's natural pixels, a diagram's `viewBox`.
   *
   * The overlay scales the content to fit the viewport once, then freezes it at that pixel size.
   * Without it the content is left to size itself.
   */
  natural?: Size;
  children: ReactNode;
  onClose: () => void;
}

/**
 * Full-viewport overlay that fits its content to the viewport and lets a browser zoom magnify it.
 *
 * Shared by the Mermaid diagrams and by every image, so that the two enlarge alike: the whole thing
 * is visible on open, and Ctrl+Wheel takes you in from there, scrolling to pan.
 *
 * The fit has to be an explicit pixel size, and it must never be recomputed. A percentage — or a fit
 * recalculated as the viewport changes — ties the content to its container, and a browser zoom shrinks
 * the container in CSS pixels by exactly the factor by which it magnifies each pixel. The two cancel,
 * and the content never actually grows. That is the trap Medium Zoom fell into, and the one
 * `@docusaurus/theme-mermaid` sets with `.container, .container > svg { max-width: 100% }` — which is
 * why the fitted box lifts that cap on whatever it holds. Frozen in pixels, the content simply renders
 * larger as you zoom.
 *
 * Closing is limited to Escape, the close button, and a click on the backdrop. It deliberately does
 * **not** close on scroll: scrolling is how you pan around content you have zoomed into, and a browser
 * zoom reflows the document and moves the scroll position on its own. Medium Zoom closed on scroll,
 * which is precisely why zooming into an enlarged screenshot used to dismiss it.
 */
export default function Overlay({title, natural, children, onClose}: OverlayProps): ReactNode {
  const closeRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<Size>({width: 0, height: 0});

  useLayoutEffect(() => {
    const body = bodyRef.current;
    if (!body || !natural?.width || !natural?.height) {
      return;
    }

    const padding = getComputedStyle(body);
    const availableWidth =
      body.clientWidth - parseFloat(padding.paddingLeft) - parseFloat(padding.paddingRight);
    const availableHeight =
      body.clientHeight - parseFloat(padding.paddingTop) - parseFloat(padding.paddingBottom);

    const scale = Math.min(1, availableWidth / natural.width, availableHeight / natural.height);
    setSize({
      width: Math.floor(natural.width * scale),
      height: Math.floor(natural.height * scale),
    });
  }, [natural?.width, natural?.height]);

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

  const fitted = size.width > 0 && size.height > 0;

  return (
    <div
      className={styles.backdrop}
      role="dialog"
      aria-modal="true"
      aria-label={title ?? 'Enlarged view'}
      onClick={onClose}>
      <div className={styles.header}>
        <span className={styles.title}>{title}</span>
        <button
          ref={closeRef}
          type="button"
          className={styles.close}
          onClick={onClose}
          aria-label="Close enlarged view">
          &times;
        </button>
      </div>
      <div
        className={styles.body}
        ref={bodyRef}
        onClick={(event) => {
          event.stopPropagation();
        }}>
        <div
          className={clsx(styles.content, fitted && styles.fitted)}
          style={fitted ? {width: `${size.width}px`, height: `${size.height}px`} : undefined}>
          {children}
        </div>
      </div>
    </div>
  );
}
