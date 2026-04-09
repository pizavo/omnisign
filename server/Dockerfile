FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

RUN addgroup -S omnisign && adduser -S omnisign -G omnisign

COPY server/build/libs/*-all.jar app.jar

RUN chown omnisign:omnisign app.jar && mkdir -p /app/config && chown omnisign:omnisign /app/config

USER omnisign

ENV OMNISIGN_SERVER_CONFIG=/app/config/server.yml

VOLUME ["/app/config"]

EXPOSE 50080
EXPOSE 50443

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:50080/api/v1/health || exit 1

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
