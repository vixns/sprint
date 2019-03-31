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

  implicit val parameterFormat = jsonFormat2(Parameter)
  implicit val dockerDefinitionFormat = jsonFormat3(DockerDefinition)
  implicit val portMappingFormat = jsonFormat3(PortMapping)
  implicit val networkFormat = jsonFormat2(Network)
  implicit val containerDefinition = jsonFormat3(ContainerDefinition)
  implicit val containerRunDefinitionFormat = jsonFormat9(ContainerRunDefinition)
  implicit val containerRunFormat = jsonFormat4(ContainerRun)
  implicit val containerListFormat = jsonFormat1(ContainerRunList)
  implicit val leadingApiFormat = jsonFormat2(LeadingApi)
  implicit val apiStatusFormat = jsonFormat4(ApiStatus)

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

  implicit object UuidJsonFormat extends RootJsonFormat[UUID] {
    def write(id: UUID) = JsString(id.toString)
    def read(value: JsValue): UUID = value match {
      case JsString(id) => UUID.fromString(id)
      case _ => throw DeserializationException("Can't deserialize a UUID")
    }
  }

  implicit object DateTimeJsonFormat extends RootJsonFormat[DateTime] {
    def write(dt: DateTime) = JsString(dt.toString)
    def read(value: JsValue): DateTime = value match {
      case JsString(str) => DateTime.parse(str)
      case _ => throw DeserializationException("Can't deserialize a DateTime")
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
}
