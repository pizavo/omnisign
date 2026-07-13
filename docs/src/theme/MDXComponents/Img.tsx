import type {ReactNode} from 'react';
import Img from '@theme-original/MDXComponents/Img';
import type {Props} from '@theme/MDXComponents/Img';
import ZoomableImage from '@site/src/components/ZoomableImage';

/**
 * Routes every markdown image through the shared enlarge overlay.
 *
 * Wrapping the themed `Img` rather than replacing it keeps its lazy loading and class handling, and
 * the inline image is otherwise untouched, so nothing about the page's layout changes — only the
 * `zoom-in` cursor marks it as enlargeable.
 *
 * This catches markdown images only. MDX does not map a literal `<img>` written as JSX through the
 * components map, so those have to reach for [ZoomableImage] themselves.
 */
export default function ImgWrapper(props: Props): ReactNode {
  return (
    <ZoomableImage src={props.src} alt={props.alt}>
      <Img {...props} />
    </ZoomableImage>
  );
}
