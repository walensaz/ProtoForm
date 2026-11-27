package net.protoform.application

import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}
import net.protoform.core.error.DomainError
import net.protoform.core.workspace.{AppSettings, CallFingerprint, CallHistoryEntry, ServiceTemplate, WorkspaceData}
import net.protoform.ports.storage.WorkspacePort
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

class WorkspaceServiceSpec extends AnyFunSuite with Matchers {

  private class InMemoryWorkspacePort(initial: WorkspaceData = WorkspaceData()) extends WorkspacePort {
    val stored = new AtomicReference[WorkspaceData](initial)
    val loadError = new AtomicReference[Option[DomainError]](None)
    val saveError = new AtomicReference[Option[DomainError]](None)
    val saveCount = new AtomicInteger(0)

    override def loadWorkspace(): Either[DomainError, WorkspaceData] = {
      loadError.get() match {
        case Some(err) => Left(err)
        case None => Right(stored.get())
      }
    }

    override def saveWorkspace(data: WorkspaceData): Either[DomainError, Unit] = {
      saveCount.incrementAndGet()
      saveError.get() match {
        case Some(err) => Left(err)
        case None =>
          stored.set(data)
          Right(())
      }
    }
  }

  test("default theme is ProtoForm Carbon") {
    val service = new WorkspaceService(new InMemoryWorkspacePort())
    service.getSettings.theme shouldBe "ProtoForm Carbon"
  }

  test("initialize loads data from port and propagates load failures") {
    val initial = WorkspaceData.fromSingleProject(
      settings = AppSettings(theme = "ProtoForm Paper", lastEndpoint = "api.example.com:443", lastUseTls = true),
      recentEndpoints = List("api.example.com:443")
    )
    val port = new InMemoryWorkspacePort(initial)
    val service = new WorkspaceService(port)

    service.initialize() shouldBe Right(initial)
    service.getSettings.theme shouldBe "ProtoForm Paper"
    service.getData.recentEndpoints shouldBe List("api.example.com:443")

    val err = DomainError.StorageFailure("load", "workspace.json", "disk read error")
    port.loadError.set(Some(err))
    service.initialize() shouldBe Left(err)
  }

  test("CRUD operations update state and persist to WorkspacePort") {
    val port = new InMemoryWorkspacePort()
    val service = new WorkspaceService(port)

    service.updateSettings(AppSettings(theme = "Nord Dark", showDefaultValues = false))
    service.getSettings.theme shouldBe "Nord Dark"
    port.stored.get().settings.showDefaultValues shouldBe false

    service.addEndpoint("  localhost:50051  ")
    service.addEndpoint("")
    service.addEndpoint("grpcb.in:9000")
    service.addEndpoint("localhost:50051")
    service.getData.recentEndpoints shouldBe List("localhost:50051", "grpcb.in:9000")

    (1 to 20).foreach(i => service.addEndpoint(s"host-$i:50051"))
    service.getData.recentEndpoints should have size 15
    service.getData.recentEndpoints.head shouldBe "host-20:50051"

    service.saveMethodPayload(" pkg.Svc/Call ", """ {"a": 1} """)
    service.getMethodPayload("pkg.Svc/Call") shouldBe Some("""{"a": 1}""")
    service.getMethodPayload(null) shouldBe None

    val tpl1 = ServiceTemplate(
      id = "t1",
      name = "First",
      serviceFullName = "pkg.Svc",
      methodName = "Call",
      requestJson = "{}"
    )
    val tpl1Updated = tpl1.copy(name = "First Updated")
    service.saveTemplate(tpl1)
    service.saveTemplate(tpl1Updated)
    service.getAllTemplates shouldBe List(tpl1Updated)
    service.getTemplatesForMethod("pkg.Svc/Call") shouldBe List(tpl1Updated)
    service.deleteTemplate("t1")
    service.getAllTemplates shouldBe Nil

    val prof1 = AuthProfile(
      id = "p1",
      name = "Admin",
      preset = AuthPreset.Custom(List(MetadataHeader("x-token", "abc"))),
      description = "desc"
    )
    val prof1Updated = prof1.copy(name = "Admin Updated")
    service.saveAuthProfile(prof1)
    service.saveAuthProfile(prof1Updated)
    service.getAuthProfiles shouldBe List(prof1Updated)
    service.deleteAuthProfile("p1")
    service.getAuthProfiles shouldBe Nil
  }

  test("save errors are recorded instead of silently discarded and cleared on next success") {
    val port = new InMemoryWorkspacePort()
    val service = new WorkspaceService(port)

    val failure = DomainError.StorageFailure("save", "workspace.json", "permission denied")
    port.saveError.set(Some(failure))

    service.updateSettings(AppSettings(theme = "ProtoForm Paper"))
    service.getLastSaveError shouldBe Some(failure)
    service.getSettings.theme shouldBe "ProtoForm Paper"

    port.saveError.set(None)
    service.addEndpoint("localhost:50051")
    service.getLastSaveError shouldBe None
    port.stored.get().recentEndpoints should contain("localhost:50051")
  }

  test("concurrent updates do not lose state") {
    val port = new InMemoryWorkspacePort()
    val service = new WorkspaceService(port)
    val threadCount = 8
    val itemsPerThread = 25
    val pool = Executors.newFixedThreadPool(threadCount)
    val startLatch = new CountDownLatch(1)
    val doneLatch = new CountDownLatch(threadCount)

    try {
      for (t <- 0 until threadCount) {
        pool.submit(new Runnable {
          override def run(): Unit = {
            try {
              startLatch.await()
              for (i <- 0 until itemsPerThread) {
                val id = s"t$t-i$i"
                service.saveMethodPayload(s"svc/$id", s"""{"id":"$id"}""")
                service.saveTemplate(ServiceTemplate(
                  id = id,
                  name = id,
                  serviceFullName = "svc",
                  methodName = id,
                  requestJson = "{}"
                ))
              }
            } finally {
              doneLatch.countDown()
            }
          }
        })
      }

      startLatch.countDown()
      doneLatch.await(15, TimeUnit.SECONDS) shouldBe true
    } finally {
      pool.shutdownNow()
    }

    val expectedTotal = threadCount * itemsPerThread
    service.getData.methodPayloads should have size expectedTotal
    service.getAllTemplates should have size expectedTotal
    port.stored.get().methodPayloads should have size expectedTotal
    port.stored.get().templates should have size expectedTotal
  }

  test("recordCallHistory deduplicates exact repeats via fingerprint, bubbles to top, and caps at 30") {
    val port = new InMemoryWorkspacePort()
    val service = new WorkspaceService(port)

    val fp1 = CallFingerprint.compute("localhost:50051", false, "svc/M1", """{"a": 1}""", "None", Map.empty, Nil)
    val fp1Same = CallFingerprint.compute("localhost:50051", false, "svc/M1", """{ "a" : 1 }""", "None", Map.empty, Nil)
    fp1 shouldBe fp1Same

    val e1 = CallHistoryEntry("id1", fp1, "localhost:50051", false, "svc", "M1", """{"a":1}""", statusCode = 0, statusDescription = "OK", latencyMs = 12, executedAt = 100L)
    val fp2 = CallFingerprint.compute("localhost:50051", false, "svc/M2", """{"b": 2}""", "None", Map.empty, Nil)
    val e2 = CallHistoryEntry("id2", fp2, "localhost:50051", false, "svc", "M2", """{"b":2}""", statusCode = 0, statusDescription = "OK", latencyMs = 20, executedAt = 200L)

    service.recordCallHistory(e1)
    service.recordCallHistory(e2)
    service.getCallHistory.map(_.id) shouldBe List("id2", "id1")

    val e1Repeated = e1.copy(id = "id1-new", fingerprint = fp1Same, latencyMs = 5, executedAt = 300L)
    service.recordCallHistory(e1Repeated)
    service.getCallHistory.map(_.id) shouldBe List("id1-new", "id2")

    (1 to 35).foreach { i =>
      val fp = CallFingerprint.compute("localhost:50051", false, s"svc/M$i", s"""{"i":$i}""", "None", Map.empty, Nil)
      service.recordCallHistory(CallHistoryEntry(s"h-$i", fp, "localhost:50051", false, "svc", s"M$i", "{}", statusCode = 0, statusDescription = "OK", latencyMs = 1))
    }
    service.getCallHistory should have size WorkspaceService.MaxCallHistoryEntries
    service.getCallHistory.head.id shouldBe "h-35"
  }

  test("importTemplates merges unique IDs and updateSessionAuth persists auth state") {
    val port = new InMemoryWorkspacePort()
    val service = new WorkspaceService(port)

    val t1 = ServiceTemplate("t1", "Smoke 1", serviceFullName = "svc", methodName = "M1", requestJson = "{}")
    val t2 = ServiceTemplate("t2", "Smoke 2", serviceFullName = "svc", methodName = "M2", requestJson = "{}")
    service.saveTemplate(t1)
    service.importTemplates(List(t1.copy(name = "Dup"), t2)) shouldBe 1
    service.getAllTemplates.map(_.id).toSet shouldBe Set("t1", "t2")

    service.updateSessionAuth(AuthPreset.BearerToken("tok-123"), List(MetadataHeader("x-qa", "true")), Some("prof-1"))
    service.getSettings.lastAuthProfileId shouldBe Some("prof-1")
    service.getData.sessionAuthPresetType shouldBe "BearerToken"
    service.getData.sessionAuthPresetData shouldBe Map("token" -> "tok-123")
    service.getData.sessionCustomHeaders shouldBe List(MetadataHeader("x-qa", "true"))
  }

  test("projects isolate templates/environments while sharing global auth profiles and tracking lastTemplateByMethod") {
    val port = new InMemoryWorkspacePort()
    val service = new WorkspaceService(port)

    val localAuth = AuthProfile("a-local", "Local Auth", AuthPreset.BearerToken("local"), isGlobal = false)
    val globalAuth = AuthProfile("a-global", "Global Auth", AuthPreset.BearerToken("global"), isGlobal = true)
    service.saveAuthProfile(localAuth)
    service.saveAuthProfile(globalAuth)

    val tpl = ServiceTemplate("tpl-1", "Order Smoke", serviceFullName = "orders.OrderSvc", methodName = "Create", requestJson = "{}", folderPath = "Smoke")
    service.saveTemplate(tpl)
    service.setLastTemplateForMethod("orders.OrderSvc/Create", Some("tpl-1"))
    service.getLastTemplateForMethod("orders.OrderSvc/Create") shouldBe Some(tpl)

    val proj2 = service.createProject("Payments")
    service.getActiveProject.id shouldBe proj2.id
    service.getAllTemplates shouldBe Nil
    service.getAuthProfiles.map(_.id) shouldBe List("a-global")

    service.switchProject("default")
    service.getAllTemplates shouldBe List(tpl)
    service.getAuthProfiles.map(_.id).toSet shouldBe Set("a-local", "a-global")
  }
}
