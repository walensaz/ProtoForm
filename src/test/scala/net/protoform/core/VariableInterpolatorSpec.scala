package net.protoform.core

import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.core.workspace.VariableInterpolator
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class VariableInterpolatorSpec extends AnyFunSuite with Matchers {

  test("interpolates {{var}} placeholders including special regex characters in replacement values") {
    val vars = Map("host" -> "api.staging", "token" -> "$ecret\\tok", "user_id" -> "u-42")
    val res = VariableInterpolator.interpolate(
      """{"endpoint": "{{ host }}", "id": "{{user_id}}", "missing": "{{unknown_var}}"}""",
      vars
    )
    res.value shouldBe """{"endpoint": "api.staging", "id": "u-42", "missing": "{{unknown_var}}"}"""
    res.unresolvedKeys shouldBe List("unknown_var")

    val auth = VariableInterpolator.interpolatePreset(AuthPreset.BearerToken("{{token}}"), vars)
    auth shouldBe AuthPreset.BearerToken("$ecret\\tok")

    val headers = VariableInterpolator.interpolateHeaders(
      List(MetadataHeader("x-user", "{{user_id}}", enabled = true)),
      vars
    )
    headers shouldBe List(MetadataHeader("x-user", "u-42", enabled = true))
  }
}
