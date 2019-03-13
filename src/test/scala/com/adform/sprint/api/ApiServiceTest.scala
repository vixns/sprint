package com.adform.sprint
package api

import mesos.MesosFrameworkDriver
import model._
import model.serialization.JsonFormatters
import state._

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{FunSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks


class ApiServiceTest extends FunSpec with Matchers with ScalatestRouteTest with MockitoSugar with ScalaCheckPropertyChecks with JsonFormatters {

  describe("/status") {

    val containerManager = mock[ContainerRunManager]
    val api = new ApiService(containerManager)

    describe("when leading") {

      val leadingStatusRoutes = api.statusRoutes(imLeading = true, "localhost", 8080)

      it("should respond 'pong' to /status/ping") {
        Get("/status/ping") ~> leadingStatusRoutes ~> check {
          responseAs[String] shouldEqual "pong"
        }
      }
      it("should respond 'true' to /status/leading") {
        Get("/status/leading") ~> leadingStatusRoutes ~> check {
          responseAs[String] shouldEqual "true"
        }
      }
      it("should correctly report /status") {
        Get("/status") ~> leadingStatusRoutes ~> check {
          val status = responseAs[ApiStatus]
          status.leading shouldBe true
          status.leader shouldEqual LeadingApi("localhost", 8080)
        }
      }
    }

    describe("when following") {

      val followingStatusRoutes = api.statusRoutes(imLeading = false, "server", 9090)

      it("should respond 'pong' to /status/ping") {
        Get("/status/ping") ~> followingStatusRoutes ~> check {
          responseAs[String] shouldEqual "pong"
        }
      }
      it("should respond 'false' to /status/leading") {
        Get("/status/leading") ~> followingStatusRoutes ~> check {
          responseAs[String] shouldEqual "false"
        }
      }
      it("should correctly report /status") {
        Get("/status") ~> followingStatusRoutes ~> check {
          val status = responseAs[ApiStatus]
          status.leading shouldBe false
          status.leader shouldEqual LeadingApi("server", 9090)
        }
      }
    }
  }

  describe("/v1") {

    val frameworkDriver = mock[MesosFrameworkDriver]
    val runStore = new InMemoryContainerRunStore()
    val containerManager = new StupidContainerRunManager(runStore)
    val api = new ApiService(containerManager)
    val routes = api.apiRoutes(frameworkDriver)

    describe("/runs") {

      it("should return a list of runs") {

        runStore.setRuns(Gen.listOfN(10, Generators.containerRunGen).sample.get)

        Get("/runs") ~> routes ~> check {
          response.status shouldBe StatusCodes.OK
          responseAs[Seq[ContainerRun]].length shouldBe 10
        }
      }

      it("should register new runs") {

        runStore.clear()
        val runDefinition = Generators.containerRunDefinitionGen.sample.get

        Post("/runs", runDefinition) ~> routes ~> check {
          response.status shouldBe StatusCodes.Created
          responseAs[ContainerRun].definition shouldEqual runDefinition
          runStore.runs.length shouldBe 1
        }
      }
    }

    describe("/runs/{id}") {

      it("should describe an existing run") {

        val runs = Gen.listOfN(10, Generators.containerRunGen).sample.get
        runStore.setRuns(runs)

        Get(s"/runs/${runs.head.id.toString}") ~> routes ~> check {
          response.status shouldBe StatusCodes.OK
          responseAs[ContainerRun].id shouldEqual runs.head.id
        }
      }

      it("should respond with 404 to non-existing runs") {

        runStore.setRuns(Gen.listOfN(10, Generators.containerRunGen).sample.get)

        Get(s"/runs/${Gen.uuid.sample.get.toString}") ~> routes ~> check {
          response.status shouldBe StatusCodes.NotFound
        }
      }

      it("should delete runs") {

        val runs = Gen.listOfN(10, Generators.containerRunGen).sample.get
        val headRunId = runs.head.id.toString
        runStore.setRuns(runs)

        Delete(s"/runs/$headRunId") ~> routes ~> check {
          response.status shouldBe StatusCodes.OK
          responseAs[String] shouldEqual headRunId
          verify(frameworkDriver, times(1)).killTask(headRunId)
        }
      }

      it("should respond with 404 when deleting non-existing runs") {

        val randId = Gen.uuid.sample.get.toString
        runStore.setRuns(Gen.listOfN(10, Generators.containerRunGen).sample.get)

        Delete(s"/runs/$randId") ~> routes ~> check {
          response.status shouldBe StatusCodes.NotFound
        }
      }
    }

  }
}

