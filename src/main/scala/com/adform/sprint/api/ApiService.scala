/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package api

import mesos._
import model._
import model.serialization._
import state._

import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import org.log4s._


trait ApiBinding {
  def unbind(): Future[Unit]
}

class ApiService(containerManager: ContainerRunManager)(implicit system: ActorSystem) extends JsonFormatters {

  private[this] val log = getLogger

  private val apiConfig = Configuration().api
  private val advertisedEndpoint = apiConfig.advertisedEndpoint
  private val bindEndpoint = apiConfig.bindEndpoint

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(apiConfig.timeout)

  private lazy val doc: String = scala.io.Source.fromFile("/opt/sprint/doc/api.html").mkString.replace("$BASE_URL$", advertisedEndpoint.baseUrl)
  private lazy val version: String = BuildInfo.version
  private lazy val gitCommit: String = BuildInfo.gitHeadCommit.head

  def parseLabels(labelStr: String): Try[Map[String, String]] = Try {
    labelStr.split(',').filter(_.nonEmpty).map { kv =>
      val split = kv.split(':')
      (split(0), split(1))
    }.toMap
  }

  def runPredicate(includeFinished: Boolean, filterLabels: Map[String, String])(run: ContainerRun): Boolean = {
    val runLabels = run.definition.labels.getOrElse(Map.empty[String, String]).toSeq
    filterLabels.forall(lb => runLabels.contains(lb)) && (run.state.isActive || includeFinished)
  }

  def transform[A](o: Try[Future[A]]): Future[A] = o match {
    case Success(f) => f
    case Failure(e) => Future.failed(e)
  }

  def apiRoutes(frameworkDriver: MesosFrameworkDriver): Route =
    path("runs") {
      get {
        parameters('includeFinished.as[Boolean] ? true, 'labels ? "") { (includeFinished, labelStr) =>
          val containerFuture = transform(parseLabels(labelStr).map(labels =>
            containerManager.listContainerRuns(runPredicate(includeFinished, labels)))
          )
          onComplete(containerFuture) {
            case Success(containers) => complete(StatusCodes.OK, containers)
            case Failure(f) => complete(StatusCodes.InternalServerError, f)
          }

        }
      } ~
      (post & entity(as[ContainerRunDefinition])) { definition =>
        onSuccess(containerManager.createContainerRun(definition)) { result: ContainerRun =>
          complete(StatusCodes.Created, result)
        }
      }
    } ~
    path("runs" / Segment) { id =>
      get {
        onSuccess(containerManager.getContainerRun(UUID.fromString(id))) {
          case Some(container) => complete(StatusCodes.OK, container)
          case None => complete(StatusCodes.NotFound, id)
        }
      } ~
      delete {
        onSuccess(containerManager.deleteContainerRun(UUID.fromString(id))) {
          case Some(removedId) =>
            frameworkDriver.killTask(id)
            complete(StatusCodes.OK, removedId.toString)
          case None =>
            complete(StatusCodes.NotFound, id)
        }
      }
    }

  val docRoutes: Route = path("docs") {
    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, doc))
  }

  def statusRoutes(imLeading: Boolean, leaderAddress: String, leaderPort: Int): Route = pathPrefix("status") {
    pathEnd {
      complete(ApiStatus(imLeading, LeadingApi(leaderAddress, leaderPort), version, gitCommit))
    } ~
    path("ping") {
      complete("pong")
    } ~
    path("leading") {
      complete(imLeading.toString)
    }
  }

  def proxyRoutes(destAddress: String, destPort: Int): Route = Route { context =>
    val flow = Http(system).outgoingConnection(destAddress, destPort)
    val proxyRequest = HttpRequest(
      method = context.request.method,
      uri = Uri.from(path = context.request.uri.path.toString),
      entity = context.request.entity
    )
    val handler = Source.single(proxyRequest)
      .via(flow)
      .runWith(Sink.head)
      .flatMap(context.complete(_))
    handler
  }

  def leaderRoutes(frameworkDriver: MesosFrameworkDriver): Route =
    pathPrefix("v1") {
      apiRoutes(frameworkDriver)
    } ~
    docRoutes ~
    statusRoutes(imLeading = true, advertisedEndpoint.address, advertisedEndpoint.port)


  def followerRoutes(leaderAddress: String, leaderPort: Int): Route =
    pathPrefix("v1") {
      proxyRoutes(leaderAddress, leaderPort)
    } ~
    docRoutes ~
    statusRoutes(imLeading = false, leaderAddress, leaderPort)


  def bindLeaderApi(frameworkDriver: MesosFrameworkDriver): Future[ApiBinding] = {
    log.info(s"Binding leader API on ${bindEndpoint.address}:${bindEndpoint.port}")
    Http().bindAndHandle(leaderRoutes(frameworkDriver), bindEndpoint.address, bindEndpoint.port).map { binding =>
      new ApiBinding {
        override def unbind(): Future[Unit] = binding.unbind()
      }
    }
  }

  def bindFollowerApi(leaderAddress: String, leaderPort: Int): Future[ApiBinding] = {
    log.info(s"Binding follower API proxy to $leaderAddress:$leaderPort on ${bindEndpoint.address}:${bindEndpoint.port}")
    Http().bindAndHandle(followerRoutes(leaderAddress, leaderPort), bindEndpoint.address, bindEndpoint.port).map { binding =>
      new ApiBinding {
        override def unbind(): Future[Unit] = binding.unbind()
      }
    }
  }
}
