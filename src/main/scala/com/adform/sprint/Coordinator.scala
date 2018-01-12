/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint

import api._
import state._
import mesos._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.Try
import java.util.UUID
import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorSystem, Cancellable, FSM, Props}
import akka.pattern.pipe
import org.joda.time.DateTime


case object Start
case object Stop
case object Stopped

case class Leader(token: String, hostname: String, port: Int) {
  def toId: String = s"$token|$hostname|$port"
}

object Leader {
  def parse(id: String): Leader = {
    val Array(token, hostname, portStr) = id.split('|')
    Leader(token, hostname, portStr.toInt)
  }
}

class Coordinator(zookeeper: Zookeeper, containerRunManager: ContainerRunManager, apiService: ApiService, frameworkManager: MesosFrameworkManager)
  extends Actor with ActorLogging with FSM[Coordinator.State, Coordinator.Data] {

  import Coordinator._

  implicit val system: ActorSystem = context.system
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher

  private val zkConfig = Configuration().zookeeper
  private val apiConfig = Configuration().api
  private val runCleanupConfig = Configuration().containerRunCleanup

  private val lockNode = s"${zkConfig.node}/lock"
  private val frameworkNode = s"${zkConfig.node}/framework"

  private val meAsLeader = Leader(UUID.randomUUID().toString, apiConfig.advertisedEndpoint.address, apiConfig.advertisedEndpoint.port)

  startWith(Idle, Empty)

  // Once the main app notifies us to start we warm up ZooKeeper

  when(Idle) {
    case Event(Start, Empty) =>
      log.info("Coordination started, initializing zookeeper")
      initZookeeper() pipeTo self
      goto(Starting)
  }

  // Starting mainly consists of waiting for zookeeper to initialize

  when(Starting) {
    case Event(ZookeeperReady(zkData, leader), _) if leader != meAsLeader =>
      log.info(s"Current leader is $leader, entering transitioning to following state")
      initFollowing(leader) pipeTo self
      goto(Transitioning) using zkData

    case Event(ZookeeperReady(zkData, leader), _) =>
      log.info(s"I got elected as leader with id $leader, entering transitioning to leader start")
      initLeadership() pipeTo self
      goto(Transitioning) using zkData

    case Event(ElectedLeader, _) =>
      log.info("Elected as initial leader, waiting for zookeeper to complete initializing")
      stay()
  }

  // Transitioning to leading or following, either initially or after an external event

  when(Transitioning) {
    case Event(LeadershipInitialized(data), zkData: ZookeeperData) =>
      log.info("Framework and API initialized, beginning leadership")
      goto(Leading) using FullData(data, zkData)

    case Event(FollowingInitialized(data), zkData: ZookeeperData) =>
      log.info("API initialized, beginning following")
      goto(Following) using FullData(data, zkData)
  }

  // We are leading

  when(Leading) {
    case Event(RelinquishedLeadership, FullData(ld: LeadershipData, zkData)) =>
      log.info("Leadership relinquished, entering transitioning to following state")
      relinquishLeadership(ld, zkData) pipeTo self
      goto(Transitioning) using zkData

    case Event(ContainerRunCleanup, _) =>
      log.info("Performing container run cleanup")
      cleanupContainerRuns()
      stay()
  }

  // We are following

  when(Following) {
    case Event(ElectedLeader, FullData(fd: FollowerData, zkData)) =>
      log.info("I got elected as leader, entering transitioning to leading state")
      relinquishFollowing(fd) pipeTo self
      goto(Transitioning) using zkData

    case Event(LeaderSync, FullData(fd: FollowerData, zkData)) =>
      log.debug("Checking if leader is still the same")
      checkLeader(zkData, fd)
      stay()

    case Event(LeaderChanged(newLeader), FullData(fd: FollowerData, zkData)) =>
      log.info(s"Leader changed to ${newLeader.hostname}:${newLeader.port}, rebinding API")
      refreshFollowing(fd, newLeader) pipeTo self
      stay()

    case Event(FollowingUpdated(nfd: FollowerData), FullData(fd: FollowerData, zkData)) =>
      log.info("Leadership change acknowledged, continuing following")
      goto(Following) using FullData(nfd, zkData)
  }

  // Everything else goes here, including the Stop message

  whenUnhandled {
    case Event(Stop, _) =>
      log.info("Stopping coordination")
      cleanup()
      sender ! Stopped
      stop()

    case Event(FrameworkDisconnected, _) =>
      log.warning(s"Framework disconnected in state $stateName")
      stay()

    case Event(FrameworkReregistered, _) =>
      log.info("Framework reregistered")
      stay()

    case Event(ContainerRunCleanup, _) =>
      log.warning(s"Can not cleanup container runs at this point, ignoring (current state is $stateName)")
      stay()

    case Event(LeaderSync | LeaderChanged(_), _) =>
      log.warning(s"Leader syncing is irrelevant at the current state $stateName, skipping")
      stay()

    case Event(Failure(e), _) =>
      log.error(e, s"Failure encountered in coordination, exiting (current state is $stateName, data is $stateData)")
      stop(FSM.Shutdown)

    case Event(event, data) =>
      log.error(s"Got an unexpected event $event while in state $stateName with data $data, this is likely a bug, exiting")
      stop(FSM.Shutdown)
  }

  onTermination {
    case StopEvent(FSM.Shutdown | FSM.Failure(_), _, _) =>
      cleanup()
      Runtime.getRuntime.halt(1)
  }

  initialize()

  // Initialize zookeeper, ensure needed nodes exist and enter leadership election
  // Also subscribe to leadership change notifications
  def initZookeeper(): Future[ZookeeperReady] = {

    val fsm = self
    def leadershipCallback(event: LeaderElectionEvent): Unit = event match {
      case LeaderElectionEvent.Elected => fsm ! ElectedLeader
      case LeaderElectionEvent.Relinquished => fsm ! RelinquishedLeadership
    }

    for {
      _ <- zookeeper.connect()
      _ <- zookeeper.ensurePath(lockNode)
      election = zookeeper.enterLeadershipElection(lockNode, meAsLeader.toId, leadershipCallback)
      currentLeader <- getCurrentLeader(election)
    } yield ZookeeperReady(ZookeeperData(election), currentLeader)
  }

  // Get the framework ID from zookeeper, if any
  // Start the mesos framework, bind the API and schedule container run cleanup
  def initLeadership(): Future[LeadershipInitialized] = {

    val fsm = self
    val registrationPromise = Promise[String]()

    def frameworkEventCallback(event: FrameworkCoordinationEvent): Unit = event match {
      case FrameworkRegistered(frameworkId) =>
        if (!registrationPromise.isCompleted) registrationPromise.complete(Try(frameworkId))
      case FrameworkDisconnected => fsm ! FrameworkDisconnected
      case FrameworkReregistered => fsm ! FrameworkReregistered
    }

    for {
      existingFrameworkId <- zookeeper.getNode(frameworkNode).map(_.map(n => new String(n.data)))
      frameworkDriver = frameworkManager.startFramework(existingFrameworkId, frameworkEventCallback)
      registeredFrameworkId <- registrationPromise.future
      _ <- zookeeper.storeNode(ZNode(frameworkNode, registeredFrameworkId.getBytes))
      apiBinding <- apiService.bindLeaderApi(frameworkDriver)
      containerCleanup = system.scheduler.schedule(5.seconds, runCleanupConfig.cleanupInterval, fsm, ContainerRunCleanup)
    } yield LeadershipInitialized(LeadershipData(frameworkDriver, apiBinding, containerCleanup))
  }

  // Bind the follower API, schedule leader sync checkups
  def initFollowing(leader: Leader): Future[FollowingInitialized] = for {
    binding <- apiService.bindFollowerApi(leader.hostname, leader.port)
    leaderSync = system.scheduler.schedule(1.second, 1.second, self, LeaderSync)
  } yield FollowingInitialized(FollowerData(binding, leader, leaderSync))

  // Unbind the leader API, stop the mesos framework driver, cancel container run cleanup
  // Start initializing following
  def relinquishLeadership(ld: LeadershipData, zk: ZookeeperData): Future[FollowingInitialized] = for {
    _ <- ld.apiBinding.unbind()
    _ = ld.runCleanup.cancel()
    _ = ld.frameworkDriver.stop()
    leaderId <- zk.election.getCurrentLeaderId
    following <- initFollowing(Leader.parse(leaderId))
  } yield following

  // Unbind the follower API and start leadership initialization
  def relinquishFollowing(fd: FollowerData): Future[LeadershipInitialized] = for {
    _ <- fd.apiBinding.unbind()
    _ = fd.leaderCheck.cancel()
    leading <- initLeadership()
  } yield leading

  // Rebind the follower API to reflect change in leadership
  def refreshFollowing(fd: FollowerData, newLeader: Leader): Future[FollowingUpdated] = for {
    _ <- fd.apiBinding.unbind()
    _ = fd.leaderCheck.cancel()
    FollowingInitialized(newData) <- initFollowing(newLeader)
  } yield FollowingUpdated(newData)

  // Perform a scheduled container run cleanup
  def cleanupContainerRuns(): Future[Seq[UUID]] = {
    containerRunManager.listContainerRuns(r => !r.state.isActive).flatMap { containers =>
      val now = DateTime.now()
      val ttlExpired = containers.filter { container =>
        val sinceLastModified = now.getMillis - container.lastModified.getMillis
        sinceLastModified > runCleanupConfig.runRetention.toMillis
      }
      val overflow = if (runCleanupConfig.maxInactiveRuns.isDefined) {
        val ttlExpiredSet = ttlExpired.map(_.id).toSet
        containers
          .filter(c => !ttlExpiredSet.contains(c.id))
          .sortBy(r => r.lastModified.getMillis)(Ordering[Long].reverse)
          .drop(runCleanupConfig.maxInactiveRuns.get)
      } else {
        Seq.empty
      }
      val toRemove = ttlExpired ++ overflow
      val removed = Future.sequence {
        toRemove.map { container =>
          log.debug(s"Cleaning up container run ${container.id}")
          containerRunManager.deleteContainerRun(container.id)
        }
      }
      removed.map(_.flatten)
    }
  }

  // Check if the leader is still the same, notify self otherwise
  def checkLeader(zk: ZookeeperData, fd: FollowerData): Unit = {
    val fsm = self
    getCurrentLeader(zk.election).foreach { leader =>
      if (leader != fd.currentLeader)
        fsm ! LeaderChanged(leader)
    }
  }

  // Find the current leader
  def getCurrentLeader(election: LeaderElection): Future[Leader] = {
    election.getCurrentLeaderId.map(Leader.parse)
  }

  // Perform cleanup when stopping the FSM
  def cleanup(): Unit = {
    zookeeper.close()
  }
}

object Coordinator {
  def props(zookeeper: Zookeeper, containerRunManager: ContainerRunManager, apiService: ApiService, frameworkManager: MesosFrameworkManager): Props =
    Props(classOf[Coordinator], zookeeper, containerRunManager, apiService, frameworkManager)

  case object RelinquishedLeadership
  case object ElectedLeader
  case object ContainerRunCleanup
  case object LeaderSync
  final case class LeaderChanged(newLeader: Leader)

  final case class ZookeeperReady(data: ZookeeperData, leader: Leader)
  final case class LeadershipInitialized(data: LeadershipData)
  final case class FollowingInitialized(data: FollowerData)
  final case class FollowingUpdated(data: FollowerData)

  trait State
  case object Idle extends State
  case object Starting extends State
  case object Transitioning extends State
  case object Leading extends State
  case object Following extends State

  trait Data
  case object Empty extends Data
  final case class ZookeeperData(election: LeaderElection) extends Data

  trait WorkingData extends Data
  final case class LeadershipData(frameworkDriver: MesosFrameworkDriver, apiBinding: ApiBinding, runCleanup: Cancellable) extends WorkingData
  final case class FollowerData(apiBinding: ApiBinding, currentLeader: Leader, leaderCheck: Cancellable) extends WorkingData

  final case class FullData(wd: WorkingData, zookeeper: ZookeeperData) extends Data
}
