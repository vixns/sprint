/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package state

import java.io.Closeable
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._
import scala.annotation.tailrec
import scala.util.Try
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.framework.recipes.cache.{TreeCache, TreeCacheEvent, TreeCacheListener}
import org.apache.curator.framework.recipes.leader.{LeaderSelector, LeaderSelectorListenerAdapter}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.CreateMode
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.log4s._


case class ZNode(path: String, data: Array[Byte])

trait LeaderElection extends Closeable {
  def getCurrentLeaderId: Future[String]
  def interrupt(): Unit
  override def toString: String = "LeaderElection"
}

trait LeaderElectionEvent
object LeaderElectionEvent {
  case object Elected extends LeaderElectionEvent
  case object Relinquished extends LeaderElectionEvent
}

trait NodeTreeWatch extends Closeable

trait NodeTreeWatchEvent
trait NodeTreeWatchConnectionEvent extends NodeTreeWatchEvent

object NodeTreeWatchEvent {
  case object Initialized extends NodeTreeWatchEvent
  case class NodeAdded(node: ZNode) extends NodeTreeWatchEvent
  case class NodeRemoved(node: ZNode) extends NodeTreeWatchEvent
  case class NodeUpdated(node: ZNode) extends NodeTreeWatchEvent
  case object ConnectionLost extends NodeTreeWatchConnectionEvent
  case object ConnectionReconnected extends NodeTreeWatchConnectionEvent
  case object ConnectionSuspended extends NodeTreeWatchConnectionEvent
}


class Zookeeper(val zkConnect: String)(implicit val dispatcher: ExecutionContext) {

  private[this] val log = getLogger

  private[this] val retryPolicy = new ExponentialBackoffRetry(1000, 3)
  private[this] val client = CuratorFrameworkFactory.newClient(zkConnect, retryPolicy)

  def connect(): Future[Unit] = Future {
    client.start()
    client.blockUntilConnected()
  }

  def nodeExists(path: String): Future[Boolean] = {
    log.trace(s"Checking if node $path exists in zookeeper")
    Future(Option(client.checkExists().forPath(path)).nonEmpty)
  }

  def getNode(path: String): Future[Option[ZNode]] = nodeExists(path).map { exists =>
    log.trace(s"Getting node $path from zookeeper")
    if (exists)
      Some(ZNode(path, client.getData.forPath(path)))
    else
      None
  }

  def getNodes(prefix: String): Future[Seq[ZNode]] = {
    log.trace(s"Getting node list for $prefix from zookeeper")
    val children = Future { client.getChildren.forPath(prefix).asScala.map(s => s"$prefix/$s").toList }
    val maybeNodes = children.flatMap(x => Future.sequence(x.map(getNode)))
    maybeNodes.map(mn => mn.flatten)
  }

  def storeNode(node: ZNode): Future[ZNode] = nodeExists(node.path).map { exists =>
    log.trace(s"Storing path ${node.path} to zookeeper")
    if (exists) {
      client.setData().forPath(node.path, node.data)
    } else {
      client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(node.path, node.data)
    }
    node
  }

  def deleteNode(path: String): Future[Try[String]] = {
    log.trace(s"Deleting path $path from zookeeper")
    Future { Try {
      client.delete().forPath(path)
      path
    }}
  }

  def ensurePath(path: String): Future[ZNode] = nodeExists(path).map { exists =>
    if (!exists) {
      client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path, Array.empty[Byte])
    }
    ZNode(path, Array.empty[Byte])
  }

  def enterLeadershipElection(lockPath: String, electionId: String, callback: LeaderElectionEvent => Unit): LeaderElection = {
    val listener = new LeaderSelectorListenerAdapter {
      @Override
      override protected def takeLeadership(client: CuratorFramework): Unit = {
        callback(LeaderElectionEvent.Elected)
        try {
          Thread.currentThread().join()
        }
        catch {
          case _: InterruptedException =>
            // curator is unhappy if we do this ¯\_(ツ)_/¯
            // Thread.currentThread().interrupt()
        }
        finally {
          callback(LeaderElectionEvent.Relinquished)
        }
      }
    }

    log.debug("Starting leadership election on zookeeper")
    val selector = new LeaderSelector(client, lockPath, listener)
    selector.setId(electionId)
    selector.autoRequeue()
    selector.start()

    new LeaderElection {
      override def getCurrentLeaderId: Future[String] = Future {
        @tailrec
        def findExistingLeader(): String = {
          val electionParticipants = selector.getParticipants.asScala
          if (electionParticipants.isEmpty) {
            log.debug("No participants in leader election, sleeping and retrying")
            Thread.sleep(100)
            findExistingLeader()
          } else {
            electionParticipants.find(_.isLeader) match {
              case Some(leader) => leader.getId
              case None =>
                log.debug("No leader among election participants, sleeping and retrying")
                Thread.sleep(100)
                findExistingLeader()
            }
          }
        }
        findExistingLeader()
      }

      override def interrupt(): Unit = selector.interruptLeadership()
      override def close(): Unit = selector.close()
    }
  }

  def watchNodeTree(pathPrefix: String, handler: NodeTreeWatchEvent => Unit): NodeTreeWatch = {
    import NodeTreeWatchEvent._
    val treeCache = new TreeCache(client, pathPrefix)

    treeCache.getListenable.addListener((_: CuratorFramework, event: TreeCacheEvent) => event.getType match {
      case TreeCacheEvent.Type.INITIALIZED => handler(NodeTreeWatchEvent.Initialized)
      case TreeCacheEvent.Type.NODE_ADDED => handler(NodeAdded(ZNode(event.getData.getPath, event.getData.getData)))
      case TreeCacheEvent.Type.NODE_REMOVED => handler(NodeRemoved(ZNode(event.getData.getPath, event.getData.getData)))
      case TreeCacheEvent.Type.NODE_UPDATED => handler(NodeUpdated(ZNode(event.getData.getPath, event.getData.getData)))
      case TreeCacheEvent.Type.CONNECTION_LOST => handler(ConnectionLost)
      case TreeCacheEvent.Type.CONNECTION_RECONNECTED => handler(ConnectionReconnected)
      case TreeCacheEvent.Type.CONNECTION_SUSPENDED => handler(ConnectionSuspended)
    })
    treeCache.start()

    new NodeTreeWatch {
      override def close(): Unit = treeCache.close()
    }
  }

  def close(): Unit = {
    if (client.getState == CuratorFrameworkState.STARTED)
      client.close()
  }
}
