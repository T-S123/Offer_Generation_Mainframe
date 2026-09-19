# Builds the lightweight TCP relay that lets WSL services reach Windows PostgreSQL.
FROM alpine:3.22
RUN apk add --no-cache socat
USER nobody
ENTRYPOINT ["socat"]
