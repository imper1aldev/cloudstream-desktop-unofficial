FROM eclipse-temurin:21-jre AS base
RUN apt-get update && apt-get install -y \
    libnss3 libnspr4 libatk-bridge2.0-0 libdrm2 libxkbcommon0 \
    libxcomposite1 libxdamage1 libxrandr2 libgbm1 libpango-1.0-0 \
    libcairo2 libasound2 \
    && rm -rf /var/lib/apt/lists/*

FROM base AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties .
COPY server-api server-api
COPY plugin-runtime plugin-runtime
COPY android-stubs android-stubs
COPY common common
COPY android-reference android-reference
RUN ./gradlew :server-api:installDist --no-daemon

FROM base AS runtime
COPY --from=build /app/server-api/build/install/server-api /opt/server
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
ENV BIND_ADDRESS=0.0.0.0
ENV PORT=8080
ENV DATA_DIR=/data
EXPOSE 8080
VOLUME /data
CMD ["/opt/server/bin/server-api"]
