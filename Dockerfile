# Build context is the repository root: docker build -t finora-ai .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
COPY frontend frontend
COPY backend/src backend/src
RUN mvn -B -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/backend/target/finora-ai.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
