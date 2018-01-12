package com.adform.sprint

import model._
import model.ContainerRunState.Created
import fixtures.ContainerRunDefinitions

import org.scalatest._
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time.{Seconds, Span}
import scalaj.http.Http
import net.liftweb.json._


class FailoverTest extends FlatSpec with Matchers with Eventually with IntegrationPatience
  with fixtures.Docker with fixtures.Mesos with fixtures.SprintBuilder {

  override val mesosMasterCount = 2
  override val mesosSlaveCount = 2

  private val timeout = Span(60, Seconds)
  private val interval = Span(2, Seconds)

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout, interval)

  "sprint" should "re-register with mesos after restarts" in withSprintInstance { sprint =>

    docker.restartContainer(sprint.id)

    eventually { sprint.isUp shouldBe true }

    val mesosLeader = mesos.masters.find(_.isLeader).get

    val frameworkJson = Http(s"http://${mesosLeader.endpoint}/frameworks").asString.body
    val activeFrameworks = parse(frameworkJson) \ "frameworks" \\ "id" \\ classOf[JString]
    val completedFrameworks = parse(frameworkJson) \ "completed_frameworks" \\ "id" \\ classOf[JString]
    val unregisteredFrameworks = parse(frameworkJson) \ "unregistered_frameworks" \\ "id" \\ classOf[JString]

    activeFrameworks.length shouldBe 1
    completedFrameworks.length shouldBe 0
    unregisteredFrameworks.length shouldBe 0
  }

  it should "run job after mesos leader restarts" in withSprintInstance { sprint =>

    mesos.masters.filter(!_.isLeader).foreach(m => docker.stopContainer(m.id, 0))
    val mesosLeader = mesos.masters.find(_.isLeader).get

    docker.restartContainer(mesosLeader.id)

    val jobId = sprint.postJob(ContainerRunDefinitions.hw)
    jobId.isDefined shouldBe true

    eventually {
      sprint.getJobState(jobId.get) shouldBe Some(ContainerRunState.Finished)
    }
  }

  it should "run job after mesos leader changes" in withSprintInstance { sprint =>

    val mesosLeader = mesos.masters.find(_.isLeader).get

    docker.stopContainer(mesosLeader.id, 0)

    eventually { mesos.masters.exists(_.isLeader) shouldBe true }

    val jobId = sprint.postJob(ContainerRunDefinitions.hw)
    jobId.isDefined shouldBe true

    eventually {
      sprint.getJobState(jobId.get) shouldBe Some(ContainerRunState.Finished)
    }
  }

  it should "not run job after mesos slaves are stopped" in withSprintInstance { sprint =>

    mesos.slaves.foreach(s => docker.stopContainer(s.id, 0))

    val jobId = sprint.postJob(ContainerRunDefinitions.hw)
    jobId.isDefined shouldBe true

    // ensure that job remains in Created state
    val timeoutSec = timeout.toSeconds.toInt
    val intervalSec = interval.toSeconds.toInt
    val isCreated = (0 to timeoutSec by intervalSec).foldLeft(true) { (created, _) =>
      if (created) {
        Thread.sleep(intervalSec * 1000)
        sprint.getJobState(jobId.get).contains(Created)
      } else created
    }

    isCreated shouldBe true
  }

}
