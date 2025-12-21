# Use alpine instead of curlimages/curl to avoid permission issues
FROM alpine:latest

RUN apk add --no-cache curl bash

COPY setup-kibana.sh /setup-kibana.sh
RUN chmod +x /setup-kibana.sh

ENTRYPOINT ["/bin/bash", "/setup-kibana.sh"]
