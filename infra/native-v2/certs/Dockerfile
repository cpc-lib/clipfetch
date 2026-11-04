FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*

COPY certs/generate-certs.sh /usr/local/bin/generate-certs.sh
RUN chmod +x /usr/local/bin/generate-certs.sh

ENTRYPOINT ["/usr/local/bin/generate-certs.sh"]
