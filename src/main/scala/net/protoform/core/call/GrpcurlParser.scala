package net.protoform.core.call

import net.protoform.core.auth.{AuthPreset, MetadataHeader}

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

final case class ParsedGrpcurlCommand(
  endpoint: Option[String],
  useTls: Boolean,
  insecureSkipTlsVerify: Boolean,
  caCertPath: Option[String],
  clientCertPath: Option[String],
  clientKeyPath: Option[String],
  overrideAuthority: Option[String],
  headers: List[MetadataHeader],
  requestJson: String,
  fullMethodName: Option[String],
  protoFiles: List[String],
  importPaths: List[String]
) {
  def extractAuthAndHeaders: (AuthPreset, List[MetadataHeader]) = {
    val authIdx = headers.indexWhere(_.key.equalsIgnoreCase("authorization"))
    if (authIdx < 0) {
      (AuthPreset.None, headers)
    } else {
      val authVal = headers(authIdx).value.trim
      val remaining = headers.patch(authIdx, Nil, 1)
      if (authVal.regionMatches(true, 0, "Bearer ", 0, 7)) {
        (AuthPreset.BearerToken(authVal.substring(7).trim), remaining)
      } else if (authVal.regionMatches(true, 0, "Basic ", 0, 6)) {
        val decodedOpt = try {
          val raw = new String(Base64.getDecoder.decode(authVal.substring(6).trim), StandardCharsets.UTF_8)
          val colon = raw.indexOf(':')
          if (colon >= 0) Some((raw.substring(0, colon), raw.substring(colon + 1))) else None
        } catch {
          case NonFatal(_) => None
        }
        decodedOpt match {
          case Some((u, p)) => (AuthPreset.BasicAuth(u, p), remaining)
          case None         => (AuthPreset.None, headers)
        }
      } else {
        (AuthPreset.None, headers)
      }
    }
  }
}

object GrpcurlParser {

  def parse(rawCommand: String): Either[String, ParsedGrpcurlCommand] = {
    val cleaned = Option(rawCommand).getOrElse("")
      .replaceAll("\\\\\\r?\\n", " ")
      .replaceAll("`\\r?\\n", " ")
      .trim

    if (cleaned.isEmpty) {
      return Left("Paste a grpcurl command to import.")
    }

    tokenize(cleaned).flatMap(parseTokens)
  }

  private def parseTokens(rawTokens: List[String]): Either[String, ParsedGrpcurlCommand] = {
    val tokens = rawTokens.headOption match {
      case Some(head) if head.equalsIgnoreCase("grpcurl") || head.equalsIgnoreCase("grpcurl.exe") =>
        rawTokens.tail
      case _ => rawTokens
    }

    if (tokens.isEmpty) {
      return Left("No grpcurl flags or target endpoint found.")
    }

    var useTls = true
    var insecure = false
    var caCert: Option[String] = None
    var clientCert: Option[String] = None
    var clientKey: Option[String] = None
    var authority: Option[String] = None
    var requestJson = "{}"
    val headers = ListBuffer.empty[MetadataHeader]
    val protoFiles = ListBuffer.empty[String]
    val importPaths = ListBuffer.empty[String]
    val positionals = ListBuffer.empty[String]

    val boolFlags = Set(
      "plaintext", "insecure", "emit-defaults", "use-reflection",
      "msg-template", "format-error", "allow-unknown-fields",
      "expand-headers", "v", "vv", "version", "help", "h"
    )
    val valueFlags = Set(
      "H", "rpc-header", "reflect-header", "d", "data",
      "cacert", "cert", "key", "authority", "servername",
      "proto", "protoset", "import-path", "format",
      "max-time", "connect-timeout", "max-msg-sz", "keepalive-time"
    )

    var errorOpt: Option[String] = None
    var i = 0
    while (i < tokens.size && errorOpt.isEmpty) {
      val tok = tokens(i)
      if (tok == "--") {
        positionals ++= tokens.drop(i + 1)
        i = tokens.size
      } else if (tok.startsWith("-") && tok.length > 1) {
        val stripped = if (tok.startsWith("--")) tok.substring(2) else tok.substring(1)
        val eqIdx = stripped.indexOf('=')
        val (flagName, inlineVal) =
          if (eqIdx >= 0) (stripped.substring(0, eqIdx), Some(stripped.substring(eqIdx + 1)))
          else (stripped, None)

        flagName match {
          case "plaintext" =>
            useTls = false
            i += 1
          case "insecure" =>
            insecure = true
            i += 1
          case f if boolFlags.contains(f) =>
            i += 1
          case f if valueFlags.contains(f) =>
            val valueOpt = inlineVal.orElse {
              if (i + 1 < tokens.size) {
                i += 1
                Some(tokens(i))
              } else None
            }
            valueOpt match {
              case None =>
                errorOpt = Some(s"Flag '-$flagName' is missing a value.")
              case Some(value) =>
                f match {
                  case "H" | "rpc-header" | "reflect-header" =>
                    val colon = value.indexOf(':')
                    if (colon <= 0) {
                      errorOpt = Some(s"Invalid header '$value'. Expected 'Key: Value'.")
                    } else {
                      headers += MetadataHeader(value.substring(0, colon).trim, value.substring(colon + 1).trim, enabled = true)
                    }
                  case "d" | "data" =>
                    if (value.trim != "@") requestJson = value
                  case "cacert" =>
                    caCert = Some(value)
                  case "cert" =>
                    clientCert = Some(value)
                  case "key" =>
                    clientKey = Some(value)
                  case "authority" | "servername" =>
                    authority = Some(value)
                  case "proto" =>
                    protoFiles += value
                  case "import-path" =>
                    importPaths += value
                  case _ => ()
                }
            }
            i += 1
          case _ =>
            i += 1
        }
      } else {
        positionals += tok
        i += 1
      }
    }

    errorOpt match {
      case Some(err) =>
        Left(err)
      case None =>
        val nonVerbPositionals = positionals.filterNot(p => p == "list" || p == "describe").toList
        val endpointOpt = nonVerbPositionals.headOption.map(_.trim).filter(_.nonEmpty)
        val methodOpt = nonVerbPositionals.drop(1).headOption.map(normalizeMethodName).filter(_.nonEmpty)

        if (endpointOpt.isEmpty && methodOpt.isEmpty && requestJson == "{}" && headers.isEmpty) {
          Left("Could not extract endpoint, headers, or payload from grpcurl command.")
        } else {
          Right(ParsedGrpcurlCommand(
            endpoint = endpointOpt,
            useTls = useTls,
            insecureSkipTlsVerify = insecure,
            caCertPath = caCert,
            clientCertPath = clientCert,
            clientKeyPath = clientKey,
            overrideAuthority = authority,
            headers = headers.toList,
            requestJson = requestJson,
            fullMethodName = methodOpt,
            protoFiles = protoFiles.toList,
            importPaths = importPaths.toList
          ))
        }
    }
  }

  private def normalizeMethodName(raw: String): String = {
    val clean = raw.trim.stripPrefix("/")
    if (clean.contains("/")) {
      clean
    } else {
      val lastDot = clean.lastIndexOf('.')
      if (lastDot > 0) s"${clean.substring(0, lastDot)}/${clean.substring(lastDot + 1)}" else clean
    }
  }

  private def tokenize(input: String): Either[String, List[String]] = {
    val out = ListBuffer.empty[String]
    val buf = new StringBuilder
    var hasToken = false
    var inSingle = false
    var inDouble = false
    var escaped = false

    var i = 0
    while (i < input.length) {
      val c = input.charAt(i)
      if (escaped) {
        c match {
          case 'n' if inDouble => buf.append('\n')
          case 't' if inDouble => buf.append('\t')
          case other           => buf.append(other)
        }
        escaped = false
        hasToken = true
      } else if (inSingle) {
        if (c == '\'') {
          inSingle = false
        } else {
          buf.append(c)
        }
        hasToken = true
      } else if (inDouble) {
        if (c == '\\') {
          escaped = true
        } else if (c == '"') {
          inDouble = false
        } else {
          buf.append(c)
        }
        hasToken = true
      } else {
        c match {
          case '\'' =>
            inSingle = true
            hasToken = true
          case '"' =>
            inDouble = true
            hasToken = true
          case '\\' =>
            escaped = true
            hasToken = true
          case ch if ch.isWhitespace =>
            if (hasToken) {
              out += buf.toString()
              buf.clear()
              hasToken = false
            }
          case ch =>
            buf.append(ch)
            hasToken = true
        }
      }
      i += 1
    }

    if (inSingle || inDouble) {
      Left("Unterminated quote in grpcurl command.")
    } else {
      if (hasToken) out += buf.toString()
      Right(out.toList)
    }
  }
}
