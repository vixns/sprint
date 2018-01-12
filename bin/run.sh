#!/bin/bash

set -euo pipefail

JAVA_OPTS=${JAVA_OPTS:-}
JMX_OPTS=${JMX_OPTS:-}
SPRINT_OPTS=${SPRINT_OPTS:-}

if [ ! -z ${HOST+x} ] && [ ! -z ${PORT_API+x} ]; then
  SPRINT_OPTS="-Dsprint.api.advertised.address=$HOST -Dsprint.api.advertised.port=$PORT_API $SPRINT_OPTS"
fi

if [ ! -z ${HOST+x} ] && [ ! -z ${PORT_MESOS+x} ]; then
  export LIBPROCESS_ADVERTISE_IP=$(getent hosts $HOST | head -1 | awk '{ print $1 }')
  export LIBPROCESS_ADVERTISE_PORT=$PORT_MESOS
  export LIBPROCESS_PORT=$PORT_MESOS
fi

if [ ! -z ${HOST+x} ] && [ ! -z ${PORT_JMX+x} ]; then
  export JMX_OPTS=`
  `" -Dcom.sun.management.jmxremote"`
  `" -Dcom.sun.management.jmxremote.local.only=false"`
  `" -Dcom.sun.management.jmxremote.authenticate=false"`
  `" -Dcom.sun.management.jmxremote.ssl=false"`
  `" -Dcom.sun.management.jmxremote.port=$PORT_JMX"`
  `" -Dcom.sun.management.jmxremote.rmi.port=$PORT_JMX"`
  `" -Djava.rmi.server.hostname=$HOST"`
  `" $JMX_OPTS"
fi

exec java $JAVA_OPTS $JMX_OPTS $SPRINT_OPTS -cp "/opt/sprint/lib/*" com.adform.sprint.Main
