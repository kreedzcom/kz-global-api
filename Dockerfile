FROM gradle:9.5.1-jdk25 AS build
WORKDIR /app
COPY . .
RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache curl \
    && addgroup -S kzapi && adduser -S -G kzapi kzapi
WORKDIR /app
COPY --from=build /app/build/libs/kz-global-api.jar app.jar
RUN chown kzapi:kzapi app.jar
USER kzapi
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/ready || exit 1
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "app.jar"]
