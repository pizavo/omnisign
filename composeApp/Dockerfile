# OmniSign web (Compose Multiplatform / Wasm) — static bundle served by nginx.
#
# Mirrors server/Dockerfile's "copy a pre-built artifact" approach: the Wasm
# distribution must already be built (on the host or in CI) before this image is
# built, exactly as the server image copies a pre-built fat JAR rather than
# compiling inside the container.
#
#   ./gradlew :composeApp:wasmJsBrowserDistribution
#   docker build -f composeApp/Dockerfile -t omnisign-web .   # build context = repo root
#
# The server the bundle talks to is baked in at *build time* of the Wasm
# distribution via the OMNISIGN_SERVER_URL env var (see composeApp/build.gradle.kts
# -> generateBuildConfig). The default is empty, which means "same origin": serve
# this bundle behind the same origin as the server's /api/v1 routes (e.g. a shared
# reverse proxy), or rebuild the distribution with OMNISIGN_SERVER_URL set to the
# API origin before building this image.
#
# The unprivileged nginx image runs as a non-root user (uid 101) and listens on
# 8080, matching the non-root security posture of server/Dockerfile.
FROM nginxinc/nginx-unprivileged:alpine

# Server block tuned for a Wasm single-page app: forces the application/wasm media
# type, enables gzip, and sets cache headers. Dropped into conf.d, which the base
# image's nginx.conf includes inside its http block.
COPY composeApp/nginx.conf /etc/nginx/conf.d/default.conf

# The pre-built Wasm distribution: index.html, composeApp.js, hashed *.wasm,
# composeResources/, mupdf-shim.js, styles.css, omnisign-logo.png.
COPY composeApp/build/dist/wasmJs/productionExecutable/ /usr/share/nginx/html/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/healthz || exit 1
