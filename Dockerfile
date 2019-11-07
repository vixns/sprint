FROM openjdk:11-stretch
ENV SCALA_VERSION 2.12.8
ENV SBT_VERSION 1.2.8

RUN \
  curl -fsL https://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz | tar xfz - -C /root/ && \
  echo >> /root/.bashrc && \
  echo "export PATH=~/scala-$SCALA_VERSION/bin:$PATH" >> /root/.bashrc

# Install sbt
RUN \
  curl -L -o sbt-$SBT_VERSION.deb https://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb && \
  dpkg -i sbt-$SBT_VERSION.deb

COPY . /src
WORKDIR /src
RUN sbt assembly
RUN mv /src/target/scala-2.12/sprint-assembly*jar /sprint.jar

FROM openjdk:11-jre
ENV ZOOKEEPER_CONNECT=zk://zookeeper:2181/sprint \
MESOS_CONNECT=zk://zookeeper:2181/mesos \
MESOS_NATIVE_LIBRARY=/usr/lib/libmesos.so \
MESOS_NATIVE_JAVA_LIBRARY=/usr/lib/libmesos.so
RUN \
export DEBIAN_FRONTEND=noninteractive \
&& echo "deb http://http.debian.net/debian stretch-backports contrib non-free main" >> /etc/apt/sources.list \
&& echo "deb http://http.debian.net/debian buster contrib non-free main" >> /etc/apt/sources.list \
&& apt-get update \
&& apt-get install -y libsasl2-modules libsvn1 libcurl4 curl \
libevent-2.1.6 libevent-openssl-2.1.6 libevent-pthreads-2.1.6
COPY bin/run.sh /run.sh
COPY --from=0 /sprint.jar /sprint.jar
ENTRYPOINT ["/run.sh"]
