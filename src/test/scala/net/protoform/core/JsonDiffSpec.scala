package net.protoform.core

import net.protoform.core.call.{DiffKind, JsonDiff}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class JsonDiffSpec extends AnyFunSuite with Matchers {

  test("diffLines and diffJsonStructure detect added, removed, and changed JSON fields") {
    val left = """{"id": "1", "status": "PENDING", "legacy": true, "items": [10, 20]}"""
    val right = """{"id": "1", "status": "PAID", "currency": "USD", "items": [10, 25]}"""

    val lines = JsonDiff.diffLines(left, right)
    lines.exists(_.kind == DiffKind.Same) shouldBe true
    lines.exists(l => l.kind == DiffKind.Changed || l.kind == DiffKind.Added || l.kind == DiffKind.Removed) shouldBe true

    val deltas = JsonDiff.diffJsonStructure(left, right).fold(fail(_), identity)
    deltas.map(d => (d.path, d.kind)).toSet shouldBe Set(
      ("$.currency", DiffKind.Added),
      ("$.items[1]", DiffKind.Changed),
      ("$.legacy", DiffKind.Removed),
      ("$.status", DiffKind.Changed)
    )
  }
}
