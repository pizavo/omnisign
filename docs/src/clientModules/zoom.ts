import mediumZoom from 'medium-zoom';
import type {Zoom, ZoomOptions} from 'medium-zoom';
import 'medium-zoom/dist/style.css';

/**
 * Images inside a doc page's rendered body. Mermaid diagrams render as inline `<svg>` and carry
 * their own enlarge-on-click overlay, so an `img` selector deliberately leaves them alone.
 */
const SELECTOR = '.markdown img';

/**
 * `background` is a CSS custom property rather than a literal colour. Medium Zoom assigns it to
 * the overlay's inline `style.background`, so the browser re-resolves it against the theme
 * currently stamped on `<html>` — light and dark follow the site with no observer to maintain.
 */
const OPTIONS: ZoomOptions = {
  background: 'var(--ifm-background-color)',
  margin: 24,
};

/**
 * The single zoom instance, created on first use.
 *
 * Medium Zoom registers `click`, `keyup`, `scroll` and `resize` listeners on `document`/`window`
 * when an instance is created, and offers no way to remove them. Creating one instance per
 * navigation would therefore leak four global listeners per page, so the instance is reused and
 * only its image bindings are refreshed.
 */
let zoom: Zoom | undefined;

/**
 * Rebinds the zoom to the images of the page that just rendered.
 *
 * Docusaurus dispatches this after the new route's DOM is committed — including the initial
 * render, where `previousLocation` is `null` — so the images are always present by the time it
 * runs. `detach()` drops the previous page's images (closing the overlay if one is open) and
 * clears the instance's internal list, which lets `attach()` pick up the current page's images.
 */
export function onRouteDidUpdate(): void {
  zoom ??= mediumZoom(OPTIONS);
  zoom.detach();
  zoom.attach(SELECTOR);
}
