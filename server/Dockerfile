FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S omnisign && adduser -S omnisign -G omnisign

COPY server/build/libs/*-all.jar app.jar

# Pre-create the writable runtime directories and hand them to the non-root user. /app
# itself stays root-owned, so the server (running as omnisign) cannot create them on its own:
#  - /app/config: operator-supplied server.yml / signing.yml / source cert files.
#  - /app/trusted-certs: the trust store reconciled at boot from signing.yml
#    (content-addressed DER files + a CBOR index). This is where the default relative
#    trustStoreDir resolves under WORKDIR /app, so the store works with no server.yml change.
#  - /app/data: per-user state rooted at $HOME (see ENV HOME below).
RUN chown omnisign:omnisign app.jar \
 && mkdir -p /app/config /app/trusted-certs /app/data \
 && chown omnisign:omnisign /app/config /app/trusted-certs /app/data

USER omnisign

ENV OMNISIGN_SERVER_CONFIG=/app/config/server.yml

# The server's per-user state — the refresh-token / PKCE SQLite database and the PKCS#11
# drop directory — resolves under $HOME (~/.config/omnisign/...). Point $HOME at a writable,
# persistent volume so it survives container recreation and the non-root user can write it,
# instead of landing in a root-owned or ephemeral path.
ENV HOME=/app/data

# Persist operator config, the reconciled trust store, and per-user state across restarts.
VOLUME ["/app/config", "/app/trusted-certs", "/app/data"]

EXPOSE 18080
EXPOSE 18443

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:18080/api/v1/health || exit 1

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
