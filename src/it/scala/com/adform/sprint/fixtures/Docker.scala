package com.adform.sprint
package fixtures

import java.util.UUID
import scala.collection.JavaConverters._
import org.log4s._
import org.scalatest.{BeforeAndAfterAll, Suite}
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.{ListContainersParam, RemoveContainerParam}
import com.spotify.docker.client.messages.{NetworkConfig, Container => SContainer}


case class DockerNetwork(id: String, name: String, ip: String)

trait Docker extends BeforeAndAfterAll { this: Suite =>

  private[this] val log = getLogger

  val docker: DefaultDockerClient = DefaultDockerClient.fromEnv().build()

  val dockerSandboxId: String = UUID.randomUUID().toString
  var network: DockerNetwork = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val networkName = s"${dockerSandboxId}_network"
    val networkId = docker.createNetwork(NetworkConfig.builder().name(networkName).build()).id()
    val ip = docker.inspectNetwork(networkId).ipam().config().asScala.head.gateway().split('/').head
    network = DockerNetwork(networkId, networkName, ip)

    log.info(s"Created docker network $networkId on $ip")
  }

  override def afterAll(): Unit = {
    try {
      if (network != null) {
        log.debug(s"Removing docker network ${network.id}")
        docker.removeNetwork(network.id)
      }
    } finally {
      super.afterAll()
    }
  }

  def listSandboxContainers(): Seq[SContainer] = {
    docker.listContainers().asScala.filter(c => c.names().asScala.exists(n => n.contains(dockerSandboxId)))
  }

  def ensureImage(image: String): Unit = {
    val images = docker.listImages().asScala.filter(_.repoTags() != null).flatMap(_.repoTags().asScala)
    if (!images.contains(image) && !images.contains(s"docker.io/$image")) {
      log.info(s"Pulling image $image")
      docker.pull(image)
    }
  }

  def getContainerStatus(containerId: String): Option[ContainerStatus] =
    ContainerStatus.fromString(docker.inspectContainer(containerId).state().status())

  def getContainerId(containerName: String): Option[String] = {
    val containers = docker.listContainers(ListContainersParam.allContainers(true))

    containers.asScala.toList.find { c =>
      val names = c.names().asScala.toList
      names.exists(_.startsWith(s"/$containerName"))
    }.map(_.id)
  }

  def stopAndRemoveContainer(containerId: String): Unit = {
    docker.stopContainer(containerId, 2)
    docker.removeContainer(containerId, RemoveContainerParam.removeVolumes())
  }
}

// https://docs.docker.com/edge/engine/reference/commandline/ps/
trait ContainerStatus
object ContainerStatus extends ContainerStatus {
  case object Created     extends ContainerStatus
  case object Restarting  extends ContainerStatus
  case object Running     extends ContainerStatus
  case object Removing    extends ContainerStatus
  case object Paused      extends ContainerStatus
  case object Exited      extends ContainerStatus
  case object Dead        extends ContainerStatus

  def fromString(status: String): Option[ContainerStatus] = status match {
    case "created"    => Some(Created)
    case "restarting" => Some(Restarting)
    case "running"    => Some(Running)
    case "removing"   => Some(Removing)
    case "paused"     => Some(Paused)
    case "exited"     => Some(Exited)
    case "dead"       => Some(Dead)
    case _            => None
  }
}

trait Container {
  def id: String
  def name: String
}

trait ContainerWithEndpoint extends Container {
  def ip: String
  def port: Int
  def endpoint: String = s"$ip:$port"
}

case class GenericContainer(id: String, name: String, ip: String, port: Int) extends ContainerWithEndpoint
