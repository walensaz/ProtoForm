package net.protoform.infrastructure

import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}
import net.protoform.core.error.DomainError
import net.protoform.core.workspace.{AppSettings, CallHistoryEntry, EnvironmentConfig, ProjectExportBundle, SavedRequest, SavedWorkbenchTab, ServiceTemplate, WorkspaceData, WorkspaceProject}
import net.protoform.infrastructure.storage.LocalJsonWorkspaceRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.jdk.CollectionConverters._

class WorkspaceRepositorySpec extends AnyFunSuite with Matchers {

  private def withTempDir(testBody: Path => Unit): Unit = {
    val dir = Files.createTempDirectory("protoform-workspace-test-")
    try {
      testBody(dir)
    } finally {
      if (Files.exists(dir)) {
        val stream = Files.walk(dir)
        try {
          stream.sorted(Comparator.reverseOrder()).forEach { p =>
            Files.deleteIfExists(p)
            ()
          }
        } finally {
          stream.close()
        }
      }
    }
  }

  test("loadWorkspace returns default ProtoForm Carbon settings when file does not exist") {
    withTempDir { dir =>
      val repo = new LocalJsonWorkspaceRepository(dir)
      val loaded = repo.loadWorkspace().fold(err => fail(err.message), identity)
      loaded.settings.theme shouldBe "ProtoForm Carbon"
      loaded.settings.showDefaultValues shouldBe true
      loaded.settings.showAdvancedActions shouldBe false
      loaded.settings.showNavPane shouldBe false
      loaded.settings.activeNavSection shouldBe "services"
      loaded.recentEndpoints shouldBe List("localhost:50051", "grpcb.in:9000")
      loaded.projects should have size 1
      loaded.activeProject.name shouldBe "Default"
    }
  }

  test("round-trip serializes settings, templates, payloads, auth profiles (including Custom), savedRequests, and defaultHeaders") {
    withTempDir { dir =>
      val repo = new LocalJsonWorkspaceRepository(dir)
      val template = ServiceTemplate(
        id = "test-tpl-1",
        name = "Create Order Valid",
        description = "Test order with mock items",
        serviceFullName = "order.OrderService",
        methodName = "CreateOrder",
        requestJson = """{"userId": "u-123", "amount": 99.5}""",
        authPresetType = "ApiKey",
        authPresetData = Map("header" -> "x-api-key", "key" -> "secret_abc"),
        customHeaders = List(MetadataHeader("x-correlation-id", "corr-99", enabled = true)),
        tags = List("smoke", "qa"),
        expectedStatus = Some("OK"),
        folderPath = "Orders/Smoke",
        createdAt = 1700000000000L,
        updatedAt = 1700000001000L
      )

      val bearerProfile = AuthProfile(
        id = "prof-1",
        name = "Staging Admin",
        preset = AuthPreset.BearerToken("jwt-token-xyz"),
        customHeaders = List(MetadataHeader("x-tenant", "staging-us", enabled = true)),
        description = "Admin credentials for staging cluster",
        isGlobal = false
      )

      val customProfile = AuthProfile(
        id = "prof-2",
        name = "Custom Headers Auth",
        preset = AuthPreset.Custom(List(
          MetadataHeader("x-custom-auth", "sig-123", enabled = true),
          MetadataHeader("x-disabled-auth", "ignored", enabled = false)
        )),
        customHeaders = List(MetadataHeader("x-trace", "on", enabled = true)),
        description = "Multi-header custom scheme",
        isGlobal = true
      )

      val savedRequest = SavedRequest(
        id = "req-1",
        name = "Health Ping",
        endpoint = "grpcb.in:9000",
        service = "grpc.health.v1.Health",
        method = "Check",
        requestJson = """{"service": ""}""",
        headers = List(
          MetadataHeader("x-request-id", "abc-1", enabled = true),
          MetadataHeader("x-debug", "1", enabled = false)
        )
      )

      val defaultHeaders = List(
        MetadataHeader("x-client", "protoform", enabled = true),
        MetadataHeader("x-env", "dev", enabled = false)
      )

      val historyEntry = CallHistoryEntry(
        id = "hist-1",
        fingerprint = "abc123",
        endpoint = "grpcb.in:9000",
        useTls = true,
        serviceFullName = "order.OrderService",
        methodName = "CreateOrder",
        requestJson = """{"userId": "u-123"}""",
        authPresetType = "BearerToken",
        authPresetData = Map("token" -> "tok"),
        customHeaders = List(MetadataHeader("x-qa", "1")),
        statusCode = 0,
        statusDescription = "OK",
        latencyMs = 42L,
        responsePreview = """{"ok": true}""",
        responseBodyJson = """{"ok": true, "full": "payload"}""",
        executedAt = 1700000002000L
      )

      val envConfig = EnvironmentConfig(
        id = "env-staging",
        name = "staging",
        endpoint = "{{host}}:443",
        useTls = true,
        authProfileId = Some("prof-1"),
        insecureSkipTlsVerify = false,
        customCaCertPath = Some("/certs/staging-ca.pem"),
        clientCertPath = Some("/certs/staging-client.crt"),
        clientKeyPath = Some("/certs/staging-client.key"),
        variables = Map("host" -> "staging.api.internal", "tenant" -> "acme")
      )

      val proj = WorkspaceProject(
        id = "proj-orders",
        name = "Orders",
        recentEndpoints = List("localhost:50051", "grpcb.in:9000"),
        methodPayloads = Map(
          "order.OrderService/CreateOrder" -> """{"userId": "u-123", "amount": 99.5}"""
        ),
        templates = List(template),
        authProfiles = List(bearerProfile),
        savedRequests = List(savedRequest),
        defaultHeaders = defaultHeaders,
        sessionAuthPresetType = "BearerToken",
        sessionAuthPresetData = Map("token" -> "sess-tok"),
        sessionCustomHeaders = List(MetadataHeader("x-session", "yes")),
        callHistory = List(historyEntry),
        environments = List(envConfig),
        activeEnvironmentId = Some("env-staging"),
        lastTemplateByMethod = Map("order.OrderService/CreateOrder" -> "test-tpl-1"),
        workbenchTabs = List(SavedWorkbenchTab("tab-1", "CreateOrder", Some("order.OrderService/CreateOrder"), """{"a":1}"""))
      )

      val data = WorkspaceData(
        settings = AppSettings(
          theme = "ProtoForm Paper",
          showDefaultValues = false,
          preserveProtoFieldNames = false,
          lastEndpoint = "grpcb.in:9000",
          lastUseTls = true,
          lastSelectedMethod = Some("order.OrderService/CreateOrder"),
          requestDeadlineSeconds = 12,
          insecureSkipTlsVerify = true,
          clientCertPath = Some("/certs/client.crt"),
          clientKeyPath = Some("/certs/client.key"),
          protocPath = Some("/usr/local/bin/protoc"),
          protoIncludeDirs = List("/workspace/protos", "/third_party/protos"),
          splitDividerPositions = List(0.28, 0.58),
          requestResponseVertical = true,
          showNavPane = true,
          activeNavSection = "templates",
          showAdvancedActions = true,
          requestTabOrder = List("json", "form"),
          responseTabOrder = List("auth", "resp-body", "resp-headers", "resp-stream"),
          navTabOrder = List("templates", "services", "history")
        ),
        projects = List(proj),
        activeProjectId = "proj-orders",
        globalAuthProfiles = List(customProfile)
      )

      repo.saveWorkspace(data).isRight shouldBe true

      // Ensure atomic write leaves no leftover .tmp files behind
      val files = {
        val s = Files.list(dir)
        try {
          s.iterator().asScala.map(_.getFileName.toString).toList
        } finally {
          s.close()
        }
      }
      files shouldBe List("workspace.json")

      val loaded = repo.loadWorkspace().fold(err => fail(err.message), identity)

      loaded.settings.theme shouldBe "ProtoForm Paper"
      loaded.settings.showDefaultValues shouldBe false
      loaded.settings.preserveProtoFieldNames shouldBe false
      loaded.settings.lastEndpoint shouldBe "grpcb.in:9000"
      loaded.settings.lastUseTls shouldBe true
      loaded.settings.lastSelectedMethod shouldBe Some("order.OrderService/CreateOrder")
      loaded.settings.requestDeadlineSeconds shouldBe 12
      loaded.settings.insecureSkipTlsVerify shouldBe true
      loaded.settings.clientCertPath shouldBe Some("/certs/client.crt")
      loaded.settings.clientKeyPath shouldBe Some("/certs/client.key")
      loaded.settings.protocPath shouldBe Some("/usr/local/bin/protoc")
      loaded.settings.protoIncludeDirs shouldBe List("/workspace/protos", "/third_party/protos")
      loaded.settings.splitDividerPositions shouldBe List(0.28, 0.58)
      loaded.settings.requestResponseVertical shouldBe true
      loaded.settings.showNavPane shouldBe true
      loaded.settings.activeNavSection shouldBe "templates"
      loaded.settings.showAdvancedActions shouldBe true
      loaded.settings.requestTabOrder shouldBe List("json", "form")
      loaded.settings.responseTabOrder shouldBe List("auth", "resp-body", "resp-headers", "resp-stream")
      loaded.settings.navTabOrder shouldBe List("templates", "services", "history")

      loaded.methodPayloads should contain key "order.OrderService/CreateOrder"
      loaded.methodPayloads("order.OrderService/CreateOrder") should include("u-123")

      loaded.templates should have size 1
      val loadedTpl = loaded.templates.head
      loadedTpl.id shouldBe "test-tpl-1"
      loadedTpl.name shouldBe "Create Order Valid"
      loadedTpl.tags shouldBe List("smoke", "qa")
      loadedTpl.expectedStatus shouldBe Some("OK")
      loadedTpl.folderPath shouldBe "Orders/Smoke"
      loadedTpl.authPresetType shouldBe "ApiKey"
      loadedTpl.authPresetData("key") shouldBe "secret_abc"
      loadedTpl.customHeaders shouldBe List(MetadataHeader("x-correlation-id", "corr-99", enabled = true))
      loadedTpl.createdAt shouldBe 1700000000000L
      loadedTpl.updatedAt shouldBe 1700000001000L

      loaded.lastTemplateByMethod shouldBe Map("order.OrderService/CreateOrder" -> "test-tpl-1")
      loaded.activeProject.workbenchTabs should have size 1
      loaded.activeProject.workbenchTabs.head.title shouldBe "CreateOrder"

      val bundleJson = repo.exportProjectBundleJson(ProjectExportBundle(proj, List(customProfile)))
      val parsedBundle = repo.importProjectBundleJson(bundleJson).fold(e => fail(e.message), identity)
      parsedBundle.project.name shouldBe "Orders"
      parsedBundle.globalAuthProfiles should have size 1

      val gcp = AuthPreset.GoogleCloud("IdToken", Some("/tmp/sa.json"), Some("https://svc.run.app"), List("https://www.googleapis.com/auth/cloud-platform"))
      val (gt, gd) = net.protoform.core.workspace.AuthSerialization.encodePreset(gcp)
      net.protoform.core.workspace.AuthSerialization.decodePreset(gt, gd) shouldBe gcp

      val jwt = AuthPreset.SignedJwt("HS256", "sec", "iss", "sub", "aud", """{"env":"qa"}""", 900L)
      val (jt, jd) = net.protoform.core.workspace.AuthSerialization.encodePreset(jwt)
      net.protoform.core.workspace.AuthSerialization.decodePreset(jt, jd) shouldBe jwt

      loaded.sessionAuthPresetType shouldBe "BearerToken"
      loaded.sessionAuthPresetData shouldBe Map("token" -> "sess-tok")
      loaded.sessionCustomHeaders shouldBe List(MetadataHeader("x-session", "yes"))
      loaded.callHistory shouldBe List(historyEntry)
      loaded.environments shouldBe List(envConfig)
      loaded.activeEnvironmentId shouldBe Some("env-staging")

      val exportedTemplates = repo.serializeTemplates(List(template))
      repo.deserializeTemplates(exportedTemplates) shouldBe Right(List(template))

      loaded.authProfiles should have size 2
      val loadedBearer = loaded.authProfiles.head
      loadedBearer.id shouldBe "prof-1"
      loadedBearer.preset shouldBe AuthPreset.BearerToken("jwt-token-xyz")
      loadedBearer.customHeaders shouldBe List(MetadataHeader("x-tenant", "staging-us", enabled = true))

      val loadedCustom = loaded.authProfiles(1)
      loadedCustom.id shouldBe "prof-2"
      loadedCustom.preset shouldBe AuthPreset.Custom(List(
        MetadataHeader("x-custom-auth", "sig-123", enabled = true),
        MetadataHeader("x-disabled-auth", "ignored", enabled = false)
      ))
      loadedCustom.customHeaders shouldBe List(MetadataHeader("x-trace", "on", enabled = true))

      loaded.savedRequests shouldBe List(savedRequest)
      loaded.defaultHeaders shouldBe defaultHeaders
    }
  }

  test("loadWorkspace tolerates JsonNull and wrong-typed fields without crashing") {
    withTempDir { dir =>
      val json =
        """{
          |  "settings": {
          |    "theme": null,
          |    "showDefaultValues": "not-a-bool",
          |    "lastEndpoint": 12345,
          |    "lastUseTls": null
          |  },
          |  "recentEndpoints": ["localhost:50051", null, 42, {"bad": true}],
          |  "methodPayloads": {
          |    "ok/Method": "{}",
          |    "nullVal": null,
          |    "numVal": 99
          |  },
          |  "templates": [
          |    null,
          |    "not-an-object",
          |    {
          |      "id": "tpl-safe",
          |      "name": null,
          |      "createdAt": "invalid-long",
          |      "authPresetData": {"k": "v", "bad": null},
          |      "customHeaders": null
          |    }
          |  ],
          |  "authProfiles": [
          |    {
          |      "id": "p-custom",
          |      "presetType": "Custom",
          |      "presetData": null,
          |      "presetHeaders": [null, {"key": "x-a", "value": "1", "enabled": "bad"}],
          |      "customHeaders": "not-an-array"
          |    }
          |  ],
          |  "savedRequests": [
          |    null,
          |    {
          |      "id": "r-1",
          |      "name": "Req",
          |      "headers": [null, {"key": "x-h", "value": null, "enabled": false}]
          |    }
          |  ],
          |  "defaultHeaders": [null, {"key": "x-def", "value": "ok"}]
          |}""".stripMargin

      Files.write(dir.resolve("workspace.json"), json.getBytes(StandardCharsets.UTF_8))
      val repo = new LocalJsonWorkspaceRepository(dir)
      val loaded = repo.loadWorkspace().fold(err => fail(err.message), identity)

      loaded.settings shouldBe AppSettings(theme = "ProtoForm Carbon", showDefaultValues = true, lastEndpoint = "localhost:50051", lastUseTls = false)
      loaded.recentEndpoints shouldBe List("localhost:50051")
      loaded.methodPayloads shouldBe Map("ok/Method" -> "{}")
      loaded.templates should have size 1
      loaded.templates.head.id shouldBe "tpl-safe"
      loaded.templates.head.name shouldBe "Untitled Template"
      loaded.templates.head.authPresetData shouldBe Map("k" -> "v")
      loaded.templates.head.customHeaders shouldBe Nil

      loaded.authProfiles should have size 1
      loaded.authProfiles.head.preset shouldBe AuthPreset.Custom(List(MetadataHeader("x-a", "1", enabled = true)))
      loaded.authProfiles.head.customHeaders shouldBe Nil

      loaded.savedRequests should have size 1
      loaded.savedRequests.head.headers shouldBe List(MetadataHeader("x-h", "", enabled = false))

      loaded.defaultHeaders shouldBe List(MetadataHeader("x-def", "ok", enabled = true))
    }
  }

  test("loadWorkspace normalizes legacy FlexRPC theme names to ProtoForm") {
    withTempDir { dir =>
      Files.write(dir.resolve("workspace.json"), """{"settings":{"theme":"FlexRPC Paper"}}""".getBytes(StandardCharsets.UTF_8))
      val repo = new LocalJsonWorkspaceRepository(dir)
      val loaded = repo.loadWorkspace().fold(err => fail(err.message), identity)
      loaded.settings.theme shouldBe "ProtoForm Paper"
    }
  }

  test("loadWorkspace returns StorageFailure on malformed JSON or non-object root") {
    withTempDir { dir =>
      val repo = new LocalJsonWorkspaceRepository(dir)

      Files.write(dir.resolve("workspace.json"), "{broken json".getBytes(StandardCharsets.UTF_8))
      repo.loadWorkspace() should matchPattern { case Left(DomainError.StorageFailure("parse", _, _)) => }

      Files.write(dir.resolve("workspace.json"), "[]".getBytes(StandardCharsets.UTF_8))
      repo.loadWorkspace() should matchPattern { case Left(DomainError.StorageFailure("parse", _, _)) => }

      Files.write(dir.resolve("workspace.json"), "null".getBytes(StandardCharsets.UTF_8))
      repo.loadWorkspace() should matchPattern { case Left(DomainError.StorageFailure("parse", _, _)) => }
    }
  }
}
