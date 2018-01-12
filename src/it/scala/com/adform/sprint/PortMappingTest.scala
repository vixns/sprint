package com.adform.sprint

import model._
import fixtures.ContainerRunDefinitions

import scala.collection.JavaConverters._
import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time.{Seconds, Span}
import scalaj.http.Http
import net.liftweb.json._


class PortMappingTest extends FlatSpec with Matchers with Eventually with IntegrationPatience
  with fixtures.Docker with fixtures.Mesos with fixtures.SprintBuilder {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(40, Seconds), interval = Span(2, Seconds))

  "sprint" should "run job and map ports correctly" in withSprintInstance { sprint =>

    val containerPort = 8000
    val webServer = ContainerRunDefinitions.hwWebServer
      .copy(container = ContainerRunDefinitions.hwWebServer.container.copy(
        portMappings = Some(List(PortMapping(containerPort, None, Some("test_port"))))
      ))

    val jobIdOption = sprint.postJob(webServer)
    jobIdOption.isDefined shouldBe true

    val jobId = jobIdOption.get

    eventually {
      sprint.getJobState(jobId) shouldBe Some(ContainerRunState.Running)
    }

    val responseBody = Http(s"http://${sprint.endpoint}/v1/runs/$jobId").asString.body

    val hostPort = (parse(responseBody) \ "network" \\ "hostPort" \ classOf[JInt]).head

    Http(s"http://${network.ip}:$hostPort").asString.code shouldBe 200
  }

  it should "set port mappings as container environment variables" in withSprintInstance { sprint =>

    // create port mappings
    val mappings = List(
      PortMapping(1234, None, Some("test_port")),
      PortMapping(1235, None, None),
      PortMapping(1236, None, Some("test_port2"))
    )

    val webServer = ContainerRunDefinitions.hwWebServer
      .copy(container = ContainerRunDefinitions.hwWebServer.container.copy(
        portMappings = Some(mappings)
      ))

    // start job
    val jobIdOption = sprint.postJob(webServer)
    jobIdOption.isDefined shouldBe true

    val jobId = jobIdOption.get

    eventually {
      sprint.getJobState(jobId) shouldBe Some(ContainerRunState.Running)
    }

    // get job container id
    val containerIdOption = for {
      leader      <- mesos.masters.find(_.isLeader)
      tasksIds    <- leader.tasksIds
      taskId      <- tasksIds.headOption
      containerId <- getContainerId(s"mesos-$taskId")
    } yield containerId

    containerIdOption.isDefined shouldBe true
    val containerId = containerIdOption.get

    // get container env variables
    val envVars = docker.inspectContainer(containerId).config().env().asScala.toList.map { v =>
      val s = v.split("=")
      s.head -> s.last
    }.toMap

    mappings.zipWithIndex.foreach { case (mapping, index) =>
      portNamesSetAndEqual(mapping, index, envVars) shouldBe true
    }
  }

  // check if ports and their values are the same
  def portNamesSetAndEqual(mapping: PortMapping, index: Int, envVars: Map[String, String]): Boolean = {
    val portContainerName = s"PORT_${mapping.containerPort}"
    val portIndexName = s"PORT$index"
    val portSpecificName = mapping.name.map(name => s"PORT_${name.toUpperCase}")

    val allSetAndEqual = for {
      portContainer <- envVars.get(portContainerName)
      portIndex     <- envVars.get(portIndexName)
      portSpecific  <- portSpecificName match {
        case Some(name) => envVars.get(name)
        case None => Some(portIndex)
      }
    } yield portContainer == portIndex && portIndex == portSpecific

    allSetAndEqual.getOrElse(false)
  }
}
