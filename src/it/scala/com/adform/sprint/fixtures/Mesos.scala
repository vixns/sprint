package com.adform.sprint
package fixtures

import ContainerStatus._
import model.serialization.JsonFormatters

import com.spotify.docker.client.messages._
import net.liftweb.json.{JString, parse}
import org.scalatest._
import org.scalatest.concurrent.Eventually
import scalaj.http.Http
import org.log4s._
import java.net.ConnectException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._


trait MesosContainer extends ContainerWithEndpoint with JsonFormatters {
  def isUp: Boolean = try {
    Http(s"http://$endpoint/state.json").asString.isNotError
  } catch {
    case _: ConnectException => false
    case _: java.net.SocketTimeoutException => false
  }
}

case class MesosMaster(id: String, name: String, ip: String, port: Int) extends MesosContainer {
  def isLeader: Boolean = try {
    Http(s"http://$endpoint/state.json").asString.isSuccess
  } catch {
    case _: ConnectException => false
    case _: java.net.SocketTimeoutException => false
  }

  def tasksIds: Option[List[String]] = try {
    val response = Http(s"http://$endpoint/state.json").asString
    if (response.isSuccess) {
      val respBody = response.body
      val ids = parse(respBody) \ "frameworks" \ "tasks" \\ "container_id" \\ "value" \ classOf[JString]
      Some(ids)
    } else None
  } catch {
    case _: ConnectException => None
    case _: java.net.SocketTimeoutException => None
  }
}

case class MesosSlave(id: String, name: String, ip: String, port: Int) extends MesosContainer

case class MesosInstance(masters: List[MesosMaster], slaves: List[MesosSlave])

object Mesos

trait Mesos extends BeforeAndAfterAll with BeforeAndAfterEach {
  this: Suite with Matchers with Eventually with Docker =>

  private[this] val log = getLogger

  private val zookeeperImage = BuildInfo.zookeeperImage
  private val mesosMasterImage = BuildInfo.mesosMasterImage
  private val mesosSlaveImage = BuildInfo.mesosSlaveImage

  private val mesosMasterPort = 5050
  private val mesosSlavePort = 5051
  private val zookeeperPort = 2181

  private val masterPortCounter: AtomicInteger = new AtomicInteger(mesosMasterPort+100)
  private val slavePortCounter: AtomicInteger = new AtomicInteger(mesosSlavePort+200)

  val mesosMasterCount = 1
  val mesosSlaveCount = 1

  var mesos: MesosInstance = _
  var zookeeper: ContainerWithEndpoint = _

  override def beforeAll(): Unit = {

    super.beforeAll()

    Mesos.synchronized {
      ensureImage(zookeeperImage)
      ensureImage(mesosMasterImage)
      ensureImage(mesosSlaveImage)
    }

    zookeeper = createZookeeper()
    mesos = MesosInstance(
      masters = (1 to mesosMasterCount).map(_ => createMesosMaster(zookeeper)).toList,
      slaves = (1 to mesosSlaveCount).map(_ => createMesosSlave(zookeeper)).toList
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    ensureMesosRunning()
  }


  def createZookeeper(): ContainerWithEndpoint = {
    val zookeeperName = s"${dockerSandboxId}_zookeeper"
    val zookeeperDef = ContainerConfig.builder()
      .image(zookeeperImage)
      .hostConfig(HostConfig.builder()
        .networkMode(network.id)
        .portBindings(Map(zookeeperPort.toString -> List(PortBinding.of(network.ip, zookeeperPort)).asJava).asJava)
        .build()
      )
      .exposedPorts(Set(zookeeperPort.toString).asJava)
      .build()

    val zookeeperContainer = docker.createContainer(zookeeperDef, zookeeperName)
    docker.startContainer(zookeeperContainer.id)
    GenericContainer(zookeeperContainer.id, zookeeperName, network.ip, zookeeperPort)
  }

  def createMesosMaster(zookeeper: ContainerWithEndpoint): MesosMaster = {
    val mesosMasterName = s"${dockerSandboxId}_mesos-master-${UUID.randomUUID().toString.take(4)}"
    val networkPort = masterPortCounter.getAndAdd(1)
    val mesosMasterDef = ContainerConfig.builder()
      .hostConfig(HostConfig.builder()
        .networkMode(network.id)
        .links(zookeeper.id)
        .portBindings(Map(mesosMasterPort.toString -> List(PortBinding.of(network.ip, networkPort)).asJava).asJava)
        .build()
      )
      .image(mesosMasterImage)
      .exposedPorts(Set(mesosMasterPort.toString).asJava)
      .env(List(
        s"MESOS_ZK=zk://${zookeeper.name}:${zookeeper.port}/mesos",
        "MESOS_QUORUM=1",
        "MESOS_WORK_DIR=/var/tmp/mesos"
      ).asJava)
      .build()

    val mesosMasterContainer = docker.createContainer(mesosMasterDef, mesosMasterName)
    docker.startContainer(mesosMasterContainer.id)
    MesosMaster(mesosMasterContainer.id, mesosMasterName, network.ip, networkPort)
  }

  def createMesosSlave(zookeeper: ContainerWithEndpoint): MesosSlave = {
    val mesosSlaveName = s"${dockerSandboxId}_mesos-slave-${UUID.randomUUID().toString.take(4)}"
    val networkPort = slavePortCounter.getAndAdd(1)
    val mesosSlaveDef = ContainerConfig.builder()
      .hostConfig(HostConfig.builder()
        .networkMode(network.id)
        .links(zookeeper.id)
        .portBindings(Map(mesosSlavePort.toString -> List(PortBinding.of(network.ip, networkPort)).asJava).asJava)
        .binds(List(
          "/sys/fs/cgroup:/sys/fs/cgroup",
          "/var/run/docker.sock:/var/run/docker.sock"
        ).asJava)
        .build()
      )
      .image(mesosSlaveImage)
      .exposedPorts(Set(mesosSlavePort.toString).asJava)
      .env(List(
        s"MESOS_MASTER=zk://${zookeeper.name}:${zookeeper.port}/mesos",
        "MESOS_WORK_DIR=/var/tmp/mesos",
        "MESOS_SYSTEMD_ENABLE_SUPPORT=false",
        "MESOS_CONTAINERIZERS=docker"
      ).asJava)
      .build()

    val mesosSlaveContainer = docker.createContainer(mesosSlaveDef, mesosSlaveName)
    docker.startContainer(mesosSlaveContainer.id)
    MesosSlave(mesosSlaveContainer.id, mesosSlaveName, network.ip, networkPort)
  }

  def ensureMesosRunning(): Unit = {
    def ensureContainerRunning(container: MesosContainer): Unit = {
      val id = container.id

      def ensureRunning = eventually { getContainerStatus(id) shouldBe Some(Running) }

      def ensureUp = eventually { container.isUp shouldBe true }

      getContainerStatus(id) match {
        case Some(Paused) =>
          docker.unpauseContainer(id)
          ensureRunning
          ensureUp
        case Some(Exited) =>
          docker.startContainer(id)
          ensureRunning
          ensureUp
        case Some(Restarting) =>
          ensureRunning
          ensureUp
        case Some(Running) =>
          ensureUp
        case _ =>
      }
    }

    mesos.masters.foreach(ensureContainerRunning(_))
    eventually { mesos.masters.exists(_.isLeader) shouldBe true }
    mesos.slaves.foreach(ensureContainerRunning(_))
  }

  def removeMesosTasks(): Unit = {
    log.debug(s"Cleaning up containers ran by mesos")
    for {
      leader      <- mesos.masters.find(_.isLeader)
      tasksIds    <- leader.tasksIds
      taskId      <- tasksIds
      containerId <- getContainerId(s"mesos-$taskId")
    } {
      log.debug(s"Stopping and removing container $containerId")
      stopAndRemoveContainer(containerId)
    }
  }

  override def afterEach(): Unit = try {
    removeMesosTasks()
  } finally {
    super.afterEach()
  }

  override def afterAll(): Unit = try {
    log.debug("Cleaning up mesos containers")

    if (mesos != null) {
      mesos.masters.foreach(m => stopAndRemoveContainer(m.id))
      mesos.slaves.foreach(s => stopAndRemoveContainer(s.id))
    }

    if (zookeeper != null)
      stopAndRemoveContainer(zookeeper.id)

  } finally {
    super.afterAll()
  }
}
