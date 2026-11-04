FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-libtorrent ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY seeder/generate_fixtures.py seeder/tracker.py seeder/seeder.py seeder/entrypoint.sh /opt/seeder/
COPY fixtures/manifest.json /opt/seeder/manifest.json

RUN chmod +x /opt/seeder/entrypoint.sh

ENTRYPOINT ["/opt/seeder/entrypoint.sh"]
