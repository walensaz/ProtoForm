package net.protoform.application

import com.google.gson.{GsonBuilder, JsonArray, JsonObject}
import io.grpc.{Channel, Status}
import net.protoform.core.call.{RpcCallOptions, RpcCallStatus}
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, RpcPattern, ServiceSchema}
import net.protoform.core.workspace.{AuthSerialization, ServiceTemplate, VariableInterpolator}
import net.protoform.ports.auth.DynamicAuthTokenPort

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

final case class SuiteCaseResult(
  template: ServiceTemplate,
  passed: Boolean,
  actualStatusCode: Int,
  actualStatusName: String,
  expectedStatus: String,
  latencyMs: Long,
  requestJsonResolved: String,
  responseBodyJson: String,
  errorMessage: Option[String] = None
)

final case class SuiteRunSummary(
  results: List[SuiteCaseResult],
  totalDurationMs: Long
) {
  def passedCount: Int = results.count(_.passed)
  def failedCount: Int = results.count(!_.passed)
}

object SuiteRunnerService {
  private val prettyGson = new GsonBuilder().setPrettyPrinting().create()

  def statusNameForCode(code: Int): String = {
    if (code < 0) "CLIENT_ERROR"
    else Status.fromCodeValue(code).getCode.name()
  }

  def exportReportMarkdown(summary: SuiteRunSummary): String = {
    val sb = new StringBuilder
    sb.append(s"# ProtoForm QA Suite Report\n\n")
    sb.append(s"- **Passed:** ${summary.passedCount} / ${summary.results.size}\n")
    sb.append(s"- **Failed:** ${summary.failedCount}\n")
    sb.append(s"- **Total Duration:** ${summary.totalDurationMs} ms\n\n")
    sb.append("| Result | Template | Method | Expected | Actual | Latency |\n")
    sb.append("|---|---|---|---|---|---|\n")
    summary.results.foreach { r =>
      val badge = if (r.passed) "PASS" else "FAIL"
      sb.append(s"| $badge | ${r.template.name} | `${r.template.fullMethodName}` | `${r.expectedStatus}` | `${r.actualStatusName}` | ${r.latencyMs} ms |\n")
    }
    sb.toString()
  }

  def exportReportJson(summary: SuiteRunSummary): String = {
    val root = new JsonObject()
    root.addProperty("passed", summary.passedCount)
    root.addProperty("failed", summary.failedCount)
    root.addProperty("total", summary.results.size)
    root.addProperty("totalDurationMs", summary.totalDurationMs)
    val arr = new JsonArray()
    summary.results.foreach { r =>
      val item = new JsonObject()
      item.addProperty("templateId", r.template.id)
      item.addProperty("templateName", r.template.name)
      item.addProperty("method", r.template.fullMethodName)
      item.addProperty("passed", r.passed)
      item.addProperty("expectedStatus", r.expectedStatus)
      item.addProperty("actualStatus", r.actualStatusName)
      item.addProperty("actualStatusCode", r.actualStatusCode)
      item.addProperty("latencyMs", r.latencyMs)
      r.errorMessage.foreach(e => item.addProperty("error", e))
      arr.add(item)
    }
    root.add("results", arr)
    prettyGson.toJson(root)
  }
}

class SuiteRunnerService(
  invocationService: InvocationService,
  dynamicAuthPort: Option[DynamicAuthTokenPort] = None
) {
  import SuiteRunnerService.statusNameForCode

  def runSuite(
    templates: List[ServiceTemplate],
    services: List[ServiceSchema],
    channel: Channel,
    target: EndpointTarget,
    variables: Map[String, String] = Map.empty,
    callOptions: RpcCallOptions = RpcCallOptions(),
    cancelFlag: AtomicBoolean = new AtomicBoolean(false),
    onCaseComplete: SuiteCaseResult => Unit = _ => ()
  )(implicit ec: ExecutionContext): Future[SuiteRunSummary] = {
    val methodsByName: Map[String, MethodSchema] = services.flatMap(_.methods).map(m => m.fullMethodName -> m).toMap
    val suiteStart = System.currentTimeMillis()

    def loop(remaining: List[ServiceTemplate], acc: List[SuiteCaseResult]): Future[List[SuiteCaseResult]] = {
      if (remaining.isEmpty || cancelFlag.get()) {
        Future.successful(acc.reverse)
      } else {
        executeSingle(remaining.head, methodsByName, channel, target, variables, callOptions).flatMap { res =>
          onCaseComplete(res)
          loop(remaining.tail, res :: acc)
        }
      }
    }

    loop(templates, Nil).map { results =>
      SuiteRunSummary(results, System.currentTimeMillis() - suiteStart)
    }
  }

  private def executeSingle(
    tpl: ServiceTemplate,
    methodsByName: Map[String, MethodSchema],
    channel: Channel,
    target: EndpointTarget,
    variables: Map[String, String],
    callOptions: RpcCallOptions
  )(implicit ec: ExecutionContext): Future[SuiteCaseResult] = {
    val expected = tpl.expectedStatus.map(_.trim).filter(_.nonEmpty).getOrElse("OK")
    val resolvedJson = VariableInterpolator.interpolateString(tpl.requestJson, variables)

    methodsByName.get(tpl.fullMethodName) match {
      case None =>
        Future.successful(SuiteCaseResult(
          template = tpl,
          passed = false,
          actualStatusCode = -1,
          actualStatusName = "METHOD_NOT_FOUND",
          expectedStatus = expected,
          latencyMs = 0L,
          requestJsonResolved = resolvedJson,
          responseBodyJson = "",
          errorMessage = Some(s"Method '${tpl.fullMethodName}' is not present in the active schema.")
        ))

      case Some(method) if method.pattern != RpcPattern.Unary =>
        Future.successful(SuiteCaseResult(
          template = tpl,
          passed = false,
          actualStatusCode = -1,
          actualStatusName = "UNSUPPORTED_PATTERN",
          expectedStatus = expected,
          latencyMs = 0L,
          requestJsonResolved = resolvedJson,
          responseBodyJson = "",
          errorMessage = Some(s"Suite runner currently supports Unary RPCs (${method.pattern.label} skipped).")
        ))

      case Some(method) =>
        val rawPreset = AuthSerialization.decodePreset(tpl.authPresetType, tpl.authPresetData)
        val preset = VariableInterpolator.interpolatePreset(rawPreset, variables)
        val customHdrs = VariableInterpolator.interpolateHeaders(tpl.customHeaders.filter(_.enabled), variables)
        val dynamicResult = if (preset.requiresDynamicResolution && dynamicAuthPort.isDefined) {
          dynamicAuthPort.get.resolveHeaders(preset)
        } else {
          Right(preset.toHeaders)
        }

        dynamicResult match {
          case Left(authErr) =>
            Future.successful(SuiteCaseResult(
              template = tpl,
              passed = false,
              actualStatusCode = -1,
              actualStatusName = "AUTH_ERROR",
              expectedStatus = expected,
              latencyMs = 0L,
              requestJsonResolved = resolvedJson,
              responseBodyJson = s"""{"error": "${authErr.message}"}""",
              errorMessage = Some(authErr.message)
            ))
          case Right(presetHeaders) =>
            val headers = presetHeaders ++ customHdrs
            invocationService.invokeUnary(channel, target, method, resolvedJson, headers, callOptions).map {
          case Right(call) =>
            val (code, lat, errDetail) = call.status match {
              case RpcCallStatus.Completed(c, _, l) => (c, l, None)
              case RpcCallStatus.Failed(c, desc, detail) =>
                (c, call.latencyMs.getOrElse(0L), Some(detail.getOrElse(desc)))
              case _ => (0, call.latencyMs.getOrElse(0L), None)
            }
            val actualName = statusNameForCode(code)
            SuiteCaseResult(
              template = tpl,
              passed = actualName.equalsIgnoreCase(expected),
              actualStatusCode = code,
              actualStatusName = actualName,
              expectedStatus = expected,
              latencyMs = lat,
              requestJsonResolved = resolvedJson,
              responseBodyJson = call.responseBodyJson.getOrElse("{}"),
              errorMessage = errDetail
            )

          case Left(err) =>
            SuiteCaseResult(
              template = tpl,
              passed = false,
              actualStatusCode = -1,
              actualStatusName = "CLIENT_ERROR",
              expectedStatus = expected,
              latencyMs = 0L,
              requestJsonResolved = resolvedJson,
              responseBodyJson = s"""{"error": "${err.message}"}""",
              errorMessage = Some(err.message)
            )
        }
        }
    }
  }
}
