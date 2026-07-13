import {useCallback, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import Overlay from '@site/src/components/Overlay';
import styles from './styles.module.css';

interface ZoomableImageProps {
  src?: string;
  alt?: string;
  /**
   * How to render the image inline. Defaults to a plain lazy `<img>`, which is what an `.mdx` file
   * calling this component directly gets.
   *
   * The `img` theme override passes Docusaurus's own themed `Img` here instead, so that markdown
   * images keep its lazy loading and class handling along with every attribute the loader set on them.
   */
  children?: ReactNode;
}

interface Size {
  width: number;
  height: number;
}

/**
 * Opens an image in the shared enlarge overlay, fitted to the viewport and zoomable from there.
 *
 * Markdown images reach this through the `@theme/MDXComponents/Img` override. An `<img>` written as
 * literal JSX inside an `.mdx` file does **not** — MDX only maps markdown-generated elements through
 * the components map — so those must use this component directly. The desktop guides do exactly that,
 * because their screenshots are *animated* AVIF (`avis`), whose dimensions Docusaurus's markdown image
 * pipeline cannot read; they have to bypass it.
 *
 * The natural size comes from the inline image, which has already decoded by the time anyone can click
 * it. The `onLoad` on the enlarged copy is the fallback for the case where it somehow has not.
 */
export default function ZoomableImage({src, alt, children}: ZoomableImageProps): ReactNode {
  const [enlarged, setEnlarged] = useState(false);
  const [natural, setNatural] = useState<Size>();
  const triggerRef = useRef<HTMLButtonElement>(null);

  const open = useCallback(() => {
    const inline = triggerRef.current?.querySelector('img');
    if (inline?.naturalWidth) {
      setNatural({width: inline.naturalWidth, height: inline.naturalHeight});
    }
    setEnlarged(true);
  }, []);

  const close = useCallback(() => {
    setEnlarged(false);
    triggerRef.current?.focus();
  }, []);

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={styles.trigger}
        onClick={open}
        aria-label={alt ? `Enlarge image: ${alt}` : 'Enlarge image'}>
        {children ?? (
          <img
            decoding="async"
            loading="lazy"
            className={styles.inline}
            src={src}
            alt={alt ?? ''}
          />
        )}
      </button>
      {enlarged && (
        <Overlay title={alt} natural={natural} onClose={close}>
          <img
            src={src}
            alt={alt ?? ''}
            onLoad={(event) => {
              const image = event.currentTarget;
              if (!natural && image.naturalWidth) {
                setNatural({width: image.naturalWidth, height: image.naturalHeight});
              }
            }}
          />
        </Overlay>
      )}
    </>
  );
}
