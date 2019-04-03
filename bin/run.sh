#!/bin/bash

set -euo pipefail

JAVA_OPTS=${JAVA_OPTS:-}
JMX_OPTS=${JMX_OPTS:-}

exec java $JAVA_OPTS $JMX_OPTS -jar /sprint.jar
