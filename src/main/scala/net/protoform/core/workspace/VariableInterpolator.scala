package net.protoform.core.workspace

import net.protoform.core.auth.{AuthPreset, MetadataHeader}

import java.util.regex.{Matcher, Pattern}
import scala.collection.mutable.ListBuffer

final case class InterpolationResult(
  value: String,
  unresolvedKeys: List[String]
)

object VariableInterpolator {
  private val VarPattern = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*\\}\\}")

  def interpolate(raw: String, variables: Map[String, String]): InterpolationResult = {
    if (raw == null || raw.isEmpty) {
      InterpolationResult("", Nil)
    } else {
      val matcher = VarPattern.matcher(raw)
      val sb = new StringBuffer(raw.length)
      val missing = ListBuffer.empty[String]
      while (matcher.find()) {
        val key = matcher.group(1)
        variables.get(key) match {
          case Some(replacement) =>
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
          case None =>
            if (!missing.contains(key)) missing += key
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)))
        }
      }
      matcher.appendTail(sb)
      InterpolationResult(sb.toString, missing.toList)
    }
  }

  def interpolateString(raw: String, variables: Map[String, String]): String = {
    interpolate(raw, variables).value
  }

  def interpolatePreset(preset: AuthPreset, variables: Map[String, String]): AuthPreset = {
    if (variables.isEmpty) preset
    else preset match {
      case AuthPreset.None => AuthPreset.None
      case AuthPreset.ApiKey(h, k) =>
        AuthPreset.ApiKey(interpolateString(h, variables), interpolateString(k, variables))
      case AuthPreset.BearerToken(tok) =>
        AuthPreset.BearerToken(interpolateString(tok, variables))
      case AuthPreset.BasicAuth(u, p) =>
        AuthPreset.BasicAuth(interpolateString(u, variables), interpolateString(p, variables))
      case g: AuthPreset.GoogleCloud =>
        g.copy(
          serviceAccountJsonPath = g.serviceAccountJsonPath.map(p => interpolateString(p, variables)),
          audience = g.audience.map(a => interpolateString(a, variables))
        )
      case j: AuthPreset.SignedJwt =>
        j.copy(
          keyOrSecret = interpolateString(j.keyOrSecret, variables),
          issuer = interpolateString(j.issuer, variables),
          subject = interpolateString(j.subject, variables),
          audience = interpolateString(j.audience, variables),
          customClaimsJson = interpolateString(j.customClaimsJson, variables)
        )
      case AuthPreset.Custom(headers) =>
        AuthPreset.Custom(interpolateHeaders(headers, variables))
    }
  }

  def interpolateHeaders(headers: List[MetadataHeader], variables: Map[String, String]): List[MetadataHeader] = {
    if (variables.isEmpty) headers
    else headers.map { h =>
      h.copy(
        key = interpolateString(h.key, variables),
        value = interpolateString(h.value, variables)
      )
    }
  }

  def findUnresolved(inputs: Seq[String], variables: Map[String, String]): List[String] = {
    inputs.flatMap(s => interpolate(s, variables).unresolvedKeys).distinct.toList
  }
}
