package com.adform.sprint
package mesos

import model._
import state.ContainerRunManager
import model.Generators._
import model.Lenses._

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.mesos.Protos.Value.{Scalar, Type}
import org.apache.mesos.Protos._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSpec, Matchers}
import org.scalacheck.Arbitrary._
import org.scalatest.prop._


class SprintFrameworkTest extends FunSpec with Matchers with MockitoSugar with PropertyChecks {

  private val framework = new SprintFramework(mock[ContainerRunManager])

  it("should correctly determine if a container can run on provided resource offers") {

    forAll { (runResources: Resources, offerResources: Seq[Resources]) =>

      val run = arbitrary[ContainerRun].sample.get.withResources(runResources)
      val offers = offerResources.map(r => makeOffers(r))

      val offered = offerResources.fold(Resources.none)(_ + _)
      val portMappings = run.definition.container.portMappings

      framework.canRun(run, offers) shouldEqual
        (runResources.cpus <= offered.cpus
          && runResources.memory <= offered.memory
          && framework.portsSatisfied(portMappings, offered)
          && offers.forall(framework.areConstraintsMet(run, _)))
    }
  }

  it("should correctly determine if offer meets constraints") {
    forAll { (hostname: String) =>

      val offer = makeOffers(Resources.none, hostname)

      framework.isConstraintMet(LikeConstraint("hostname", ".*a.*"), offer) shouldEqual hostname.contains('a')
      framework.isConstraintMet(UnlikeConstraint("hostname", ".*a.*"), offer) shouldEqual !hostname.contains('a')

    }
  }

  describe("offer partitioner") {

    it("should produce offers that can run a given container on the left") {

      forAll { (runResources: Resources, offerResources: Seq[Resources]) =>

        val run = arbitrary[ContainerRun].sample.get.withResources(runResources)
        val offers = offerResources.map(r => makeOffers(r))
        val (usableOffers, _) = framework.findAndPartitionOffers(run, offers)

        usableOffers.isEmpty || framework.canRun(run, usableOffers) shouldBe true
      }
    }

    it("should not lose any offers") {

      forAll { (runResources: Resources, offerResources: Seq[Resources]) =>

        val run = arbitrary[ContainerRun].sample.get.withResources(runResources)
        val offers = offerResources.map(r => makeOffers(r))
        val (usableOffers, remainingOffers) = framework.findAndPartitionOffers(run, offers)

        val partitionedOfferIds = usableOffers.map(_.getId.getValue).toSet ++ remainingOffers.map(_.getId.getValue).toSet
        val originalOfferIds = offers.map(_.getId.getValue).toSet

        originalOfferIds shouldEqual partitionedOfferIds
      }
    }
  }

  describe("offer matcher") {

    it("should match offers to runs only if they are sufficient for the run") {

      forAll { (runResources: Seq[Resources], offerResources: Seq[Resources]) =>

        val runs = runResources.map(r => arbitrary[ContainerRun].sample.get.withResources(r))
        val offers = offerResources.map(r => makeOffers(r))
        val matches = framework.matchOffers(offers, runs)

        matches.foreach {
          case (os, Some(run)) if os.nonEmpty =>
            framework.canRun(run, os) shouldBe true
          case _ =>
        }
      }
    }

    it("should not lose any offers") {

      forAll { (runResources: Seq[Resources], offerResources: Seq[Resources]) =>

        val runs = runResources.map(r => arbitrary[ContainerRun].sample.get.withResources(r))
        val offers = offerResources.map(r => makeOffers(r))
        val matches = framework.matchOffers(offers, runs)

        offers.map(_.getId.getValue).toSet shouldEqual matches.flatMap(_._1.map(_.getId.getValue)).toSet
      }
    }
  }

  def makeOffers(resources: Resources, hostname: String = arbitrary[String].sample.get): Offer =
    Offer.newBuilder()
      .setId(OfferID.newBuilder().setValue(UUID.randomUUID().toString))
      .setFrameworkId(FrameworkID.newBuilder().setValue(arbitrary[String].sample.get))
      .setSlaveId(SlaveID.newBuilder().setValue(arbitrary[String].sample.get))
      .setHostname(hostname)
      .addResources(Resource.newBuilder().setType(Type.SCALAR).setName("cpus").setScalar(Scalar.newBuilder().setValue(resources.cpus)))
      .addResources(Resource.newBuilder().setType(Type.SCALAR).setName("mem").setScalar(Scalar.newBuilder().setValue(resources.memory.inMegabytes)))
      .build()
}
