# Fuse One Host: the household host on its own, as a small container.
#
# The image carries a Java runtime and the host's own build, nothing else: no UI, no emulators, no
# game library. It runs as an unprivileged user and keeps everything in /data. Build the host first
# (the Android SDK is needed to configure Fuse's shared modules), then the image from the repository:
#
#   ./gradlew :app:host:installDist
#   docker build -f tools/host/Dockerfile -t fuse-one-host .
FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="Fuse One Host" \
      org.opencontainers.image.description="The Fuse One household host: profiles, saves, library sync and transfers, without the Fuse app." \
      org.opencontainers.image.source="https://github.com/matiyaaa/fuse" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"
RUN groupadd --system --gid 10001 fuse \
 && useradd --system --uid 10001 --gid fuse --home-dir /data --shell /usr/sbin/nologin fuse \
 && mkdir -p /data && chown fuse:fuse /data
COPY app/host/build/install/fuse-one-host /opt/fuse-one-host
ENV PATH="/opt/fuse-one-host/bin:${PATH}" \
    FUSE_ONE_DATA=/data \
    FUSE_ONE_PORT=47311
USER fuse
VOLUME ["/data"]
EXPOSE 47311/tcp 47310/udp
HEALTHCHECK --interval=60s --timeout=10s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "fuse-one-host status > /dev/null"]
ENTRYPOINT ["fuse-one-host"]
CMD ["serve"]
