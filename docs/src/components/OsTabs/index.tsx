import type {ReactNode} from 'react';
import Tabs from '@theme/Tabs';

interface OsTabsProps {
  children: ReactNode;
}

/**
 * Pre-configured OS tab group. Wrap three `<TabItem>` elements (windows,
 * linux, macos) inside this component. All instances on the page (and
 * across pages via localStorage) stay synchronised thanks to the shared
 * `groupId`.
 *
 * Usage in MDX — leave a **blank line** after each `<TabItem>` tag so
 * that MDX parses the body as Markdown:
 *
 * ```mdx
 * <OsTabs>
 * <TabItem value="windows" label="Windows">
 *
 * Windows-specific **Markdown** here.
 *
 * </TabItem>
 * <TabItem value="linux" label="Linux">
 *
 * Linux-specific **Markdown** here.
 *
 * </TabItem>
 * <TabItem value="macos" label="macOS">
 *
 * macOS-specific **Markdown** here.
 *
 * </TabItem>
 * </OsTabs>
 * ```
 */
export default function OsTabs({children}: OsTabsProps): ReactNode {
  return (
    <Tabs groupId="operating-system" queryString>
      {children}
    </Tabs>
  );
}
