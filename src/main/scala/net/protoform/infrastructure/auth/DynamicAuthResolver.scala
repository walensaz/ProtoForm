package net.protoform.infrastructure.auth

import com.google.auth.oauth2.{GoogleCredentials, IdTokenCredentials, IdTokenProvider}
import com.google.gson.{Gson, JsonObject, JsonParser}
import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.core.error.DomainError
import net.protoform.ports.auth.DynamicAuthTokenPort

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.{KeyFactory, PrivateKey, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class DynamicAuthResolver extends DynamicAuthTokenPort {
  private val gson = new Gson()
  private val b64Url = Base64.getUrlEncoder.withoutPadding()

  private case class CachedToken(token: String, expiresAtMs: Long)
  private val gcpCache = new ConcurrentHashMap[String, CachedToken]()

  override def resolveHeaders(preset: AuthPreset): Either[DomainError, List[MetadataHeader]] = {
    preset match {
      case g: AuthPreset.GoogleCloud => resolveGoogleCloud(g)
      case j: AuthPreset.SignedJwt   => resolveSignedJwt(j)
      case other                     => Right(other.toHeaders)
    }
  }

  private def resolveGoogleCloud(g: AuthPreset.GoogleCloud): Either[DomainError, List[MetadataHeader]] = {
    val cacheKey = s"${g.tokenMode}|${g.serviceAccountJsonPath.getOrElse("")}|${g.audience.getOrElse("")}|${g.scopes.mkString(",")}"
    val now = System.currentTimeMillis()
    Option(gcpCache.get(cacheKey)).filter(_.expiresAtMs - now > 60000L) match {
      case Some(cached) =>
        Right(List(MetadataHeader("authorization", s"Bearer ${cached.token}")))
      case None =>
        try {
          val credsOrErr: Either[DomainError, GoogleCredentials] =
            g.serviceAccountJsonPath.map(_.trim).filter(_.nonEmpty) match {
              case Some(pathStr) =>
                val p = Paths.get(pathStr)
                if (!Files.exists(p)) {
                  Left(DomainError.RpcExecutionFailed(s"Google service account JSON not found: $pathStr", -1))
                } else {
                  val stream = Files.newInputStream(p)
                  try { Right(GoogleCredentials.fromStream(stream)) } finally { stream.close() }
                }
              case None =>
                Right(GoogleCredentials.getApplicationDefault())
            }

          credsOrErr.flatMap { baseCreds =>
            if (g.tokenMode.equalsIgnoreCase("IdToken")) {
              g.audience.map(_.trim).filter(_.nonEmpty) match {
                case None =>
                  Left(DomainError.RpcExecutionFailed("Audience is required for Google Cloud ID Token mode.", -1))
                case Some(aud) =>
                  baseCreds match {
                    case provider: IdTokenProvider =>
                      val idCreds = IdTokenCredentials.newBuilder()
                        .setIdTokenProvider(provider)
                        .setTargetAudience(aud)
                        .build()
                      idCreds.refreshIfExpired()
                      Option(idCreds.getIdToken) match {
                        case Some(token) =>
                          val expMs = Option(token.getExpirationTime).map(_.getTime).getOrElse(now + 3000000L)
                          gcpCache.put(cacheKey, CachedToken(token.getTokenValue, expMs))
                          Right(List(MetadataHeader("authorization", s"Bearer ${token.getTokenValue}")))
                        case None =>
                          Left(DomainError.RpcExecutionFailed("Failed to obtain Google Cloud ID token.", -1))
                      }
                    case _ =>
                      Left(DomainError.RpcExecutionFailed("Loaded Google credentials do not support ID Token minting (use a Service Account JSON or GCE metadata).", -1))
                  }
              }
            } else {
              val scoped = if (g.scopes.nonEmpty && baseCreds.createScopedRequired()) baseCreds.createScoped(g.scopes.asJava) else baseCreds
              scoped.refreshIfExpired()
              Option(scoped.getAccessToken) match {
                case Some(token) =>
                  val expMs = Option(token.getExpirationTime).map(_.getTime).getOrElse(now + 3000000L)
                  gcpCache.put(cacheKey, CachedToken(token.getTokenValue, expMs))
                  Right(List(MetadataHeader("authorization", s"Bearer ${token.getTokenValue}")))
                case None =>
                  Left(DomainError.RpcExecutionFailed("Failed to obtain Google Cloud access token.", -1))
              }
            }
          }
        } catch {
          case NonFatal(e) =>
            Left(DomainError.RpcExecutionFailed(Option(e.getMessage).getOrElse("Google Cloud auth failed"), -1, Some(e.getClass.getSimpleName)))
        }
    }
  }

  private def resolveSignedJwt(j: AuthPreset.SignedJwt): Either[DomainError, List[MetadataHeader]] = {
    val alg = if (j.algorithm.equalsIgnoreCase("RS256")) "RS256" else "HS256"
    if (j.keyOrSecret.trim.isEmpty) {
      Left(DomainError.RpcExecutionFailed(s"Missing ${if (alg == "RS256") "RSA private key" else "HMAC secret"} for Signed JWT.", -1))
    } else {
      try {
        val headerJson = s"""{"alg":"$alg","typ":"JWT"}"""
        val nowSec = System.currentTimeMillis() / 1000L
        val ttl = Math.max(1L, j.ttlSeconds)

        val payload = new JsonObject()
        val customRaw = Option(j.customClaimsJson).map(_.trim).filter(_.nonEmpty).getOrElse("{}")
        val parsedCustom = JsonParser.parseString(customRaw)
        if (!parsedCustom.isJsonObject) {
          Left(DomainError.RpcExecutionFailed("JWT custom claims must be a JSON object.", -1))
        } else {
          parsedCustom.getAsJsonObject.entrySet().asScala.foreach { entry =>
            payload.add(entry.getKey, entry.getValue)
          }
          payload.addProperty("iat", nowSec)
          payload.addProperty("exp", nowSec + ttl)
          if (j.issuer.trim.nonEmpty) payload.addProperty("iss", j.issuer.trim)
          if (j.subject.trim.nonEmpty) payload.addProperty("sub", j.subject.trim)
          if (j.audience.trim.nonEmpty) payload.addProperty("aud", j.audience.trim)

          val encodedHeader = b64Url.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
          val encodedPayload = b64Url.encodeToString(gson.toJson(payload).getBytes(StandardCharsets.UTF_8))
          val signingInput = s"$encodedHeader.$encodedPayload"

          val signatureBytes: Array[Byte] = alg match {
            case "RS256" =>
              val privKey = parsePkcs8RsaKey(j.keyOrSecret)
              val sig = Signature.getInstance("SHA256withRSA")
              sig.initSign(privKey)
              sig.update(signingInput.getBytes(StandardCharsets.UTF_8))
              sig.sign()
            case _ =>
              val mac = Mac.getInstance("HmacSHA256")
              mac.init(new SecretKeySpec(j.keyOrSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
              mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8))
          }

          val jwt = s"$signingInput.${b64Url.encodeToString(signatureBytes)}"
          Right(List(MetadataHeader("authorization", s"Bearer $jwt")))
        }
      } catch {
        case NonFatal(e) =>
          Left(DomainError.RpcExecutionFailed(s"Signed JWT error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}", -1))
      }
    }
  }

  private def parsePkcs8RsaKey(pemOrPath: String): PrivateKey = {
    val trimmed = pemOrPath.trim
    val rawPem =
      if (!trimmed.contains("BEGIN") && Files.exists(Paths.get(trimmed))) {
        new String(Files.readAllBytes(Paths.get(trimmed)), StandardCharsets.UTF_8)
      } else trimmed
    val cleanBase64 = rawPem
      .replaceAll("-----BEGIN [A-Z ]+-----", "")
      .replaceAll("-----END [A-Z ]+-----", "")
      .replaceAll("\\s+", "")
    val decoded = Base64.getDecoder.decode(cleanBase64)
    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded))
  }
}
