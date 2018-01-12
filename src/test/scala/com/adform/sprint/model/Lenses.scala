package com.adform.sprint
package model

import mesos.Resources

import scala.language.implicitConversions

object Lenses {

  class LensedContainerRun(containerRun: ContainerRun) {

    def withLabels(labels: Map[String, String]): ContainerRun =
      containerRun.copy(definition = containerRun.definition.copy(labels = Some(labels)))

    def withResources(resources: Resources): ContainerRun =
      containerRun.copy(definition = containerRun.definition.copy(cpus = Some(resources.cpus), mem = Some(resources.memory.inMegabytes)))
  }

  implicit def lensedContainerRun(containerRun: ContainerRun): LensedContainerRun = new LensedContainerRun(containerRun)
}
