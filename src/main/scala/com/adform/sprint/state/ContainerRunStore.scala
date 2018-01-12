/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package state

import model._
import model.serialization._
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.collection.mutable.ArrayBuffer
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.log4s._
import NodeTreeWatchEvent.{NodeAdded, NodeRemoved, NodeUpdated}


trait ContainerRunStore {
  def listContainerRuns(): Future[Seq[ContainerRun]]
  def storeContainerRun(container: ContainerRun): Future[ContainerRun]
  def getContainerRun(id: UUID): Future[Option[ContainerRun]]
  def deleteContainerRun(id: UUID): Future[Option[UUID]]
  def updateContainerRun(id: UUID, update: ContainerRun => ContainerRun): Future[Option[ContainerRun]]
}

case class UpdateRun(id: UUID, update: ContainerRun => ContainerRun)
case class CacheRun(run: ContainerRun)
case class RemoveRun(id: UUID)
case class GetRun(id: UUID)
case object GetRuns

class ContainerRunCache extends Actor {
  def receive: Receive = caching(Map.empty)
  def caching(runs: Map[UUID, ContainerRun]): Receive = {
    case UpdateRun(id, update) =>
      runs.get(id) match {
        case Some(run) =>
          val updated = update(run)
          sender ! Some(updated)
          context.become(caching(runs + (run.id -> updated)))
        case None =>
          sender ! None
      }
    case CacheRun(run) => context.become(caching(runs + (run.id -> run)))
    case RemoveRun(id) => context.become(caching(runs - id))
    case GetRun(id)    => sender ! runs.get(id)
    case GetRuns       => sender ! runs.values.toList
  }
}

class ZookeeperContainerRunStore(zookeeper: Zookeeper, runPrefix: String)(implicit system: ActorSystem)
  extends ContainerRunStore with ByteSerializers {

  private[this] val log = getLogger
  private[this] val cache = system.actorOf(Props(classOf[ContainerRunCache]))

  private[this] def cacheNode(node: ZNode): Unit = nodeToContainer(node) match {
    case Some(run) =>
      log.debug(s"Adding run ${run.id} to cache")
      cache ! CacheRun(run)
    case None =>
      log.warn(s"Could not deserialize node at path ${node.path}, ignoring")
  }

  private[this] def uncacheNode(node: ZNode): Unit = nodeToContainer(node) match {
    case Some(run) =>
      log.debug(s"Removing run ${run.id} to cache")
      cache ! RemoveRun(run.id)
    case None =>
      log.warn(s"Could not deserialize node at path ${node.path}, ignoring")
  }

  zookeeper.watchNodeTree(runPrefix, {
    case NodeAdded(node)   if node.path != runPrefix => cacheNode(node)
    case NodeUpdated(node) if node.path != runPrefix => cacheNode(node)
    case NodeRemoved(node) if node.path != runPrefix => uncacheNode(node)
    case _ =>
  })

  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(1.second)

  private def containerPath(containerId: UUID): String = s"$runPrefix/${containerId.toString}"
  private def containerPath(container: ContainerRun): String = containerPath(container.id)

  private def containerRunToNode(run: ContainerRun): ZNode = ZNode(containerPath(run), toBytes(run))
  private def nodeToContainer(node: ZNode): Option[ContainerRun] = fromBytes(node.data)

  override def listContainerRuns(): Future[Seq[ContainerRun]] =
    cache.ask(GetRuns).mapTo[Seq[ContainerRun]]

  override def storeContainerRun(run: ContainerRun): Future[ContainerRun] = {
    log.debug(s"Storing container run ${run.id} to zookeeper")
    cache ! CacheRun(run)
    zookeeper.storeNode(containerRunToNode(run)).map(_ => run)
  }

  override def updateContainerRun(id: UUID, update: ContainerRun => ContainerRun): Future[Option[ContainerRun]] = {
    log.debug(s"Updating container run $id in zookeeper")
    def transform[A](x: Option[Future[A]]): Future[Option[A]] = Future.sequence(x.toSeq).map(_.headOption)
    for {
      updatedRun <- cache.ask(UpdateRun(id, update)).mapTo[Option[ContainerRun]]
      _ <- transform(updatedRun.map(run => zookeeper.storeNode(containerRunToNode(run))))
    } yield updatedRun
  }

  override def deleteContainerRun(id: UUID): Future[Option[UUID]] = {
    log.debug(s"Deleting container run $id from zookeeper")
    cache ! RemoveRun(id)
    zookeeper.deleteNode(containerPath(id)).map(r => r.toOption.map(_ => id))
  }

  override def getContainerRun(id: UUID): Future[Option[ContainerRun]] =
    cache.ask(GetRun(id)).mapTo[Option[ContainerRun]]
}

class InMemoryContainerRunStore() extends ContainerRunStore {

  val runs: ArrayBuffer[ContainerRun] = ArrayBuffer.empty[ContainerRun]

  def clear(): Unit = runs.clear()

  def setRuns(rs: Seq[ContainerRun]): Unit = {
    runs.clear()
    runs ++= rs
  }

  override def listContainerRuns(): Future[Seq[ContainerRun]] = Future.successful(runs)

  override def storeContainerRun(container: ContainerRun): Future[ContainerRun] = Future.successful {
    runs += container
    container
  }

  override def updateContainerRun(id: UUID, update: ContainerRun => ContainerRun): Future[Option[ContainerRun]] = Future.successful {
    runs.find(_.id == id) match {
      case Some(run) =>
        val updated = update(run)
        runs += updated
        Some(updated)
      case None =>
        None
    }
  }

  override def getContainerRun(id: UUID): Future[Option[ContainerRun]] = Future.successful(runs.find(_.id == id))

  override def deleteContainerRun(id: UUID): Future[Option[UUID]] = Future.successful {
    runs.find(_.id == id).map(run => {
      runs -= run
      run.id
    })
  }
}