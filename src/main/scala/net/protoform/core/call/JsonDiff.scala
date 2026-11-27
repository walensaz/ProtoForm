package net.protoform.core.call

import com.google.gson.{GsonBuilder, JsonElement, JsonParser}

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

enum DiffKind {
  case Same
  case Added
  case Removed
  case Changed

  def label: String = this match {
    case Same    => "SAME"
    case Added   => "ADDED"
    case Removed => "REMOVED"
    case Changed => "CHANGED"
  }
}

final case class DiffLine(
  kind: DiffKind,
  leftLineNum: Option[Int],
  rightLineNum: Option[Int],
  leftText: String,
  rightText: String
)

final case class JsonFieldDelta(
  path: String,
  kind: DiffKind,
  leftValue: String,
  rightValue: String
)

object JsonDiff {
  private val prettyGson = new GsonBuilder().setPrettyPrinting().serializeNulls().create()

  def normalizeForDisplay(raw: String): String = {
    val trimmed = Option(raw).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) ""
    else {
      try {
        prettyGson.toJson(JsonParser.parseString(trimmed))
      } catch {
        case NonFatal(_) => raw
      }
    }
  }

  def diffLines(leftRaw: String, rightRaw: String): List[DiffLine] = {
    val leftLines = splitLines(normalizeForDisplay(leftRaw))
    val rightLines = splitLines(normalizeForDisplay(rightRaw))
    val m = leftLines.length
    val n = rightLines.length

    val dp = Array.ofDim[Int](m + 1, n + 1)
    for (i <- m - 1 to 0 by -1; j <- n - 1 to 0 by -1) {
      if (leftLines(i) == rightLines(j)) {
        dp(i)(j) = dp(i + 1)(j + 1) + 1
      } else {
        dp(i)(j) = Math.max(dp(i + 1)(j), dp(i)(j + 1))
      }
    }

    val rawOps = ListBuffer.empty[(DiffKind, Option[Int], Option[Int], String, String)]
    var i = 0
    var j = 0
    while (i < m && j < n) {
      if (leftLines(i) == rightLines(j)) {
        rawOps += ((DiffKind.Same, Some(i + 1), Some(j + 1), leftLines(i), rightLines(j)))
        i += 1
        j += 1
      } else if (dp(i + 1)(j) >= dp(i)(j + 1)) {
        rawOps += ((DiffKind.Removed, Some(i + 1), None, leftLines(i), ""))
        i += 1
      } else {
        rawOps += ((DiffKind.Added, None, Some(j + 1), "", rightLines(j)))
        j += 1
      }
    }
    while (i < m) {
      rawOps += ((DiffKind.Removed, Some(i + 1), None, leftLines(i), ""))
      i += 1
    }
    while (j < n) {
      rawOps += ((DiffKind.Added, None, Some(j + 1), "", rightLines(j)))
      j += 1
    }

    // Pair adjacent Removed + Added runs into side-by-side Changed lines.
    val result = ListBuffer.empty[DiffLine]
    var idx = 0
    val ops = rawOps.toList
    while (idx < ops.size) {
      if (ops(idx)._1 == DiffKind.Removed) {
        val remStart = idx
        while (idx < ops.size && ops(idx)._1 == DiffKind.Removed) idx += 1
        val addStart = idx
        while (idx < ops.size && ops(idx)._1 == DiffKind.Added) idx += 1

        val rems = ops.slice(remStart, addStart)
        val adds = ops.slice(addStart, idx)
        val paired = Math.min(rems.size, adds.size)
        for (k <- 0 until paired) {
          result += DiffLine(DiffKind.Changed, rems(k)._2, adds(k)._3, rems(k)._4, adds(k)._5)
        }
        rems.drop(paired).foreach { r => result += DiffLine(DiffKind.Removed, r._2, None, r._4, "") }
        adds.drop(paired).foreach { a => result += DiffLine(DiffKind.Added, None, a._3, "", a._5) }
      } else {
        val op = ops(idx)
        result += DiffLine(op._1, op._2, op._3, op._4, op._5)
        idx += 1
      }
    }
    result.toList
  }

  def diffJsonStructure(leftRaw: String, rightRaw: String): Either[String, List[JsonFieldDelta]] = {
    try {
      val leftEl = parseOrNull(leftRaw)
      val rightEl = parseOrNull(rightRaw)
      val deltas = ListBuffer.empty[JsonFieldDelta]
      compareElements("$", leftEl, rightEl, deltas)
      Right(deltas.toList)
    } catch {
      case NonFatal(e) =>
        Left(Option(e.getMessage).getOrElse("Invalid JSON for structural comparison"))
    }
  }

  private def parseOrNull(raw: String): Option[JsonElement] = {
    val trimmed = Option(raw).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) None else Option(JsonParser.parseString(trimmed))
  }

  private def compareElements(
    path: String,
    leftOpt: Option[JsonElement],
    rightOpt: Option[JsonElement],
    out: ListBuffer[JsonFieldDelta]
  ): Unit = {
    (leftOpt, rightOpt) match {
      case (None, None) => ()
      case (None, Some(r)) =>
        out += JsonFieldDelta(path, DiffKind.Added, "", formatCompact(r))
      case (Some(l), None) =>
        out += JsonFieldDelta(path, DiffKind.Removed, formatCompact(l), "")
      case (Some(l), Some(r)) if l == r => ()
      case (Some(l), Some(r)) if l.isJsonObject && r.isJsonObject =>
        val lObj = l.getAsJsonObject
        val rObj = r.getAsJsonObject
        val allKeys = (lObj.keySet().asScala ++ rObj.keySet().asScala).toList.distinct.sorted
        allKeys.foreach { key =>
          val lv = if (lObj.has(key)) Some(lObj.get(key)) else None
          val rv = if (rObj.has(key)) Some(rObj.get(key)) else None
          compareElements(s"$path.$key", lv, rv, out)
        }
      case (Some(l), Some(r)) if l.isJsonArray && r.isJsonArray =>
        val lArr = l.getAsJsonArray
        val rArr = r.getAsJsonArray
        val maxLen = Math.max(lArr.size(), rArr.size())
        for (i <- 0 until maxLen) {
          val lv = if (i < lArr.size()) Some(lArr.get(i)) else None
          val rv = if (i < rArr.size()) Some(rArr.get(i)) else None
          compareElements(s"$path[$i]", lv, rv, out)
        }
      case (Some(l), Some(r)) =>
        out += JsonFieldDelta(path, DiffKind.Changed, formatCompact(l), formatCompact(r))
    }
  }

  private def formatCompact(el: JsonElement): String = {
    if (el == null || el.isJsonNull) "null"
    else if (el.isJsonPrimitive) el.toString
    else prettyGson.toJson(el).replaceAll("\\s+", " ")
  }

  private def splitLines(s: String): Vector[String] = {
    if (s.isEmpty) Vector.empty else s.split("\r?\n", -1).toVector
  }
}
