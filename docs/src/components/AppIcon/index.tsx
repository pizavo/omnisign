import React from 'react';
import clsx from 'clsx';
import {icons, type IconName} from '@site/src/icons';
import styles from './styles.module.css';

/**
 * Semantic tint keys that mirror the desktop Lumo theme. Each maps to a theme-aware colour in
 * `styles.module.css`; omit `color` to inherit the surrounding text colour (also theme-aware).
 */
type IconColor =
  | 'euStars'
  | 'error'
  | 'success'
  | 'warning'
  | 'qualified'
  | 'qualifiedQscd'
  | 'folder'
  | 'muted';

interface AppIconProps {
  /** Icon name — a drawable file stem without the `icon_` prefix (e.g. `eu`, `shield_check`). */
  name: IconName;
  /** Optional semantic tint; defaults to the current text colour. */
  color?: IconColor;
  /** Size in CSS units; a bare number is treated as `px`. Defaults to `1.2em` (scales with text). */
  size?: number | string;
  /** Accessible label. When given the icon is exposed as an image; otherwise it is decorative. */
  label?: string;
  className?: string;
}

/**
 * Render one of the desktop app's icons inline, recoloured to match the app via a semantic tint.
 *
 * The SVGs are generated from the app's drawables with `currentColor` fills (see
 * `scripts/generate-icons.mjs`), so the tint is applied as a CSS `color` on the `<svg>` element.
 */
export default function AppIcon({
  name,
  color,
  size = '1.2em',
  label,
  className,
}: AppIconProps): React.ReactElement | null {
  const Svg = icons[name];
  if (!Svg) {
    return null;
  }
  const dimension = typeof size === 'number' ? `${size}px` : size;
  return (
    <Svg
      className={clsx(styles.icon, color && styles[color], className)}
      width={dimension}
      height={dimension}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    />
  );
}
