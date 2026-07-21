# OmniSign Documentation

The OmniSign user documentation, built with [Docusaurus](https://docusaurus.io/). It hosts the
guides for the **Desktop**, **Web**, **Server**, and **CLI** surfaces and is published at
**[pizavo.github.io/omnisign](https://pizavo.github.io/omnisign/)**.

## Prerequisites

- **Node.js 26+** and **[pnpm](https://pnpm.io/)** — the repo pins `pnpm@11` via the `packageManager`
  field, so running `corepack enable` once selects the correct pnpm version automatically.

## Installation

```bash
pnpm install
```

## Local development

```bash
pnpm start
```

Starts a local development server and opens a browser window. Most changes are reflected live
without restarting the server.

## Build

```bash
pnpm build
```

Generates static content into the `build/` directory, servable by any static host. The build runs
with `onBrokenLinks: 'throw'`, so a broken link or anchor fails the build — run it before pushing
documentation changes.

## API references

**KDoc (code API)** — shown under `/api/`, generated separately from the Kotlin sources with
`./gradlew :dokkaGenerate` (from the repository root) and copied into `static/api/` during the CI
build. It is **not** produced by `pnpm build`.

**HTTP API (OpenAPI)** — the server's REST reference under `/server/api/` is generated from
[`static/openapi.yaml`](static/openapi.yaml) by `docusaurus-plugin-openapi-docs`, via the `generate`
step that runs automatically before `pnpm start` / `pnpm build`. Edit the spec, not the generated
`docs-server/api/` pages, which are git-ignored and rebuilt on every run.

## Deployment

Deployment is automatic. The **Deploy Docs — GitHub Pages** workflow
(`.github/workflows/deploy-docs.yml`) rebuilds and publishes the site to GitHub Pages on every push
to `main` that touches `docs/**` or any module's `src/**` (and can also be triggered manually via
*workflow_dispatch*). No manual deploy step is required.
