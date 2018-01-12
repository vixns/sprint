/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint

import api.ApiService
import mesos.{MesosFrameworkManager, SprintFramework}
import state.{StupidContainerRunManager, Zookeeper, ZookeeperContainerRunStore}

import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import org.log4s._


object Main {

  private[this] val log = getLogger

  def main(args: Array[String]): Unit = try {

    implicit val system: ActorSystem = ActorSystem()
    implicit val context: ExecutionContextExecutor = system.dispatcher

    log.info(s"Starting sprint ...")
    log.info(s"Version: ${BuildInfo.version}")
    log.info(s"Git head commit hash: ${BuildInfo.gitHeadCommit.get}")

    val zkConfig = Configuration().zookeeper

    val zookeeper = new Zookeeper(zkConfig.connectionString)(system.dispatchers.lookup("zk-thread-pool-dispatcher"))

    val containerRoot = s"${zkConfig.node}/runs"

    val containerStore = new ZookeeperContainerRunStore(zookeeper, containerRoot)
    val containerRunManager = new StupidContainerRunManager(containerStore)

    val framework = new SprintFramework(containerRunManager)
    val frameworkManager = new MesosFrameworkManager(framework)
    val apiService = new ApiService(containerRunManager)

    val coordinator = system.actorOf(Coordinator.props(zookeeper, containerRunManager, apiService, frameworkManager))

    coordinator ! Start

    shutdownHook {
      log.info("Caught stop signal, shutting down ...")
      val sh = coordinator.ask(Stop)(timeout = Timeout(5.seconds))

      sh.onComplete {
        case Success(_) => log.info("Shutdown complete, bye!")
        case Failure(t) => log.warn(t)("Failure shutting down, exiting anyway")
      }

      Await.result(sh, Duration.Inf)

      system.terminate()
      Await.result(system.whenTerminated, Duration.Inf)
    }

  } catch {
    case e: Throwable => log.error(e)("Unhandled exception, terminating")
  }

  def shutdownHook(f: => Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = f
  })
}
