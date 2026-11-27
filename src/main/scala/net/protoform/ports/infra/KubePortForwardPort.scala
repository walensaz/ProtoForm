package net.protoform.ports.infra

import net.protoform.core.error.DomainError
import net.protoform.core.model.{ActivePortForward, KubeTargetCandidate, KubeTargetSpec}

trait KubePortForwardPort {
  def listContexts(): Either[DomainError, List[String]]
  def listNamespaces(context: Option[String]): Either[DomainError, List[String]]
  def listTargets(context: Option[String], namespace: String): Either[DomainError, List[KubeTargetCandidate]]
  def startForward(spec: KubeTargetSpec): Either[DomainError, ActivePortForward]
  def stopForward(id: String): Unit
  def activeForwards: List[ActivePortForward]
  def stopAll(): Unit
}
