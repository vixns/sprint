/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package mesos

import java.util
import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, Protos, Scheduler, SchedulerDriver}
import org.log4s._


trait FrameworkCoordinationEvent
final case class FrameworkRegistered(id: String) extends FrameworkCoordinationEvent
case object FrameworkDisconnected extends FrameworkCoordinationEvent
case object FrameworkReregistered extends FrameworkCoordinationEvent

trait MesosFrameworkDriver {
  def stop(): Unit
  def killTask(id: String): Unit
}


class MesosFrameworkManager(framework: Framework)(implicit context: ExecutionContext) {

  private[this] val log = getLogger

  private[this] val mesosConfig = Configuration().mesos

  def mesosScheduler(coordinationEventCallback: FrameworkCoordinationEvent => Unit): Scheduler = new Scheduler {

    override def offerRescinded(driver: SchedulerDriver, offerId: OfferID): Unit = {
      log.info(s"Offer ${offerId.getValue} rescinded")
    }

    override def disconnected(driver: SchedulerDriver): Unit = {
      log.warn("Disconnected from mesos")
      coordinationEventCallback(FrameworkDisconnected)
    }

    override def reregistered(driver: SchedulerDriver, masterInfo: MasterInfo): Unit = {
      log.info(s"Re-registered at master ${masterInfo.getHostname}")
      coordinationEventCallback(FrameworkReregistered)
    }

    override def slaveLost(driver: SchedulerDriver, slaveId: SlaveID): Unit = {
      log.warn(s"Slave ${slaveId.getValue} lost")
    }

    override def error(driver: SchedulerDriver, message: String): Unit = {
      log.error(s"Received error from framework: $message")
    }

    override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
      log.info(s"Received status update from task ${status.getTaskId.getValue}")
      log.info(status.toString)
      framework.updateTask(status)
    }

    override def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]): Unit = {
      log.info(s"Received message from mesos slave ${slaveId.getValue}")
    }

    override def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]): Unit = {
      framework.considerOffers(offers.asScala.toList).onComplete {
        case Success(responses) => responses.foreach {
          case LaunchTasks(os, ts) =>
            log.info(s"Launching tasks ${ts.map(_.getTaskId.getValue).mkString(",")} on offers ${os.map(_.getId.getValue).mkString(",")}")
            driver.launchTasks(os.map(_.getId).asJava, ts.asJava)
          case DeclineOffers(os) =>
            log.debug(s"Declining offers ${os.map(_.getId.getValue).mkString(",")}")
            os.foreach(o => driver.declineOffer(o.getId))
        }
        case Failure(f) =>
          log.warn(s"Did not get back an answer on resource offers, declining [$f]")
          offers.asScala.foreach(o => driver.declineOffer(o.getId))
      }
    }

    override def registered(driver: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo): Unit = {
      log.info(s"Registered framework ${frameworkId.getValue} at master ${masterInfo.getHostname}")
      coordinationEventCallback(FrameworkRegistered(frameworkId.getValue))
    }

    override def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int): Unit = {
      log.warn(s"Executor ${executorId.getValue} lost on slave ${slaveId.getValue}, status: $status")
    }
  }

  def startFramework(frameworkId: Option[String], coordinationEventCallback: FrameworkCoordinationEvent => Unit): MesosFrameworkDriver = {
    val frameworkInfoBuilder =
      Protos.FrameworkInfo.newBuilder
        .setName(mesosConfig.frameworkName)
        .setWebuiUrl(mesosConfig.webUrl)
        .setFailoverTimeout(mesosConfig.failoverTimeout.toMillis)
        .setCheckpoint(true)

    frameworkId.foreach { id => frameworkInfoBuilder.setId(FrameworkID.newBuilder().setValue(id)) }

    mesosConfig.username.foreach { u => frameworkInfoBuilder.setUser(u) }
    mesosConfig.role.foreach { r => frameworkInfoBuilder.setRoles(0, r) }

    log.info("Starting mesos framework driver")

    val driver = if (mesosConfig.principal.isDefined && mesosConfig.secret.isDefined) {
      mesosConfig.principal.foreach { p => frameworkInfoBuilder.setPrincipal(p) }
      val credentials = Credential.newBuilder
        .setPrincipal(mesosConfig.principal.get)
        .setSecret(mesosConfig.secret.get)

      new MesosSchedulerDriver(
        mesosScheduler(coordinationEventCallback),
        frameworkInfoBuilder.build(),
        mesosConfig.connectionString,
        credentials.build()
      )
    } else {
      new MesosSchedulerDriver(
        mesosScheduler(coordinationEventCallback),
        frameworkInfoBuilder.build(),
        mesosConfig.connectionString
      )
    }

    driver.start()

    new MesosFrameworkDriver {
      override def stop(): Unit = driver.stop(true)
      override def killTask(id: String): Unit = {
        driver.killTask(TaskID.newBuilder().setValue(id).build())
      }
    }
  }
}
