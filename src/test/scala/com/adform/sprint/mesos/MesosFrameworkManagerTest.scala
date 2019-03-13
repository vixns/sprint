package com.adform.sprint
package mesos

import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.mesos.Protos._
import org.apache.mesos.{Protos, SchedulerDriver}
import org.scalatest.{FunSpec, Matchers}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar


class MesosFrameworkManagerTest extends FunSpec with Matchers with MockitoSugar {

  private val framework = mock[Framework]
  private val manager = new MesosFrameworkManager(framework)

  it("should notify on framework registration") {

    val callback = mock[FrameworkCoordinationEvent => Unit]

    manager.mesosScheduler(callback).registered(
      mock[SchedulerDriver],
      Protos.FrameworkID.newBuilder().setValue("framework").build(),
      MasterInfo.newBuilder().setId("id").setIp(123).setPort(9090).build()
    )

    verify(callback, times(1)).apply(FrameworkRegistered("framework"))
  }

  it("should notify on framework reregistration") {

    val callback = mock[FrameworkCoordinationEvent => Unit]

    manager.mesosScheduler(callback).reregistered(
      mock[SchedulerDriver],
      MasterInfo.newBuilder().setId("id").setIp(123).setPort(9090).build()
    )

    verify(callback, times(1)).apply(FrameworkReregistered)
  }

  it("should notify on framework disconnection") {

    val callback = mock[FrameworkCoordinationEvent => Unit]

    manager.mesosScheduler(callback).disconnected(mock[SchedulerDriver])

    verify(callback, times(1)).apply(FrameworkDisconnected)
  }

}
