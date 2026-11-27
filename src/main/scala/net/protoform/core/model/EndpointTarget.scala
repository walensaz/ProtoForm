package net.protoform.core.model

final case class EndpointTarget(
  id: TargetId = TargetId.random(),
  name: String,
  host: String,
  port: Int,
  useTls: Boolean = false,
  overrideAuthority: Option[String] = None,
  tlsCertPath: Option[String] = None,
  insecureSkipTlsVerify: Boolean = false,
  clientCertPath: Option[String] = None,
  clientKeyPath: Option[String] = None
) {
  // Bracket IPv6 literals for URI/authority formatting while keeping `host` raw for NettyChannelBuilder.forAddress.
  private def formattedHost: String = if (host.contains(":")) s"[$host]" else host
  def uriString: String = s"${if (useTls) "https" else "http"}://$formattedHost:$port"
  def authority: String = overrideAuthority.getOrElse(s"$formattedHost:$port")
  def displayLabel: String = s"$formattedHost:$port ${if (useTls) "(TLS)" else "(Plaintext)"}"
}

object EndpointTarget {
  def parse(input: String, useTls: Boolean = false): Either[String, EndpointTarget] = {
    val cleaned = input.trim
      .stripPrefix("http://")
      .stripPrefix("https://")
      .replaceAll("/+$", "")
      .trim

    if (cleaned.isEmpty) {
      Left("Invalid endpoint format. Expected 'host:port' e.g. 'localhost:50051'")
    } else if (cleaned.startsWith("[")) {
      parseBracketedIpv6(cleaned, useTls)
    } else {
      parseHostPort(cleaned, useTls)
    }
  }

  private def parseBracketedIpv6(cleaned: String, useTls: Boolean): Either[String, EndpointTarget] = {
    val closeIdx = cleaned.indexOf(']')
    if (closeIdx < 0) {
      Left("Invalid IPv6 endpoint format. Missing closing ']'.")
    } else {
      val host = cleaned.substring(1, closeIdx).trim
      if (host.isEmpty) {
        Left("Invalid endpoint format: host cannot be empty.")
      } else {
        val rest = cleaned.substring(closeIdx + 1).trim
        if (rest.isEmpty) {
          val defaultPort = if (useTls) 443 else 80
          Right(EndpointTarget(
            name = s"[$host]:$defaultPort",
            host = host,
            port = defaultPort,
            useTls = useTls
          ))
        } else if (rest.startsWith(":")) {
          val portStr = rest.substring(1).trim
          parsePort(portStr).map { port =>
            EndpointTarget(
              name = s"[$host]:$port",
              host = host,
              port = port,
              useTls = useTls
            )
          }
        } else {
          Left("Invalid endpoint format. Expected '[ipv6]:port' e.g. '[::1]:50051'")
        }
      }
    }
  }

  private def parseHostPort(cleaned: String, useTls: Boolean): Either[String, EndpointTarget] = {
    val parts = cleaned.split(":", -1)
    if (parts.length == 2) {
      val host = parts(0).trim
      if (host.isEmpty) {
        Left("Invalid endpoint format: host cannot be empty.")
      } else {
        parsePort(parts(1).trim).map { port =>
          EndpointTarget(
            name = s"$host:$port",
            host = host,
            port = port,
            useTls = useTls
          )
        }
      }
    } else if (parts.length == 1) {
      val host = parts(0).trim
      if (host.isEmpty) {
        Left("Invalid endpoint format: host cannot be empty.")
      } else {
        val defaultPort = if (useTls) 443 else 80
        Right(EndpointTarget(
          name = s"$host:$defaultPort",
          host = host,
          port = defaultPort,
          useTls = useTls
        ))
      }
    } else {
      Left("Invalid endpoint format. Expected 'host:port' e.g. 'localhost:50051' or '[::1]:50051'")
    }
  }

  private def parsePort(portStr: String): Either[String, Int] = {
    portStr.toIntOption match {
      case Some(port) if port > 0 && port <= 65535 => Right(port)
      case _ => Left(s"Invalid port: '$portStr'. Must be between 1 and 65535.")
    }
  }
}
