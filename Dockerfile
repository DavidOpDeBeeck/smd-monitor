# The page, bundled by Vite where Gradle expects it: build/generated/monitor-ui.
FROM node:22-slim AS ui
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# The hub's jar, with the page bundled above instead of one Gradle builds with npm.
FROM eclipse-temurin:25-jdk AS hub
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
COPY --from=ui /build/build/generated/monitor-ui build/generated/monitor-ui
RUN sh gradlew --no-daemon --console=plain bootJar -x installMonitorUi -x buildMonitorUi \
    && mv build/libs/smd-monitor-hub-*.jar hub.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system hub && useradd --system --gid hub --no-create-home hub
WORKDIR /opt/smd-monitor-hub
COPY --from=hub /build/hub.jar hub.jar
USER hub
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080 4318
ENTRYPOINT ["java", "-jar", "hub.jar"]
