/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint

import java.net.InetAddress

import scala.concurrent.duration.{Duration, FiniteDuration}
import com.typesafe.config.{Config, ConfigFactory}
import org.log4s._


final case class MesosConfiguration(
  frameworkName: String,
  failoverTimeout: Duration,
  webUrl: String,
  connectionString: String,
  username: Option[String],
  role: Option[String],
  principal: Option[String],
  secret: Option[String]
)

final case class Endpoint(
  address: String,
  port: Int
) {
  def baseUrl: String = s"$address:$port"
}

final case class ApiConfiguration(
  bindEndpoint: Endpoint,
  advertisedEndpoint: Endpoint,
  timeout: FiniteDuration
)

final case class ZookeeperConfiguration(
  connectionString: String,
  node: String,
  timeout: Duration
)

final case class ContainerRunCleanupConfiguration(
  cleanupInterval: FiniteDuration,
  runRetention: FiniteDuration,
  maxInactiveRuns: Option[Int]
)

final case class SprintConfiguration(
  advertisedHostname: String,
  advertisedPort: Int
)

final case class Configuration(
  api: ApiConfiguration,
  mesos: MesosConfiguration,
  zookeeper: ZookeeperConfiguration,
  containerRunCleanup: ContainerRunCleanupConfiguration
)

object Configuration {

  import scala.language.implicitConversions
  import scala.concurrent.duration.{Duration => SDuration, FiniteDuration => SFiniteDuration}
  import java.time.{Duration => JDuration}

  private[this] val log = getLogger

  implicit private def asFiniteDuration(d: JDuration): SFiniteDuration = SDuration.fromNanos(d.toNanos)

  implicit private class RichConfig(cfg: Config) {
    def getStringOption(path: String): Option[String] = if (cfg.hasPath(path)) Some(cfg.getString(path)) else None
    def getIntOption(path: String): Option[Int] = if (cfg.hasPath(path)) Some(cfg.getInt(path)) else None
  }

  private lazy val config: Configuration = {
    val config = ConfigFactory.load

    val apiAdvertisedHostname = if (config.hasPath("sprint.api.advertised.address")) {
      config.getString("sprint.api.advertised.address")
    } else {
      InetAddress.getLocalHost.getHostName
    }

    val apiAdvertisedPort = if (config.hasPath("sprint.api.advertised.port")) {
      config.getInt("sprint.api.advertised.port")
    } else {
      config.getInt("sprint.api.bind.port")
    }

    val apiTimeout = config.getDuration("sprint.api.timeout")
    val apiBindAddress = config.getString("sprint.api.bind.address")
    val apiBindPort = config.getInt("sprint.api.bind.port")


    val mesosWebUrl = s"http://$apiAdvertisedHostname:$apiAdvertisedPort/v1/runs"
    val mesosFrameworkName = config.getString("mesos.framework.name")
    val mesosFailoverTimeout = config.getDuration("mesos.framework.failover-timeout")
    val mesosConnect = config.getString("mesos.connect")
    val mesosUsername = config.getStringOption("mesos.framework.username")
    val mesosPrincipal = config.getStringOption("mesos.framework.principal")
    val mesosSecret = config.getStringOption("mesos.framework.secret")
    val mesosRole = config.getStringOption("mesos.framework.role")

    val zkTimeout = config.getDuration("zookeeper.timeout")
    val zkConnectFull = config.getString("zookeeper.connect")
    val zkPattern = """zk://([^/]+)/(.+)""".r
    val (zkConnect, zkNode) = zkConnectFull match {
      case zkPattern(connect, node) => (connect, s"/$node")
      case _ =>
        log.error(s"zookeeper.connect has to be of the form 'zk://server1:port1,server2:port2/chroot, instead found $zkConnectFull")
        throw new IllegalArgumentException(zkConnectFull)
    }

    val containerCleanupRate = config.getDuration("sprint.container-run-cleanup.rate")
    val containerRetention = config.getDuration("sprint.container-run-cleanup.retention")
    val maxInactiveRuns = config.getIntOption("sprint.container-run-cleanup.max-inactive")

    Configuration(
      api = ApiConfiguration(
        bindEndpoint = Endpoint(apiBindAddress, apiBindPort),
        advertisedEndpoint = Endpoint(apiAdvertisedHostname, apiAdvertisedPort),
        apiTimeout
      ),
      mesos = MesosConfiguration(
        mesosFrameworkName,
        mesosFailoverTimeout,
        mesosWebUrl,
        mesosConnect,
        mesosUsername,
        mesosRole,
        mesosPrincipal, mesosSecret
      ),
      zookeeper = ZookeeperConfiguration(zkConnect, zkNode, zkTimeout),
      containerRunCleanup = ContainerRunCleanupConfiguration(containerCleanupRate, containerRetention, maxInactiveRuns)
    )
  }

  def apply(): Configuration = config
}
