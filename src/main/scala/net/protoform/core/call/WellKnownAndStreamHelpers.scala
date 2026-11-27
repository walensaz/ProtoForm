package net.protoform.core.call

import com.google.gson.{GsonBuilder, JsonParser}

import java.time.Instant
import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.NonFatal

object WellKnownTypeHelper {
  private val DurationPattern = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*(ms|s|m|h)?\\s*$", Pattern.CASE_INSENSITIVE)

  def normalizeDuration(raw: String): Either[String, String] = {
    val trimmed = Option(raw).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      Right("0s")
    } else {
      val m = DurationPattern.matcher(trimmed)
      if (!m.matches()) {
        Left("Expected duration like '5s', '150ms', '1.5s', or '2m'")
      } else {
        Try {
          val num = BigDecimal(m.group(1))
          val unit = Option(m.group(2)).map(_.toLowerCase).getOrElse("s")
          val seconds = unit match {
            case "ms" => num / 1000
            case "m"  => num * 60
            case "h"  => num * 3600
            case _    => num
          }
          val stripped = seconds.bigDecimal.stripTrailingZeros()
          val plain = if (stripped.scale() <= 0) stripped.toPlainString else stripped.toPlainString
          s"${plain}s"
        }.toEither.left.map(_ => "Invalid numeric duration")
      }
    }
  }

  def validateTimestamp(raw: String): Either[String, String] = {
    val trimmed = Option(raw).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      Right("1970-01-01T00:00:00Z")
    } else {
      Try(Instant.parse(trimmed).toString).toEither.left.map(_ => "Expected ISO-8601 UTC timestamp (e.g. 2026-11-26T18:00:00Z)")
    }
  }
}

object StreamScriptParser {
  private val compactGson = new GsonBuilder().create()

  def parse(rawScript: String): Either[String, List[String]] = {
    val content = Option(rawScript).map(_.trim).getOrElse("")
    if (content.isEmpty) {
      return Left("Enter one or more JSON objects (JSONL) or a JSON array to replay.")
    }

    if (content.startsWith("[")) {
      try {
        val el = JsonParser.parseString(content)
        if (!el.isJsonArray) {
          Left("Expected a JSON array of request objects.")
        } else {
          val items = ListBuffer.empty[String]
          val arr = el.getAsJsonArray
          var errorOpt: Option[String] = None
          var i = 0
          while (i < arr.size() && errorOpt.isEmpty) {
            val item = arr.get(i)
            if (item == null || !item.isJsonObject) {
              errorOpt = Some(s"Array item #${i + 1} must be a JSON object.")
            } else {
              items += compactGson.toJson(item)
            }
            i += 1
          }
          errorOpt match {
            case Some(err)             => Left(err)
            case None if items.isEmpty => Left("JSON array contains 0 request objects.")
            case None                  => Right(items.toList)
          }
        }
      } catch {
        case NonFatal(e) =>
          Left(s"Invalid JSON array: ${Option(e.getMessage).getOrElse("parse error")}")
      }
    } else {
      val lines = content.split("\r?\n").toList.zipWithIndex
      val items = ListBuffer.empty[String]
      var errorOpt: Option[String] = None
      lines.foreach { case (line, idx) =>
        if (errorOpt.isEmpty) {
          val t = line.trim
          if (t.nonEmpty && !t.startsWith("#") && !t.startsWith("//")) {
            try {
              val el = JsonParser.parseString(t)
              if (el == null || !el.isJsonObject) {
                errorOpt = Some(s"Line ${idx + 1} must be a JSON object.")
              } else {
                items += compactGson.toJson(el)
              }
            } catch {
              case NonFatal(e) =>
                errorOpt = Some(s"Line ${idx + 1} invalid JSON: ${Option(e.getMessage).getOrElse("parse error")}")
            }
          }
        }
      }
      errorOpt match {
        case Some(err) => Left(err)
        case None if items.isEmpty => Left("No JSON request lines found in script.")
        case None => Right(items.toList)
      }
    }
  }
}
