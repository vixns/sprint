/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package model
package serialization

import java.util.UUID

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.joda.time.DateTime
import spray.json._


trait JsonFormatters extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object MapIntIntFormat extends RootJsonFormat[Map[Int, Int]] {
    def write(map: Map[Int, Int]) = JsObject(map.map(kv => kv._1.toString -> JsNumber(kv._2)))

    def read(value: JsValue): Map[Int, Int] = value match {
      case JsObject(map) => map.map(kv => kv._1.toInt -> kv._2.convertTo[Int])
      case _ => throw DeserializationException("Can't deserialize a Map[Int, Int]")
    }
  }

  implicit object ContainerTypeJsonFormat extends RootJsonFormat[ContainerType] {
    def write(s: ContainerType): JsString = s match {
      case ContainerType.Docker => JsString("DOCKER")
      case ContainerType.Mesos => JsString("MESOS")
    }

    def read(value: JsValue): ContainerType = value match {
      case JsString("DOCKER") => ContainerType.Docker
      case JsString("MESOS") => ContainerType.Mesos
      case JsString(s) => throw DeserializationException(s"Unsupported container type $s")
      case _ => throw DeserializationException("Could not deserialize container type")
    }
  }

  implicit object EnvironmentTypeJsonFormat extends RootJsonFormat[EnvironmentType] {
    def write(s: EnvironmentType): JsString = s match {
      case EnvironmentType.Value => JsString("VALUE")
      case EnvironmentType.Secret => JsString("SECRET")
    }

    def read(value: JsValue): EnvironmentType = value match {
      case JsString("VALUE") => EnvironmentType.Value
      case JsString("SECRET") => EnvironmentType.Secret
      case JsString(s) => throw DeserializationException(s"Unsupported environment type $s")
      case _ => throw DeserializationException("Could not deserialize environment type")
    }
  }

  implicit object SecretTypeJsonFormat extends RootJsonFormat[SecretType] {
    def write(s: SecretType): JsString = s match {
      case SecretType.Value => JsString("VALUE")
      case SecretType.Reference => JsString("REFERENCE")
    }

    def read(value: JsValue): SecretType = value match {
      case JsString("VALUE") => SecretType.Value
      case JsString("REFERENCE") => SecretType.Reference
      case JsString(s) => throw DeserializationException(s"Unsupported secret type $s")
      case _ => throw DeserializationException(s"Could not deserialize secret type $value")
    }
  }

  implicit object SecretJsonFormat extends RootJsonFormat[Secret] {
    def write(s: Secret): JsObject = s.`type` match {
      case SecretType.Value => JsObject(
        "value" -> JsString(s.value.get))
      case SecretType.Reference => JsObject(
        "name" -> JsString(s.reference.get.name),
        "key" -> JsString(s.reference.get.key))
    }

    def read(value: JsValue): Secret = if (value.asJsObject.getFields("value").isEmpty) {
      Secret(SecretType.Reference, None,
        Option(SecretReference(
          value.asJsObject.fields("name").convertTo[String],
          value.asJsObject.fields("key").convertTo[String])))
    } else {
      Secret(SecretType.Value, Option(value.asJsObject.fields("value").convertTo[String]), None)
    }
  }

  implicit object DateTimeJsonFormat extends RootJsonFormat[DateTime] {
    def write(dt: DateTime) = JsString(dt.toString)

    def read(value: JsValue): DateTime = value match {
      case JsString(str) => DateTime.parse(str)
      case _ => throw DeserializationException("Can't deserialize a DateTime")
    }
  }

  implicit object UuidJsonFormat extends RootJsonFormat[UUID] {
    def write(id: UUID) = JsString(id.toString)

    def read(value: JsValue): UUID = value match {
      case JsString(id) => UUID.fromString(id)
      case _ => throw DeserializationException("Can't deserialize a UUID")
    }
  }

  implicit object ConstraintFormat extends RootJsonFormat[Constraint] {
    def write(con: Constraint): JsArray = con match {
      case LikeConstraint(field, arg) => JsArray(JsString(field), JsString("LIKE"), JsString(arg))
      case UnlikeConstraint(field, arg) => JsArray(JsString(field), JsString("UNLIKE"), JsString(arg))
    }

    def read(value: JsValue): Constraint = value match {
      case JsArray(Vector(JsString(field), JsString("LIKE"), JsString(arg))) => LikeConstraint(field, arg)
      case JsArray(Vector(JsString(field), JsString("UNLIKE"), JsString(arg))) => UnlikeConstraint(field, arg)
      case _ => throw DeserializationException("Can't deserialize a Constraint")
    }
  }

  implicit object ContainerStateJsonFormat extends RootJsonFormat[ContainerRunState] {
    def write(s: ContainerRunState) = JsString(s.toString)

    def read(value: JsValue): ContainerRunState = value match {
      case JsString(str) => ContainerRunState.fromString(str) match {
        case Some(state) => state
        case None => throw DeserializationException(s"Unsupported container state $str")
      }
      case _ => throw DeserializationException("Can't deserialize a ContainerState")
    }
  }

  implicit object EnvironmentJsonFormat extends RootJsonFormat[Environment] {
    def write(e: Environment): JsObject = if (e.value.nonEmpty)
      JsObject("name" -> JsString(e.name), "value" -> JsString(e.value.get))
    else
      JsObject("name" -> JsString(e.name), "secret" -> e.secret.get.toJson(SecretJsonFormat).asJsObject)

    def read(value: JsValue): Environment = {
      val e = value.asJsObject.fields
      val n = e("name").convertTo[String]
      if (e.contains("value")) Environment(n, Option(e("value").convertTo[String]), None)
      else Environment(n, None, Option(e("secret").convertTo[Secret]))
    }
  }

  implicit object SourceTypeJsonFormat extends RootJsonFormat[SourceType] {
    def write(s: SourceType): JsString = s match {
      case SourceType.SandboxPath => JsString("SANDBOX")
      case SourceType.HostPath => JsString("HOST")
      case SourceType.Secret => JsString("SECRET")
    }

    def read(value: JsValue): SourceType = value match {
      case JsString("SANDBOX") => SourceType.SandboxPath
      case JsString("HOST") => SourceType.HostPath
      case JsString("SECRET") => SourceType.Secret
      case JsString(s) => throw DeserializationException(s"Unsupported source type $s")
      case _ => throw DeserializationException(s"Could not deserialize source type $value")
    }
  }
  implicit object VolumeSourceJsonFormat extends RootJsonFormat[VolumeSource] {
    def write(s: VolumeSource): JsObject = s.`type` match {
      case SourceType.Secret => JsObject(
        "type" -> SourceTypeJsonFormat.write(s.`type`),
        "secret" -> s.secret.get.toJson(SecretJsonFormat).asJsObject)
      case _ => JsObject(
          "type" -> SourceTypeJsonFormat.write(s.`type`),
          "path" -> JsString(s.path.get))
    }

    def read(value: JsValue): VolumeSource = value match {
      case JsObject(s) =>
        val t = s("type").convertTo[SourceType]
        t match {
          case SourceType.Secret => VolumeSource(t, None, Option(s("secret").convertTo[Secret]))
          case _ => VolumeSource(t, Option(s("path").convertTo[String]), None)
        }
      case _ => throw DeserializationException("Can't deserialize a VolumeSource")
    }
  }

  implicit val parameterFormat: RootJsonFormat[Parameter] = jsonFormat2(Parameter)
  implicit val dockerDefinitionFormat: RootJsonFormat[DockerDefinition] = jsonFormat3(DockerDefinition)
  implicit val portMappingFormat: RootJsonFormat[PortMapping] = jsonFormat4(PortMapping)
  implicit val networkFormat: RootJsonFormat[Network] = jsonFormat3(Network)
  implicit val hostNetworkFormat: RootJsonFormat[HostNetwork] = jsonFormat2(HostNetwork)
  implicit val uriFormat: RootJsonFormat[Uri] = jsonFormat5(Uri)
  implicit val volumeFormat: RootJsonFormat[Volume] = jsonFormat3(Volume)
  implicit val containerDefinition: RootJsonFormat[ContainerDefinition] = jsonFormat6(ContainerDefinition)
  implicit val containerRunDefinitionFormat: RootJsonFormat[ContainerRunDefinition] = jsonFormat9(ContainerRunDefinition)
  implicit val containerRunFormat: RootJsonFormat[ContainerRun] = jsonFormat5(ContainerRun)
  implicit val containerListFormat: RootJsonFormat[ContainerRunList] = jsonFormat1(ContainerRunList)
  implicit val leadingApiFormat: RootJsonFormat[LeadingApi] = jsonFormat2(LeadingApi)
  implicit val apiStatusFormat: RootJsonFormat[ApiStatus] = jsonFormat4(ApiStatus)
}
