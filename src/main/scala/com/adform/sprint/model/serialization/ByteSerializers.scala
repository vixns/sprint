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

import scala.util.Try
import spray.json._


trait ByteSerializers extends JsonFormatters {
  def toBytes(run: ContainerRun): Array[Byte] = run.toJson.compactPrint.getBytes
  def fromBytes(run: Array[Byte]): Option[ContainerRun] =
    Try(new String(run).parseJson.convertTo[ContainerRun]).toOption
}
