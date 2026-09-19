#!/usr/bin/env bash
# Compiles the COBOL adapters, packages both Java applications and runs their default tests and JDBC packaging
# check.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
cobc -x -fixed -Wall -I app/cpy app/cbl/LIRLBAT.cbl app/cbl/LIRL01C.cbl -o build/lirlbatch
cobc -x -fixed -Wall -I app/cpy app/cbl/LIBAT01.cbl app/cbl/LIUW01C.cbl -o build/liuwbatch
cobc -x -fixed -Wall -I app/cpy app/cbl/LIMBAT01.cbl app/cbl/LIMK01C.cbl -o build/limkbatch
cobc -x -fixed -Wall -I app/cpy app/cbl/LICBAT01.cbl app/cbl/LICB01C.cbl -o build/licbbatch
cobc -x -fixed -Wall -I app/cpy app/cbl/LIOBAT01.cbl app/cbl/LIOF01C.cbl -o build/liofbatch
cobc -x -fixed -Wall -I app/cpy app/cbl/LIEXBAT.cbl app/cbl/LIEX01C.cbl -o build/liexbatch
mvn -q -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -f marketing-service/pom.xml clean package
mvn -q -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -f services/pom.xml clean package
java -cp services/target/customer-engine-1.0.0.jar com.lending.engine.infrastructure.RuntimeCheck
