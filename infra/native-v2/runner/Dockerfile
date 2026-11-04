FROM python:3.12-slim

RUN pip install --no-cache-dir requests==2.32.3

COPY runner/acceptance.py runner/run-acceptance.sh /opt/acceptance/
RUN chmod +x /opt/acceptance/run-acceptance.sh

ENTRYPOINT ["/opt/acceptance/run-acceptance.sh"]
