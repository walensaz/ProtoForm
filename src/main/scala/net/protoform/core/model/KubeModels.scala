package net.protoform.core.model

enum KubeResourceType(val cliPrefix: String, val displayLabel: String) {
  case Service extends KubeResourceType("svc", "Service")
  case Pod extends KubeResourceType("pod", "Pod")
}

final case class KubeTargetCandidate(
  namespace: String,
  resourceType: KubeResourceType,
  name: String,
  ports: List[Int]
)

final case class KubeTargetSpec(
  context: Option[String],
  namespace: String,
  resourceType: KubeResourceType,
  resourceName: String,
  remotePort: Int,
  localPort: Int = 0
)

final case class ActivePortForward(
  id: String,
  spec: KubeTargetSpec,
  localPort: Int,
  startedAt: Long = System.currentTimeMillis()
) {
  def endpointLabel: String = s"127.0.0.1:$localPort"
}
