/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package api

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}

import mesos._
import model._
import model.serialization._
import state._
import java.util.UUID

import akka.Done

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.log4s._


trait ApiBinding {
  def unbind(): Future[Done]
}

class ApiService(containerManager: ContainerRunManager)(implicit system: ActorSystem) extends JsonFormatters {

  private[this] val log = getLogger

  private val apiConfig = Configuration().api
  private val advertisedEndpoint = apiConfig.advertisedEndpoint
  private val bindEndpoint = apiConfig.bindEndpoint
  private val tlsEnabled: Boolean = apiConfig.tls.enabled
  private val tlsKeystoreFile: String = apiConfig.tls.keyStoreFile
  private val tlsKeyStorePassword: String = apiConfig.tls.keyStorePassword

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
      uri = akka.http.scaladsl.model.Uri.from(path = context.request.uri.path.toString),
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
    if (tlsEnabled) {
      val keyFile: InputStream = getClass.getClassLoader.getResourceAsStream(tlsKeystoreFile)
      require(keyFile != null, s"Failed to load key file: $tlsKeystoreFile")
      val extension = if (tlsKeystoreFile.lastIndexOf('.') > 0) tlsKeystoreFile.substring(tlsKeystoreFile.lastIndexOf('.') + 1) else ""
      val keyStore: KeyStore = extension.toLowerCase match {
        case "jks" => KeyStore.getInstance("jks") //Java Key Store; Java default and only works with Java; tested
        case "jcek" => KeyStore.getInstance("JCEKS") //Java Cryptography Extension KeyStore; Java 1.4+; not tested
        case "pfx" | "p12" => KeyStore.getInstance("PKCS12") // PKCS #12, Common and supported by many languages/frameworks; tested
        case _ => throw new IllegalArgumentException(s"Key has an unknown type extension $extension. Support types are jks, jcek, pfx, p12.")
      }
      val password: Array[Char] = (if (tlsKeyStorePassword == null) "" else tlsKeyStorePassword).toCharArray
      keyStore.load(keyFile, password)
      //TODO: looks like the "SunX509", "TLS", are defined in the keystore, should we pull them out rather than hard coding?
      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, password)

      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      tmf.init(keyStore)

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
      val https: HttpsConnectionContext = ConnectionContext.https(sslContext)
      Http().setDefaultServerHttpContext(https)
    }
    log.info(s"Binding leader API on ${bindEndpoint.address}:${bindEndpoint.port}")
    Http().bindAndHandle(leaderRoutes(frameworkDriver), bindEndpoint.address, bindEndpoint.port).map { binding =>
      () => binding.unbind()
    }
  }

  def bindFollowerApi(leaderAddress: String, leaderPort: Int): Future[ApiBinding] = {
    log.info(s"Binding follower API proxy to $leaderAddress:$leaderPort on ${bindEndpoint.address}:${bindEndpoint.port}")
    Http().bindAndHandle(followerRoutes(leaderAddress, leaderPort), bindEndpoint.address, bindEndpoint.port).map { binding =>
      () => binding.unbind()
    }
  }
}
