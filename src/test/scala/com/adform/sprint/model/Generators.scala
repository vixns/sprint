package com.adform.sprint
package model

import com.adform.sprint.mesos.{PortRange, Resources}
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary._
import org.joda.time.DateTime
import model.StorageUnit.conversions._

object Generators {

  val imageGen: Gen[String] = for {
    registry <- Gen.oneOf("evilcorp", "omnitech", "megastock")
    image <- Gen.oneOf("service", "database", "app")
    tag <- Gen.chooseNum(1, 100)
  } yield s"$registry/$image:$tag"

  val parameterGen: Gen[Parameter] = for {
    key <- Gen.alphaStr
    value <- Gen.option(Gen.alphaStr)
  } yield Parameter(key, value)

  val portMappingGen: Gen[PortMapping] = for {
    containerPort <- Gen.chooseNum(0, 65535)
    hostPort <- Gen.option(Gen.chooseNum(0, 65535))
    name <- Gen.option(Gen.alphaStr)
  } yield PortMapping(containerPort, hostPort, name)

  val secretGen: Gen[Secret] = for {
    secretType <- Gen.oneOf(SecretType.Value, SecretType.Reference)
    value <- Gen.some(Gen.alphaStr)
    secretReferenceKey <- Gen.alphaStr
    secretReferenceValue <- Gen.alphaStr
    secretReference <- Gen.some(SecretReference(secretReferenceKey, secretReferenceValue))
  } yield Secret(secretType, value, secretReference)

  val envGen: Gen[Environment] = for {
    name <- Gen.alphaStr
    value <- Gen.some(Gen.alphaStr)
    secret <- Gen.some(secretGen)
  } yield Environment(name, value, secret)

  val kvGen: Gen[(String, String)] = for {
    key <- Gen.listOfN(Gen.chooseNum(3, 10).sample.get, Gen.alphaChar).map(_.mkString)
    value <- Gen.alphaStr
  } yield key -> value

  val networkGen: Gen[Network] = for {
    name <- Gen.alphaStr
    portMappings <- Gen.option(Gen.listOf(portMappingGen))
    labels <- Gen.option(Gen.mapOf(kvGen))
  } yield Network(name, portMappings, labels)

  val volumeGen: Gen[Volume] = for {
    container_path <- Gen.alphaStr
    path <- Gen.some(Gen.alphaStr)
    mode <- Gen.oneOf("RW", "RO")
    sourceType <- Gen.oneOf(SourceType.HostPath, SourceType.Secret, SourceType.SandboxPath)
    secret <- Gen.some(secretGen)
  } yield Volume(container_path, VolumeSource(sourceType, path, secret), mode)

  val containerDefinitionGen: Gen[ContainerDefinition] = for {
    image <- imageGen
    forcePull <- Gen.option(Gen.oneOf(true, false))
    parameters <- Gen.option(Gen.listOf(parameterGen))
    portMappings <- Gen.option(Gen.listOf(portMappingGen))
    containerType <- Gen.oneOf(ContainerType.Docker, ContainerType.Mesos)
    envList <- Gen.option(Gen.listOf(envGen))
    networks <- Gen.option(Gen.listOf(networkGen))
    volumes <- Gen.option(Gen.listOf(volumeGen))
  } yield ContainerDefinition(
    DockerDefinition(image, forcePull, parameters),
    containerType, networks, portMappings, envList, volumes)

  val portRangeListGen: Gen[List[PortRange]] = for {
    rangeLimits <- Gen.listOf(Gen.chooseNum(0, 65534))
    rangeLimitsSorted = rangeLimits.toSet.toList.sorted
    ranges = rangeLimitsSorted.zip(rangeLimitsSorted.drop(1)).map { case (start, end) => PortRange(start, end) }
  } yield ranges

  val resourceGen: Gen[Resources] = for {
    cpus <- Gen.chooseNum(0.01, 40.0)
    mem <- Gen.chooseNum(100, 256000)
    ports <- portRangeListGen
  } yield Resources(cpus, mem.megabytes, ports)

  implicit lazy val arbResources: Arbitrary[Resources] = Arbitrary(resourceGen)

  val constraintGen: Gen[Constraint with Product with Serializable] = for {
    field <- Gen.oneOf(Gen.const("hostname"), Gen.alphaStr)
    arg <- Gen.alphaStr
    constraint <- Gen.oneOf(LikeConstraint.apply _, UnlikeConstraint.apply _)
  } yield constraint(field, arg)

  val hostNetworkGen: Gen[HostNetwork] = for {
    hostname <- Gen.alphaStr
    portMappings <- Gen.option(Gen.listOf(portMappingGen))
  } yield HostNetwork(hostname, portMappings)

  val uriGen: Gen[Uri] = for {
    value <- Gen.alphaStr
    output_file <- Gen.option(Gen.alphaStr)
  } yield Uri(value, output_file)

  val containerRunDefinitionGen: Gen[ContainerRunDefinition] = for {
    cmd <- Gen.option(Gen.alphaStr)
    args <- Gen.option(Gen.listOf(Gen.alphaStr))
    container <- containerDefinitionGen
    resources <- resourceGen
    cpus <- Gen.option(resources.cpus)
    mem <- Gen.option(resources.memory.inMegabytes)
    env <- Gen.option(Gen.listOf(envGen))
    uris <- Gen.option(Gen.listOf(uriGen))
    labels <- Gen.option(Gen.mapOf(kvGen))
    constraints <- Gen.option(Gen.listOf(constraintGen))
  } yield ContainerRunDefinition(cmd, args, container, cpus, mem, env, uris, labels, constraints)

  implicit lazy val arbContainerRunDefinition: Arbitrary[ContainerRunDefinition] = Arbitrary(containerRunDefinitionGen)

  def containerRunGen: Gen[ContainerRun] = for {
    id <- Gen.uuid
    millis <- Gen.chooseNum(new DateTime(2015, 1, 1, 0, 0).getMillis, new DateTime(2016, 1, 1, 0, 0).getMillis)
    time = new DateTime(millis)
    state <- Gen.oneOf(Seq(ContainerRunState.Finished, ContainerRunState.Failed, ContainerRunState.Running))
    definition <- arbitrary[ContainerRunDefinition]
    network <- Gen.option(hostNetworkGen)
  } yield ContainerRun(id, state, time, definition, network)

  implicit lazy val arbContainerRun: Arbitrary[ContainerRun] = Arbitrary(containerRunGen)
}
