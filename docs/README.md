# OmniSign Documentation

The OmniSign user documentation, built with [Docusaurus](https://docusaurus.io/). It hosts the
guides for the **Desktop**, **Web**, **Server**, and **CLI** surfaces and is published at
**[pizavo.github.io/omnisign](https://pizavo.github.io/omnisign/)**.

## Installation

```bash
npm install
```

## Local development

```bash
npm start
```

Starts a local development server and opens a browser window. Most changes are reflected live
without restarting the server.

## Build

```bash
npm run build
```

Generates static content into the `build/` directory, servable by any static host. The build runs
with `onBrokenLinks: 'throw'`, so a broken link or anchor fails the build — run it before pushing
documentation changes.

## API reference

The KDoc/API reference shown under `/api/` is generated separately from the Kotlin sources with
`./gradlew :dokkaGenerate` (from the repository root) and copied into `static/api/` during the CI
build. It is not produced by `npm run build`.

## Deployment

Deployment is automatic. The **Deploy Docs — GitHub Pages** workflow
(`.github/workflows/deploy-docs.yml`) rebuilds and publishes the site to GitHub Pages on every push
to `main` that touches `docs/**` or any module's `src/**` (and can also be triggered manually via
*workflow_dispatch*). No manual deploy step is required.
