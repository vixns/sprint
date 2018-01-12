/*
 * Copyright (c) 2018 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.sprint
package model

import scala.language.implicitConversions


/**
  * Representation of storage units.
  * Shamelessly inspired by twitter-util:
  * https://github.com/twitter/util/blob/master/util-core/src/main/scala/com/twitter/util/StorageUnit.scala
  *
  * If you import the [[com.adform.sprint.model.StorageUnit.conversions]] implicits you can
  * write human-readable values such as `1.gigabyte` or `50.megabytes`.
  *
  * Note: operations can cause overflows of the Long used to represent the
  * number of bytes.
  */
class StorageUnit(val bytes: Long) extends Ordered[StorageUnit] {
  def inBytes: Long = bytes
  def inKilobytes: Long = bytes / 1024L
  def inMegabytes: Long = bytes / (1024L * 1024)
  def inGigabytes: Long = bytes / (1024L * 1024 * 1024)
  def inTerabytes: Long = bytes / (1024L * 1024 * 1024 * 1024)
  def inPetabytes: Long = bytes / (1024L * 1024 * 1024 * 1024 * 1024)
  def inExabytes: Long  = bytes / (1024L * 1024 * 1024 * 1024 * 1024 * 1024)

  def +(that: StorageUnit): StorageUnit = new StorageUnit(this.bytes + that.bytes)
  def -(that: StorageUnit): StorageUnit = new StorageUnit(this.bytes - that.bytes)
  def *(scalar: Double): StorageUnit = new StorageUnit((this.bytes.toDouble*scalar).toLong)
  def *(scalar: Long): StorageUnit = new StorageUnit(this.bytes * scalar)
  def /(scalar: Long): StorageUnit = new StorageUnit(this.bytes / scalar)

  override def equals(other: Any): Boolean = {
    other match {
      case other: StorageUnit =>
        inBytes == other.inBytes
      case _ =>
        false
    }
  }

  override def hashCode: Int = bytes.hashCode

  override def compare(other: StorageUnit): Int =
    if (bytes < other.bytes) -1 else if (bytes > other.bytes) 1 else 0

  def min(other: StorageUnit): StorageUnit =
    if (this < other) this else other

  def max(other: StorageUnit): StorageUnit =
    if (this > other) this else other

  override def toString: String = inBytes + ".bytes"

  def toHuman: String = {
    val prefix = "KMGTPE"
    var prefixIndex = -1
    var display = bytes.toDouble.abs
    while (display > 1126.0) {
      prefixIndex += 1
      display /= 1024.0
    }
    if (prefixIndex < 0) {
      "%d B".format(bytes)
    } else {
      "%.1f %ciB".format(display*bytes.signum, prefix.charAt(prefixIndex))
    }
  }
}

object StorageUnit {
  val infinite = new StorageUnit(Long.MaxValue)
  val zero = new StorageUnit(0)

  private def factor(s: String) = {
    var lower = s.toLowerCase
    if (lower endsWith "s")
      lower = lower dropRight 1

    lower match {
      case "byte" => 1L
      case "kilobyte" => 1L<<10
      case "megabyte" => 1L<<20
      case "gigabyte" => 1L<<30
      case "terabyte" => 1L<<40
      case "petabyte" => 1L<<50
      case "exabyte" => 1L<<60
      case badUnit => throw new NumberFormatException(
        "Unrecognized unit %s".format(badUnit))
    }
  }

  /**
    * Note, this can cause overflows of the Long used to represent the
    * number of bytes.
    */
  def parse(s: String): StorageUnit = s.split("\\.") match {
    case Array(v, u) =>
      val vv = v.toLong
      val uu = factor(u)
      new StorageUnit(vv * uu)

    case _ =>
      throw new NumberFormatException("invalid storage unit string: %s".format(s))
  }

  object conversions {
    class RichWholeNumber(wrapped: Long) {
      def byte:      StorageUnit = bytes
      def bytes:     StorageUnit = new StorageUnit(wrapped)
      def kilobyte:  StorageUnit = kilobytes
      def kilobytes: StorageUnit = new StorageUnit(wrapped * 1024)
      def megabyte:  StorageUnit = megabytes
      def megabytes: StorageUnit = new StorageUnit(wrapped * 1024 * 1024)
      def gigabyte:  StorageUnit = gigabytes
      def gigabytes: StorageUnit = new StorageUnit(wrapped * 1024 * 1024 * 1024)
      def terabyte:  StorageUnit = terabytes
      def terabytes: StorageUnit = new StorageUnit(wrapped * 1024 * 1024 * 1024 * 1024)
      def petabyte:  StorageUnit = petabytes
      def petabytes: StorageUnit = new StorageUnit(wrapped * 1024 * 1024 * 1024 * 1024 * 1024)

      def thousand: Long = wrapped * 1000
      def million:  Long = wrapped * 1000 * 1000
      def billion:  Long = wrapped * 1000 * 1000 * 1000
    }

    implicit def intToStorageUnitableWholeNumber(i: Int): RichWholeNumber = new RichWholeNumber(i)
    implicit def longToStorageUnitableWholeNumber(l: Long): RichWholeNumber = new RichWholeNumber(l)
  }
}
