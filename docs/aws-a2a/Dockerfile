# Builds the repository's Java agents and bank demo owner on Linux.
FROM debian:bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl gnucobol maven openjdk-17-jdk-headless python3 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
# The owner JDBC adapters currently discard URI query options.
RUN python3 docs/aws-a2a/prepare-build.py
RUN mkdir -p build \
 && cobc -x -fixed -Wall -I app/cpy app/cbl/LIRLBAT.cbl app/cbl/LIRL01C.cbl -o build/lirlbatch \
 && cobc -x -fixed -Wall -I app/cpy app/cbl/LIBAT01.cbl app/cbl/LIUW01C.cbl -o build/liuwbatch \
 && cobc -x -fixed -Wall -I app/cpy app/cbl/LIMBAT01.cbl app/cbl/LIMK01C.cbl -o build/limkbatch \
 && cobc -x -fixed -Wall -I app/cpy app/cbl/LICBAT01.cbl app/cbl/LICB01C.cbl -o build/licbbatch \
 && cobc -x -fixed -Wall -I app/cpy app/cbl/LIOBAT01.cbl app/cbl/LIOF01C.cbl -o build/liofbatch \
 && cobc -x -fixed -Wall -I app/cpy app/cbl/LIEXBAT.cbl app/cbl/LIEX01C.cbl -o build/liexbatch \
 && mvn -B -q -Dmaven.test.skip=true -f services/pom.xml package \
 && mvn -B -q -Dmaven.test.skip=true -f marketing-service/pom.xml package \
 && mvn -B -q -Dmaven.test.skip=true -f ai-service/pom.xml package \
 && curl --fail --silent --show-error --location --retry 3 \
      https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /src/rds-bundle.pem \
 && java -cp services/target/customer-engine-1.0.0.jar com.lending.engine.infrastructure.RuntimeCheck

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl gnucobol iproute2 openjdk-17-jre-headless \
    postgresql-client procps python3 socat tini util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home ogm
WORKDIR /opt/ogm
COPY --from=build /src/rds-bundle.pem /etc/ssl/certs/ogm-rds-bundle.pem
COPY --from=build /src/build/ build/
COPY --from=build /src/app/data/ app/data/
COPY --from=build /src/app/data/policies/ policy-documents/
COPY --from=build /src/ai-service/config/mcp-servers.json ai-service/config/mcp-servers.json
COPY --from=build /src/ai-service/target/ai-simulation-1.0.0.jar ai-service/target/
COPY --from=build /src/marketing-service/target/marketing-offers-1.0.0.jar marketing-service/target/
COPY --from=build /src/services/target/customer-engine-1.0.0.jar services/target/
COPY --from=build /src/scripts/ scripts/
COPY --from=build /src/docs/aws-a2a/boot.py /src/docs/aws-a2a/bridge.py cloud/
RUN mkdir runtime && chown -R 10001:10001 runtime policy-documents
USER 10001
ENTRYPOINT ["/usr/bin/tini", "--", "python3", "/opt/ogm/cloud/boot.py"]
