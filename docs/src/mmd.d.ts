/// <reference types="@docusaurus/theme-mermaid" />

/**
 * Raw Mermaid sources under `diagrams/` are imported as strings by the
 * `mmd-source-loader` plugin in `docusaurus.config.ts`, which registers them
 * as webpack `asset/source` modules.
 */
declare module '*.mmd' {
  const content: string;
  export default content;
}
