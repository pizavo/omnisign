/*
 * The `mupdf` npm package ships a single ESM build that targets both Node.js
 * and browsers. Its Node-only branch — `node_fs = await import("node:fs")`
 * inside a `typeof process !== "undefined"` guard — is dead code in the
 * browser, but webpack still statically analyzes the dynamic import and the
 * sibling `require("module")` call and reports them as build errors. The
 * runtime is unaffected; this config silences the noise.
 */
const webpack = require("webpack");

config.plugins = config.plugins || [];
config.plugins.push(
    new webpack.IgnorePlugin({
        resourceRegExp: /^node:/,
    })
);

config.resolve = config.resolve || {};
config.resolve.fallback = {
    ...config.resolve.fallback,
    module: false,
};
