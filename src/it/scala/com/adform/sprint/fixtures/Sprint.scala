package com.adform.sprint
package fixtures

import model.{ContainerRunDefinition, ContainerRunState}
import model.serialization.JsonFormatters

import org.scalatest.{Matchers, Suite}
import org.scalatest.concurrent.Eventually
import com.spotify.docker.client.messages.{ContainerConfig, HostConfig, PortBinding}
import spray.json._
import scalaj.http.Http
import scala.collection.JavaConverters._
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger


case class SprintContainer(id: String, name: String, ip: String, port: Int) extends ContainerWithEndpoint with JsonFormatters {

  def isLeader: Boolean = try {
    Http(s"http://$endpoint/status/leading").asString.body.toBoolean
  } catch {
    case _: ConnectException => false
    case _: java.net.SocketTimeoutException => false
    case _: IllegalArgumentException => false
  }

  def isUp: Boolean = try {
    Http(s"http://$endpoint/status/ping").asString.isSuccess
  } catch {
    case _: ConnectException => false
    case _: java.net.SocketTimeoutException => false
  }

  def postJob(jobDef: ContainerRunDefinition): Option[String] = try {
    val resp = Http(s"http://$endpoint/v1/runs")
      .postData(jobDef.toJson.toString())
      .header("content-type", "application/json")
      .asString

    if (resp.isSuccess) {
      val jsObject = resp.body.parseJson.asJsObject
      val jobIdOption = jsObject.getFields("id").headOption
      jobIdOption.map(_.convertTo[String])
    } else None
  } catch {
    case _: ConnectException => None
    case _: java.net.SocketTimeoutException => None
  }

  def jobExists(jobId: String): Boolean = try {
    Http(s"http://$endpoint/v1/runs/$jobId").asString.isSuccess
  } catch {
    case _: ConnectException => false
    case _: java.net.SocketTimeoutException => false
  }

  def getJobState(jobId: String): Option[ContainerRunState] = try {
    val resp = Http(s"http://$endpoint/v1/runs/$jobId").asString

    if (resp.isSuccess) {
      val jsObject = resp.body.parseJson.asJsObject
      val stateOption = jsObject.getFields("state").headOption
      stateOption.flatMap(state => ContainerRunState.fromString(state.convertTo[String]))
    } else None
  } catch {
    case _: ConnectException => None
    case _: java.net.SocketTimeoutException => None
  }

}

trait SprintBuilder { this: Suite with Matchers with Eventually with Docker with Mesos =>

  private val sprintPort = 9090
  private val portCounter: AtomicInteger = new AtomicInteger(sprintPort)

  def withSprintInstance(testCode: SprintContainer => Any): Unit = {
    val newPort = portCounter.getAndAdd(1)
    withSprintInstance(onPort = newPort)(testCode)
  }

  def withSprintInstance(onPort: Int)(testCode: SprintContainer => Any): Unit = {
    var containerId: String = ""
    try {
      val sprintName = s"${dockerSandboxId}_sprint_$onPort"
      val sprintDef = ContainerConfig.builder()
        .hostConfig(HostConfig.builder()
          .networkMode(network.id)
          .links(zookeeper.id :: mesos.masters.map(_.id):_*)
          .portBindings(Map(
            sprintPort.toString -> List(PortBinding.of(network.ip, onPort)
            ).asJava).asJava)
          .build()
        )
        .image(s"adform/sprint:${BuildInfo.version}")
        .exposedPorts(Set(sprintPort.toString).asJava)
        .env(List(
          s"ZOOKEEPER_CONNECT=zk://${zookeeper.name}:${zookeeper.port}/sprint",
          s"MESOS_CONNECT=zk://${zookeeper.name}:${zookeeper.port}/mesos"
        ).asJava)
        .build()

      val sprintContainer = docker.createContainer(sprintDef, sprintName)
      containerId = sprintContainer.id()
      docker.startContainer(containerId)

      val sprint = SprintContainer(containerId, sprintName, network.ip, onPort)
      // ensure sprint instance is running
      eventually { sprint.isUp shouldBe true }

      testCode(sprint)
    } finally {
      if (containerId != "")
        stopAndRemoveContainer(containerId)
    }
  }

  def withSprintInstances(testCode: (SprintContainer, SprintContainer) => Any): Unit = {
    withSprintInstance { sprint1 =>
      withSprintInstance { sprint2 =>
        testCode(sprint1, sprint2)
      }
    }
  }

  def withSprintInstances(testCode: (SprintContainer, SprintContainer, SprintContainer) => Any): Unit = {
    withSprintInstance { sprint1 =>
      withSprintInstance { sprint2 =>
        withSprintInstance { sprint3 =>
          testCode(sprint1, sprint2, sprint3)
        }
      }
    }
  }

}
