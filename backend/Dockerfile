# Backend image for Render (Render has no native Java runtime, so it builds this remotely --
# Docker is not needed on the developer machine).
#
# Build context is backend/ (see render.yaml: dockerContext).

# ---- build stage: full JDK, compiles and packages the executable jar ---------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Wrapper and build scripts first, so the Gradle distribution download is its own cached layer and
# is not repeated when only source code changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# Invoked through `sh` rather than ./gradlew: this repo is developed on Windows, where the
# executable bit is not tracked by the filesystem (see CLAUDE.md section 9). Same reason CI does it.
RUN sh gradlew --no-daemon --version

COPY src src
# Tests are not run here. They need an embedded PostgreSQL binary and belong in CI
# (.github/workflows/ci-backend.yml); the image build only has to package what CI already verified.
RUN sh gradlew --no-daemon bootJar -x test

# ---- runtime stage: JRE only, no compiler, no Gradle, no source ---------------------------------
FROM eclipse-temurin:17-jre
# Never run as root inside the container.
RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app
WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar app.jar

# The application reads PORT (server.port: ${PORT:8080}); Render injects it at runtime.
# JVM flags come from the JAVA_TOOL_OPTIONS environment variable, which the JVM reads on its own.
ENTRYPOINT ["java", "-jar", "app.jar"]
