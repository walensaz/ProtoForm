package net.protoform.core.auth

import java.util.Base64
import java.nio.charset.StandardCharsets

final case class MetadataHeader(
  key: String,
  value: String,
  enabled: Boolean = true
) {
  def isBinary: Boolean = key.endsWith("-bin")
}

sealed trait AuthPreset {
  def toHeaders: List[MetadataHeader]
  def presetType: String
  def toDataMap: Map[String, String]
  def requiresDynamicResolution: Boolean = false
}

object AuthPreset {
  case object None extends AuthPreset {
    override def toHeaders: List[MetadataHeader] = Nil
    override def presetType: String = "None"
    override def toDataMap: Map[String, String] = Map.empty
  }

  final case class ApiKey(headerName: String = "x-api-key", key: String) extends AuthPreset {
    override def toHeaders: List[MetadataHeader] =
      if (key.trim.nonEmpty) List(MetadataHeader(headerName.trim, key.trim)) else Nil
    override def presetType: String = "ApiKey"
    override def toDataMap: Map[String, String] = Map("header" -> headerName, "key" -> key)
  }

  final case class BearerToken(token: String) extends AuthPreset {
    override def toHeaders: List[MetadataHeader] =
      if (token.trim.nonEmpty) List(MetadataHeader("authorization", s"Bearer ${token.trim}")) else Nil
    override def presetType: String = "BearerToken"
    override def toDataMap: Map[String, String] = Map("token" -> token)
  }

  final case class BasicAuth(username: String, password: String) extends AuthPreset {
    override def toHeaders: List[MetadataHeader] = {
      if (username.nonEmpty || password.nonEmpty) {
        val encoded = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
        List(MetadataHeader("authorization", s"Basic $encoded"))
      } else Nil
    }
    override def presetType: String = "BasicAuth"
    override def toDataMap: Map[String, String] = Map("username" -> username, "password" -> password)
  }

  final case class GoogleCloud(
    tokenMode: String = "AccessToken",
    serviceAccountJsonPath: Option[String] = scala.None,
    audience: Option[String] = scala.None,
    scopes: List[String] = List("https://www.googleapis.com/auth/cloud-platform")
  ) extends AuthPreset {
    override def toHeaders: List[MetadataHeader] = Nil
    override def presetType: String = "GoogleCloud"
    override def requiresDynamicResolution: Boolean = true
    override def toDataMap: Map[String, String] = Map(
      "tokenMode" -> tokenMode,
      "serviceAccountJsonPath" -> serviceAccountJsonPath.getOrElse(""),
      "audience" -> audience.getOrElse(""),
      "scopes" -> scopes.mkString(",")
    )
  }

  final case class SignedJwt(
    algorithm: String = "HS256",
    keyOrSecret: String = "",
    issuer: String = "",
    subject: String = "",
    audience: String = "",
    customClaimsJson: String = "{}",
    ttlSeconds: Long = 3600L
  ) extends AuthPreset {
    override def toHeaders: List[MetadataHeader] = Nil
    override def presetType: String = "SignedJwt"
    override def requiresDynamicResolution: Boolean = true
    override def toDataMap: Map[String, String] = Map(
      "algorithm" -> algorithm,
      "keyOrSecret" -> keyOrSecret,
      "issuer" -> issuer,
      "subject" -> subject,
      "audience" -> audience,
      "customClaimsJson" -> customClaimsJson,
      "ttlSeconds" -> ttlSeconds.toString
    )
  }

  final case class Custom(headers: List[MetadataHeader]) extends AuthPreset {
    override def toHeaders: List[MetadataHeader] = headers.filter(_.enabled)
    override def presetType: String = "Custom"
    override def toDataMap: Map[String, String] = Map.empty
  }
}

final case class AuthProfile(
  id: String,
  name: String,
  preset: AuthPreset,
  customHeaders: List[MetadataHeader] = Nil,
  description: String = "",
  isGlobal: Boolean = false
) {
  def allHeaders: List[MetadataHeader] = preset.toHeaders ++ customHeaders.filter(_.enabled)
}
