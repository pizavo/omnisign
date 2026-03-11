FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

COPY server/build/libs/*-all.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
