package net.protoform.core

import net.protoform.core.call.{StreamScriptParser, WellKnownTypeHelper}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WellKnownAndStreamHelpersSpec extends AnyFunSuite with Matchers {

  test("WellKnownTypeHelper normalizes Duration units and validates ISO-8601 Timestamps") {
    WellKnownTypeHelper.normalizeDuration("150ms") shouldBe Right("0.15s")
    WellKnownTypeHelper.normalizeDuration("5s") shouldBe Right("5s")
    WellKnownTypeHelper.normalizeDuration("2m") shouldBe Right("120s")
    WellKnownTypeHelper.normalizeDuration("1.5") shouldBe Right("1.5s")
    WellKnownTypeHelper.normalizeDuration("bad-val").isLeft shouldBe true

    WellKnownTypeHelper.validateTimestamp("2026-11-26T18:00:00Z") shouldBe Right("2026-11-26T18:00:00Z")
    WellKnownTypeHelper.validateTimestamp("not-a-date").isLeft shouldBe true
  }

  test("StreamScriptParser parses both JSONL scripts with comments and JSON arrays") {
    val jsonl =
      """# smoke sequence
        |{"text": "one", "count": 1}
        |
        |// second chunk
        |{"text": "two", "count": 2}""".stripMargin
    val parsedJsonl = StreamScriptParser.parse(jsonl).fold(fail(_), identity)
    parsedJsonl shouldBe List("""{"text":"one","count":1}""", """{"text":"two","count":2}""")

    val jsonArr = """[{"text":"a"},{"text":"b"}]"""
    val parsedArr = StreamScriptParser.parse(jsonArr).fold(fail(_), identity)
    parsedArr shouldBe List("""{"text":"a"}""", """{"text":"b"}""")

    StreamScriptParser.parse("   ").isLeft shouldBe true
    StreamScriptParser.parse("""[{"bad": 1}, "not-obj"]""").isLeft shouldBe true
    StreamScriptParser.parse("""{"ok":1}
                                |{invalid""".stripMargin).isLeft shouldBe true
  }
}
