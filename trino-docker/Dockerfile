FROM python:3.8-slim

WORKDIR /app
COPY trino-module.py trino-module.py
COPY trino-docker/trino-proxy-356 trino-proxy
ENV FORMAT "dummy format"
ENV DATAPATH "dummy datapath"
ENV NAME "dummy data asset name"

RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir pyyaml

# Install OpenJDK-11
RUN apt-get update && \
    apt-get install -y openjdk-11-jre-headless && \
    apt-get clean;

COPY trino-docker/requirements.txt /tmp/requirements.txt
RUN python3 -m pip install --no-cache-dir -r /tmp/requirements.txt

# ENTRYPOINT ["tail", "-f", "/dev/null"]
ENTRYPOINT ["python3"]
CMD ["trino-module.py"]
