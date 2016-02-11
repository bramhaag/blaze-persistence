#!/bin/bash

if [ "$JDK" = "9" ]  || ["$LATEST_MAVEN" = "true"]; then
  MVN_BIN=/tmp/apache-maven/bin/mvn
  export MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=512m"
else
  MVN_BIN=mvn
fi

if [ "$JDK" = "9" ]; then
  export JAVA_HOME="/usr/lib/jvm/java-9-oracle/"
fi

${MVN_BIN} -version
exec ${MVN_BIN} -P ${JPAPROVIDER},${RDBMS} install
