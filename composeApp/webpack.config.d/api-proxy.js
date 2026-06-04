/*
 * Forwards `/api/*` from the webpack-dev-server origin (http://localhost:8080)
 * to the OmniSign Ktor server. Keeps the wasm app fetching same-origin in dev,
 * matching the production deployment topology where the server hosts both the
 * web bundle and the API.
 *
 * Override the target with the OMNISIGN_DEV_API_TARGET environment variable
 * when the Ktor server is bound to a non-default port or host.
 */
const apiTarget = process.env.OMNISIGN_DEV_API_TARGET || "http://localhost:18080";

if (config.devServer) {
    config.devServer.proxy = config.devServer.proxy || [];
    config.devServer.proxy.push({
        context: ["/api"],
        target: apiTarget,
        changeOrigin: true,
    });
}
