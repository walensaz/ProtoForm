package net.protoform.infrastructure.storage

import com.google.gson._
import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}
import net.protoform.core.error.DomainError
import net.protoform.core.workspace._
import net.protoform.ports.storage.WorkspacePort

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class LocalJsonWorkspaceRepository(
  val configDir: Path = Paths.get(System.getProperty("user.home"), ".protoform")
) extends WorkspacePort {

  val configFile: Path = configDir.resolve("workspace.json")
  private val gson = new GsonBuilder().setPrettyPrinting().create()

  private def migrateLegacyWorkspaceIfNeeded(): Unit = {
    val dirName = Option(configDir.getFileName).map(_.toString)
    if (dirName.contains(".protoform") && !Files.exists(configFile)) {
      val legacyFile = configDir.resolveSibling(".flexrpc").resolve("workspace.json")
      if (Files.isRegularFile(legacyFile)) {
        Files.createDirectories(configDir)
        Files.copy(legacyFile, configFile, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  override def loadWorkspace(): Either[DomainError, WorkspaceData] = {
    try {
      migrateLegacyWorkspaceIfNeeded()
      if (!Files.exists(configFile)) {
        Right(WorkspaceData.fromSingleProject(
          settings = AppSettings(),
          recentEndpoints = List("localhost:50051", "grpcb.in:9000")
        ))
      } else {
        val content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8)
        deserializeWorkspace(content)
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.StorageFailure("load", configFile.toString, Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    }
  }

  override def saveWorkspace(data: WorkspaceData): Either[DomainError, Unit] = {
    writeStringAtomically(configFile, serializeWorkspace(data))
  }

  def exportWorkspaceJson(data: WorkspaceData): String = serializeWorkspace(data)

  def importWorkspaceJson(jsonStr: String): Either[DomainError, WorkspaceData] = deserializeWorkspace(jsonStr)

  def exportProjectBundleJson(bundle: ProjectExportBundle): String = {
    val root = new JsonObject()
    root.addProperty("format", "protoform-project-v1")
    root.add("project", serializeProjectObject(bundle.project))
    root.add("globalAuthProfiles", serializeAuthProfilesArray(bundle.globalAuthProfiles))
    gson.toJson(root)
  }

  def importProjectBundleJson(jsonStr: String): Either[DomainError, ProjectExportBundle] = {
    try {
      val parsed = JsonParser.parseString(jsonStr)
      if (parsed == null || !parsed.isJsonObject) {
        Left(DomainError.StorageFailure("parse", "project-bundle.json", "Expected JSON object"))
      } else {
        val root = parsed.getAsJsonObject
        val projObj = optObject(root, "project").getOrElse(root)
        val project = parseProjectObject(projObj)
        val globals = parseAuthProfilesArray(optArray(root, "globalAuthProfiles")).map(_.copy(isGlobal = true))
        Right(ProjectExportBundle(project = project, globalAuthProfiles = globals))
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.StorageFailure("parse", "project-bundle.json", Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    }
  }

  def serializeTemplates(templates: List[ServiceTemplate]): String = {
    val root = new JsonObject()
    root.addProperty("format", "protoform-templates-v1")
    root.add("templates", serializeTemplatesArray(templates))
    gson.toJson(root)
  }

  def deserializeTemplates(jsonStr: String): Either[DomainError, List[ServiceTemplate]] = {
    try {
      val parsed = JsonParser.parseString(jsonStr)
      if (parsed == null) {
        Left(DomainError.StorageFailure("parse", "templates.json", "Empty template JSON"))
      } else if (parsed.isJsonArray) {
        Right(parseTemplatesArray(Some(parsed.getAsJsonArray)))
      } else if (parsed.isJsonObject) {
        val obj = parsed.getAsJsonObject
        Right(parseTemplatesArray(optArray(obj, "templates")))
      } else {
        Left(DomainError.StorageFailure("parse", "templates.json", "Expected JSON object or array"))
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.StorageFailure("parse", "templates.json", Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    }
  }

  private def writeStringAtomically(target: Path, content: String): Either[DomainError, Unit] = {
    var tempFile: Path = null
    try {
      val parent = target.getParent
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent)
      }
      tempFile = Files.createTempFile(parent, "workspace-", ".tmp")
      Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8))
      try {
        Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch {
        case _: AtomicMoveNotSupportedException =>
          Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
      }
      tempFile = null
      Right(())
    } catch {
      case NonFatal(e) =>
        if (tempFile != null) {
          try {
            Files.deleteIfExists(tempFile)
          } catch {
            case NonFatal(_) => ()
          }
        }
        Left(DomainError.StorageFailure("save", target.toString, Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    }
  }

  private def serializeWorkspace(data: WorkspaceData): String = {
    val root = new JsonObject()
    root.add("settings", serializeSettings(data.settings))
    root.addProperty("activeProjectId", data.activeProjectId)
    val projArr = new JsonArray()
    data.projects.foreach(p => projArr.add(serializeProjectObject(p)))
    root.add("projects", projArr)
    root.add("globalAuthProfiles", serializeAuthProfilesArray(data.globalAuthProfiles))
    gson.toJson(root)
  }

  private def serializeProjectObject(p: WorkspaceProject): JsonObject = {
    val obj = new JsonObject()
    obj.addProperty("id", p.id)
    obj.addProperty("name", p.name)
    obj.add("recentEndpoints", serializeStringArray(p.recentEndpoints))
    obj.add("methodPayloads", serializeStringMap(p.methodPayloads))
    obj.add("templates", serializeTemplatesArray(p.templates))
    obj.add("authProfiles", serializeAuthProfilesArray(p.authProfiles))
    obj.add("savedRequests", serializeSavedRequestsArray(p.savedRequests))
    obj.add("defaultHeaders", serializeHeaders(p.defaultHeaders))
    obj.addProperty("sessionAuthPresetType", p.sessionAuthPresetType)
    obj.add("sessionAuthPresetData", serializeStringMap(p.sessionAuthPresetData))
    obj.add("sessionCustomHeaders", serializeHeaders(p.sessionCustomHeaders))
    obj.add("callHistory", serializeCallHistoryArray(p.callHistory))
    obj.add("environments", serializeEnvironmentsArray(p.environments))
    p.activeEnvironmentId.foreach(id => obj.addProperty("activeEnvironmentId", id))
    p.lastEndpoint.foreach(v => obj.addProperty("lastEndpoint", v))
    p.lastUseTls.foreach(v => obj.addProperty("lastUseTls", v))
    p.lastSelectedMethod.foreach(v => obj.addProperty("lastSelectedMethod", v))
    p.lastAuthProfileId.foreach(v => obj.addProperty("lastAuthProfileId", v))
    obj.add("lastTemplateByMethod", serializeStringMap(p.lastTemplateByMethod))
    obj.add("workbenchTabs", serializeWorkbenchTabsArray(p.workbenchTabs))
    obj.addProperty("activeWorkbenchTabIndex", p.activeWorkbenchTabIndex)
    obj
  }

  private def serializeWorkbenchTabsArray(tabs: List[SavedWorkbenchTab]): JsonArray = {
    val arr = new JsonArray()
    tabs.foreach { t =>
      val obj = new JsonObject()
      obj.addProperty("id", t.id)
      obj.addProperty("title", t.title)
      t.methodFullName.foreach(m => obj.addProperty("methodFullName", m))
      obj.addProperty("requestJson", t.requestJson)
      obj.addProperty("authPresetType", t.authPresetType)
      obj.add("authPresetData", serializeStringMap(t.authPresetData))
      obj.add("customHeaders", serializeHeaders(t.customHeaders))
      t.selectedAuthProfileId.foreach(id => obj.addProperty("selectedAuthProfileId", id))
      t.activeTemplateId.foreach(id => obj.addProperty("activeTemplateId", id))
      obj.addProperty("lastEditedIsForm", t.lastEditedIsForm)
      arr.add(obj)
    }
    arr
  }

  private def serializeSettings(s: AppSettings): JsonObject = {
    val obj = new JsonObject()
    obj.addProperty("theme", s.theme)
    obj.addProperty("showDefaultValues", s.showDefaultValues)
    obj.addProperty("preserveProtoFieldNames", s.preserveProtoFieldNames)
    obj.addProperty("autoFormatJson", s.autoFormatJson)
    obj.addProperty("autoSavePayloads", s.autoSavePayloads)
    obj.addProperty("autoSelectLastMethod", s.autoSelectLastMethod)
    obj.addProperty("lastEndpoint", s.lastEndpoint)
    obj.addProperty("lastUseTls", s.lastUseTls)
    s.lastSelectedMethod.foreach(v => obj.addProperty("lastSelectedMethod", v))
    s.lastAuthProfileId.foreach(v => obj.addProperty("lastAuthProfileId", v))
    obj.addProperty("requestDeadlineSeconds", s.requestDeadlineSeconds)
    obj.addProperty("reflectionTimeoutSeconds", s.reflectionTimeoutSeconds)
    obj.addProperty("maxInboundMessageMb", s.maxInboundMessageMb)
    obj.addProperty("useGzipCompression", s.useGzipCompression)
    obj.addProperty("insecureSkipTlsVerify", s.insecureSkipTlsVerify)
    s.customCaCertPath.foreach(v => obj.addProperty("customCaCertPath", v))
    s.clientCertPath.foreach(v => obj.addProperty("clientCertPath", v))
    s.clientKeyPath.foreach(v => obj.addProperty("clientKeyPath", v))
    s.protocPath.foreach(v => obj.addProperty("protocPath", v))
    obj.add("protoIncludeDirs", serializeStringArray(s.protoIncludeDirs))
    obj.addProperty("editorFontSize", s.editorFontSize)
    obj.addProperty("windowWidth", s.windowWidth)
    obj.addProperty("windowHeight", s.windowHeight)
    s.windowX.foreach(v => obj.addProperty("windowX", v))
    s.windowY.foreach(v => obj.addProperty("windowY", v))
    obj.addProperty("isMaximized", s.isMaximized)
    val divArr = new JsonArray()
    s.splitDividerPositions.foreach(d => divArr.add(Double.box(d)))
    obj.add("splitDividerPositions", divArr)
    obj.addProperty("requestResponseVertical", s.requestResponseVertical)
    obj.addProperty("showNavPane", s.showNavPane)
    obj.addProperty("activeNavSection", s.activeNavSection)
    obj.addProperty("showResponsePane", s.showResponsePane)
    obj.addProperty("showAdvancedActions", s.showAdvancedActions)
    obj.add("requestTabOrder", serializeStringArray(s.requestTabOrder))
    obj.add("responseTabOrder", serializeStringArray(s.responseTabOrder))
    obj.add("navTabOrder", serializeStringArray(s.navTabOrder))
    obj
  }

  private def serializeTemplatesArray(templates: List[ServiceTemplate]): JsonArray = {
    val arr = new JsonArray()
    templates.foreach { t =>
      val tObj = new JsonObject()
      tObj.addProperty("id", t.id)
      tObj.addProperty("name", t.name)
      tObj.addProperty("description", t.description)
      tObj.addProperty("serviceFullName", t.serviceFullName)
      tObj.addProperty("methodName", t.methodName)
      tObj.addProperty("requestJson", t.requestJson)
      tObj.addProperty("authPresetType", t.authPresetType)
      tObj.add("authPresetData", serializeStringMap(t.authPresetData))
      tObj.add("customHeaders", serializeHeaders(t.customHeaders))
      tObj.add("tags", serializeStringArray(t.tags))
      t.expectedStatus.foreach(s => tObj.addProperty("expectedStatus", s))
      tObj.addProperty("folderPath", t.folderPath)
      tObj.addProperty("createdAt", t.createdAt)
      tObj.addProperty("updatedAt", t.updatedAt)
      arr.add(tObj)
    }
    arr
  }

  private def serializeAuthProfilesArray(profiles: List[AuthProfile]): JsonArray = {
    val arr = new JsonArray()
    profiles.foreach { p =>
      val pObj = new JsonObject()
      pObj.addProperty("id", p.id)
      pObj.addProperty("name", p.name)
      pObj.addProperty("description", p.description)
      pObj.addProperty("isGlobal", p.isGlobal)
      pObj.addProperty("presetType", p.preset.presetType)
      pObj.add("presetData", serializeStringMap(p.preset.toDataMap))
      p.preset match {
        case AuthPreset.Custom(headers) => pObj.add("presetHeaders", serializeHeaders(headers))
        case _ => ()
      }
      pObj.add("customHeaders", serializeHeaders(p.customHeaders))
      arr.add(pObj)
    }
    arr
  }

  private def serializeSavedRequestsArray(saved: List[SavedRequest]): JsonArray = {
    val arr = new JsonArray()
    saved.foreach { r =>
      val rObj = new JsonObject()
      rObj.addProperty("id", r.id)
      rObj.addProperty("name", r.name)
      rObj.addProperty("endpoint", r.endpoint)
      rObj.addProperty("service", r.service)
      rObj.addProperty("method", r.method)
      rObj.addProperty("requestJson", r.requestJson)
      rObj.add("headers", serializeHeaders(r.headers))
      arr.add(rObj)
    }
    arr
  }

  private def serializeCallHistoryArray(history: List[CallHistoryEntry]): JsonArray = {
    val arr = new JsonArray()
    history.foreach { h =>
      val obj = new JsonObject()
      obj.addProperty("id", h.id)
      obj.addProperty("fingerprint", h.fingerprint)
      obj.addProperty("endpoint", h.endpoint)
      obj.addProperty("useTls", h.useTls)
      obj.addProperty("serviceFullName", h.serviceFullName)
      obj.addProperty("methodName", h.methodName)
      obj.addProperty("requestJson", h.requestJson)
      obj.addProperty("authPresetType", h.authPresetType)
      obj.add("authPresetData", serializeStringMap(h.authPresetData))
      obj.add("customHeaders", serializeHeaders(h.customHeaders))
      obj.addProperty("statusCode", h.statusCode)
      obj.addProperty("statusDescription", h.statusDescription)
      obj.addProperty("latencyMs", h.latencyMs)
      obj.addProperty("responsePreview", h.responsePreview)
      obj.addProperty("responseBodyJson", h.responseBodyJson)
      obj.addProperty("executedAt", h.executedAt)
      arr.add(obj)
    }
    arr
  }

  private def serializeEnvironmentsArray(envs: List[EnvironmentConfig]): JsonArray = {
    val arr = new JsonArray()
    envs.foreach { env =>
      val obj = new JsonObject()
      obj.addProperty("id", env.id)
      obj.addProperty("name", env.name)
      obj.addProperty("endpoint", env.endpoint)
      obj.addProperty("useTls", env.useTls)
      env.authProfileId.foreach(id => obj.addProperty("authProfileId", id))
      obj.addProperty("insecureSkipTlsVerify", env.insecureSkipTlsVerify)
      env.customCaCertPath.foreach(p => obj.addProperty("customCaCertPath", p))
      env.clientCertPath.foreach(p => obj.addProperty("clientCertPath", p))
      env.clientKeyPath.foreach(p => obj.addProperty("clientKeyPath", p))
      obj.add("variables", serializeStringMap(env.variables))
      arr.add(obj)
    }
    arr
  }

  private def serializeStringArray(items: List[String]): JsonArray = {
    val arr = new JsonArray()
    items.foreach(arr.add)
    arr
  }

  private def serializeStringMap(map: Map[String, String]): JsonObject = {
    val obj = new JsonObject()
    map.foreach { case (k, v) => obj.addProperty(k, v) }
    obj
  }

  private def serializeHeaders(headers: List[MetadataHeader]): JsonArray = {
    val arr = new JsonArray()
    headers.foreach { h =>
      val hObj = new JsonObject()
      hObj.addProperty("key", h.key)
      hObj.addProperty("value", h.value)
      hObj.addProperty("enabled", h.enabled)
      arr.add(hObj)
    }
    arr
  }

  private def deserializeWorkspace(jsonStr: String): Either[DomainError, WorkspaceData] = {
    try {
      val parsed = JsonParser.parseString(jsonStr)
      if (parsed == null || !parsed.isJsonObject) {
        return Left(DomainError.StorageFailure("parse", configFile.toString, "Workspace root must be a JSON object"))
      }
      val root = parsed.getAsJsonObject
      val defaults = AppSettings()

      val settings = optObject(root, "settings").map { s =>
        val divs = optArray(s, "splitDividerPositions").map { arr =>
          arr.asScala.collect {
            case el if el != null && el.isJsonPrimitive && el.getAsJsonPrimitive.isNumber => el.getAsDouble
          }.toList
        }.filter(_.nonEmpty).getOrElse(defaults.splitDividerPositions)

        val rawTheme = optString(s, "theme").getOrElse(defaults.theme)
        val normalizedTheme = rawTheme match {
          case "FlexRPC Carbon" => "ProtoForm Carbon"
          case "FlexRPC Paper"  => "ProtoForm Paper"
          case other            => other
        }
        AppSettings(
          theme = normalizedTheme,
          showDefaultValues = optBool(s, "showDefaultValues").getOrElse(defaults.showDefaultValues),
          preserveProtoFieldNames = optBool(s, "preserveProtoFieldNames").getOrElse(defaults.preserveProtoFieldNames),
          autoFormatJson = optBool(s, "autoFormatJson").getOrElse(defaults.autoFormatJson),
          autoSavePayloads = optBool(s, "autoSavePayloads").getOrElse(defaults.autoSavePayloads),
          autoSelectLastMethod = optBool(s, "autoSelectLastMethod").getOrElse(defaults.autoSelectLastMethod),
          lastEndpoint = optString(s, "lastEndpoint").getOrElse(defaults.lastEndpoint),
          lastUseTls = optBool(s, "lastUseTls").getOrElse(defaults.lastUseTls),
          lastSelectedMethod = optString(s, "lastSelectedMethod").filter(_.nonEmpty),
          lastAuthProfileId = optString(s, "lastAuthProfileId").filter(_.nonEmpty),
          requestDeadlineSeconds = optInt(s, "requestDeadlineSeconds").getOrElse(defaults.requestDeadlineSeconds),
          reflectionTimeoutSeconds = optInt(s, "reflectionTimeoutSeconds").getOrElse(defaults.reflectionTimeoutSeconds),
          maxInboundMessageMb = optInt(s, "maxInboundMessageMb").getOrElse(defaults.maxInboundMessageMb),
          useGzipCompression = optBool(s, "useGzipCompression").getOrElse(defaults.useGzipCompression),
          insecureSkipTlsVerify = optBool(s, "insecureSkipTlsVerify").getOrElse(defaults.insecureSkipTlsVerify),
          customCaCertPath = optString(s, "customCaCertPath").filter(_.nonEmpty),
          clientCertPath = optString(s, "clientCertPath").filter(_.nonEmpty),
          clientKeyPath = optString(s, "clientKeyPath").filter(_.nonEmpty),
          protocPath = optString(s, "protocPath").filter(_.nonEmpty),
          protoIncludeDirs = parseStringArray(optArray(s, "protoIncludeDirs")).getOrElse(defaults.protoIncludeDirs),
          editorFontSize = optInt(s, "editorFontSize").getOrElse(defaults.editorFontSize),
          windowWidth = optDouble(s, "windowWidth").getOrElse(defaults.windowWidth),
          windowHeight = optDouble(s, "windowHeight").getOrElse(defaults.windowHeight),
          windowX = optDouble(s, "windowX"),
          windowY = optDouble(s, "windowY"),
          isMaximized = optBool(s, "isMaximized").getOrElse(defaults.isMaximized),
          splitDividerPositions = divs,
          requestResponseVertical = optBool(s, "requestResponseVertical").getOrElse(defaults.requestResponseVertical),
          showNavPane = optBool(s, "showNavPane").getOrElse(defaults.showNavPane),
          activeNavSection = optString(s, "activeNavSection").filter(_.nonEmpty).getOrElse(defaults.activeNavSection),
          showResponsePane = optBool(s, "showResponsePane").getOrElse(defaults.showResponsePane),
          showAdvancedActions = optBool(s, "showAdvancedActions").getOrElse(defaults.showAdvancedActions),
          requestTabOrder = parseStringArray(optArray(s, "requestTabOrder")).filter(_.nonEmpty).getOrElse(defaults.requestTabOrder),
          responseTabOrder = parseStringArray(optArray(s, "responseTabOrder")).filter(_.nonEmpty).getOrElse(defaults.responseTabOrder),
          navTabOrder = parseStringArray(optArray(s, "navTabOrder")).filter(_.nonEmpty).getOrElse(defaults.navTabOrder)
        )
      }.getOrElse(defaults)

      val projectsArrOpt = optArray(root, "projects")
      projectsArrOpt match {
        case Some(arr) if arr.size() > 0 =>
          val projects = arr.asScala.collect {
            case el if el != null && el.isJsonObject => parseProjectObject(el.getAsJsonObject)
          }.toList
          val safeProjects = if (projects.nonEmpty) projects else List(WorkspaceProject())
          val activeProjId = optString(root, "activeProjectId").filter(id => safeProjects.exists(_.id == id)).getOrElse(safeProjects.head.id)
          val globals = parseAuthProfilesArray(optArray(root, "globalAuthProfiles")).map(_.copy(isGlobal = true))
          Right(WorkspaceData(
            settings = settings,
            projects = safeProjects,
            activeProjectId = activeProjId,
            globalAuthProfiles = globals
          ))

        case _ =>
          val migratedProj = parseProjectObject(root, isLegacyRoot = true)
          val (globals, projLocalProfiles) = migratedProj.authProfiles.partition(_.isGlobal)
          val explicitGlobals = parseAuthProfilesArray(optArray(root, "globalAuthProfiles")).map(_.copy(isGlobal = true))
          Right(WorkspaceData(
            settings = settings,
            projects = List(migratedProj.copy(id = "default", name = "Default", authProfiles = projLocalProfiles)),
            activeProjectId = "default",
            globalAuthProfiles = (globals ++ explicitGlobals).distinctBy(_.id)
          ))
      }
    } catch {
      case NonFatal(e) =>
        Left(DomainError.StorageFailure("parse", configFile.toString, Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
    }
  }

  private def parseProjectObject(obj: JsonObject, isLegacyRoot: Boolean = false): WorkspaceProject = {
    val defaultEndpoints = if (isLegacyRoot) List("localhost:50051") else Nil
    WorkspaceProject(
      id = optString(obj, "id").filter(_.nonEmpty).getOrElse("default"),
      name = optString(obj, "name").filter(_.nonEmpty).getOrElse("Default"),
      recentEndpoints = parseStringArray(optArray(obj, "recentEndpoints")).getOrElse(defaultEndpoints),
      methodPayloads = optStringMap(obj, "methodPayloads"),
      templates = parseTemplatesArray(optArray(obj, "templates")),
      authProfiles = parseAuthProfilesArray(optArray(obj, "authProfiles")),
      savedRequests = parseSavedRequestsArray(optArray(obj, "savedRequests")),
      defaultHeaders = parseHeaders(optArray(obj, "defaultHeaders")),
      sessionAuthPresetType = optString(obj, "sessionAuthPresetType").getOrElse("None"),
      sessionAuthPresetData = optStringMap(obj, "sessionAuthPresetData"),
      sessionCustomHeaders = parseHeaders(optArray(obj, "sessionCustomHeaders")),
      callHistory = parseCallHistoryArray(optArray(obj, "callHistory")),
      environments = parseEnvironmentsArray(optArray(obj, "environments")),
      activeEnvironmentId = optString(obj, "activeEnvironmentId").filter(_.nonEmpty),
      lastEndpoint = optString(obj, "lastEndpoint").filter(_.nonEmpty),
      lastUseTls = optBool(obj, "lastUseTls"),
      lastSelectedMethod = optString(obj, "lastSelectedMethod").filter(_.nonEmpty),
      lastAuthProfileId = optString(obj, "lastAuthProfileId").filter(_.nonEmpty),
      lastTemplateByMethod = optStringMap(obj, "lastTemplateByMethod"),
      workbenchTabs = parseWorkbenchTabsArray(optArray(obj, "workbenchTabs")),
      activeWorkbenchTabIndex = optInt(obj, "activeWorkbenchTabIndex").getOrElse(0)
    )
  }

  private def parseWorkbenchTabsArray(arrOpt: Option[JsonArray]): List[SavedWorkbenchTab] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonObject =>
          val t = el.getAsJsonObject
          SavedWorkbenchTab(
            id = optString(t, "id").filter(_.nonEmpty).getOrElse(java.util.UUID.randomUUID().toString),
            title = optString(t, "title").filter(_.nonEmpty).getOrElse("New Request"),
            methodFullName = optString(t, "methodFullName").filter(_.nonEmpty),
            requestJson = optString(t, "requestJson").getOrElse("{}"),
            authPresetType = optString(t, "authPresetType").getOrElse("None"),
            authPresetData = optStringMap(t, "authPresetData"),
            customHeaders = parseHeaders(optArray(t, "customHeaders")),
            selectedAuthProfileId = optString(t, "selectedAuthProfileId").filter(_.nonEmpty),
            activeTemplateId = optString(t, "activeTemplateId").filter(_.nonEmpty),
            lastEditedIsForm = optBool(t, "lastEditedIsForm").getOrElse(true)
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def parseTemplatesArray(arrOpt: Option[JsonArray]): List[ServiceTemplate] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonObject =>
          val t = el.getAsJsonObject
          val created = optLong(t, "createdAt").getOrElse(System.currentTimeMillis())
          ServiceTemplate(
            id = optString(t, "id").getOrElse(java.util.UUID.randomUUID().toString),
            name = optString(t, "name").getOrElse("Untitled Template"),
            description = optString(t, "description").getOrElse(""),
            serviceFullName = optString(t, "serviceFullName").getOrElse(""),
            methodName = optString(t, "methodName").getOrElse(""),
            requestJson = optString(t, "requestJson").getOrElse("{}"),
            authPresetType = optString(t, "authPresetType").getOrElse("None"),
            authPresetData = optStringMap(t, "authPresetData"),
            customHeaders = parseHeaders(optArray(t, "customHeaders")),
            tags = parseStringArray(optArray(t, "tags")).getOrElse(Nil),
            expectedStatus = optString(t, "expectedStatus").filter(_.nonEmpty),
            folderPath = optString(t, "folderPath").getOrElse(""),
            createdAt = created,
            updatedAt = optLong(t, "updatedAt").getOrElse(created)
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def parseAuthProfilesArray(arrOpt: Option[JsonArray]): List[AuthProfile] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonObject =>
          val p = el.getAsJsonObject
          val pType = optString(p, "presetType").getOrElse("None")
          val pData = optStringMap(p, "presetData")
          val preset: AuthPreset = pType match {
            case "Custom" => AuthPreset.Custom(parseHeaders(optArray(p, "presetHeaders")))
            case other    => AuthSerialization.decodePreset(other, pData)
          }
          AuthProfile(
            id = optString(p, "id").getOrElse(java.util.UUID.randomUUID().toString),
            name = optString(p, "name").getOrElse("Unnamed Profile"),
            preset = preset,
            customHeaders = parseHeaders(optArray(p, "customHeaders")),
            description = optString(p, "description").getOrElse(""),
            isGlobal = optBool(p, "isGlobal").getOrElse(false)
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def parseSavedRequestsArray(arrOpt: Option[JsonArray]): List[SavedRequest] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonObject =>
          val r = el.getAsJsonObject
          SavedRequest(
            id = optString(r, "id").getOrElse(java.util.UUID.randomUUID().toString),
            name = optString(r, "name").getOrElse("Untitled Request"),
            endpoint = optString(r, "endpoint").getOrElse(""),
            service = optString(r, "service").getOrElse(""),
            method = optString(r, "method").getOrElse(""),
            requestJson = optString(r, "requestJson").getOrElse("{}"),
            headers = parseHeaders(optArray(r, "headers"))
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def parseCallHistoryArray(arrOpt: Option[JsonArray]): List[CallHistoryEntry] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonObject =>
          val h = el.getAsJsonObject
          val ep = optString(h, "endpoint").getOrElse("")
          val useTls = optBool(h, "useTls").getOrElse(false)
          val svc = optString(h, "serviceFullName").getOrElse("")
          val mtd = optString(h, "methodName").getOrElse("")
          val reqJson = optString(h, "requestJson").getOrElse("{}")
          val authType = optString(h, "authPresetType").getOrElse("None")
          val authData = optStringMap(h, "authPresetData")
          val headers = parseHeaders(optArray(h, "customHeaders"))
          val fp = optString(h, "fingerprint").filter(_.nonEmpty).getOrElse {
            CallFingerprint.compute(ep, useTls, s"$svc/$mtd", reqJson, authType, authData, headers)
          }
          CallHistoryEntry(
            id = optString(h, "id").getOrElse(java.util.UUID.randomUUID().toString),
            fingerprint = fp,
            endpoint = ep,
            useTls = useTls,
            serviceFullName = svc,
            methodName = mtd,
            requestJson = reqJson,
            authPresetType = authType,
            authPresetData = authData,
            customHeaders = headers,
            statusCode = optInt(h, "statusCode").getOrElse(0),
            statusDescription = optString(h, "statusDescription").getOrElse(""),
            latencyMs = optLong(h, "latencyMs").getOrElse(0L),
            responsePreview = optString(h, "responsePreview").getOrElse(""),
            responseBodyJson = optString(h, "responseBodyJson").getOrElse(""),
            executedAt = optLong(h, "executedAt").getOrElse(System.currentTimeMillis())
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def parseEnvironmentsArray(arrOpt: Option[JsonArray]): List[EnvironmentConfig] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonObject =>
          val e = el.getAsJsonObject
          EnvironmentConfig(
            id = optString(e, "id").getOrElse(java.util.UUID.randomUUID().toString),
            name = optString(e, "name").getOrElse("Unnamed Environment"),
            endpoint = optString(e, "endpoint").getOrElse("localhost:50051"),
            useTls = optBool(e, "useTls").getOrElse(false),
            authProfileId = optString(e, "authProfileId").filter(_.nonEmpty),
            insecureSkipTlsVerify = optBool(e, "insecureSkipTlsVerify").getOrElse(false),
            customCaCertPath = optString(e, "customCaCertPath").filter(_.nonEmpty),
            clientCertPath = optString(e, "clientCertPath").filter(_.nonEmpty),
            clientKeyPath = optString(e, "clientKeyPath").filter(_.nonEmpty),
            variables = optStringMap(e, "variables")
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def parseStringArray(arrOpt: Option[JsonArray]): Option[List[String]] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case el if el != null && el.isJsonPrimitive && el.getAsJsonPrimitive.isString => el.getAsString
      }.toList
    }
  }

  private def parseHeaders(arrOpt: Option[JsonArray]): List[MetadataHeader] = {
    arrOpt.map { arr =>
      arr.asScala.collect {
        case hEl if hEl != null && hEl.isJsonObject =>
          val h = hEl.getAsJsonObject
          MetadataHeader(
            key = optString(h, "key").getOrElse(""),
            value = optString(h, "value").getOrElse(""),
            enabled = optBool(h, "enabled").getOrElse(true)
          )
      }.toList
    }.getOrElse(Nil)
  }

  private def optObject(obj: JsonObject, field: String): Option[JsonObject] = {
    if (obj.has(field)) {
      val el = obj.get(field)
      if (el != null && el.isJsonObject) Some(el.getAsJsonObject) else None
    } else None
  }

  private def optArray(obj: JsonObject, field: String): Option[JsonArray] = {
    if (obj.has(field)) {
      val el = obj.get(field)
      if (el != null && el.isJsonArray) Some(el.getAsJsonArray) else None
    } else None
  }

  private def optString(obj: JsonObject, field: String): Option[String] = {
    if (obj.has(field)) {
      val el = obj.get(field)
      if (el != null && el.isJsonPrimitive && el.getAsJsonPrimitive.isString) Some(el.getAsString) else None
    } else None
  }

  private def optBool(obj: JsonObject, field: String): Option[Boolean] = {
    if (obj.has(field)) {
      val el = obj.get(field)
      if (el != null && el.isJsonPrimitive && el.getAsJsonPrimitive.isBoolean) Some(el.getAsBoolean) else None
    } else None
  }

  private def optInt(obj: JsonObject, field: String): Option[Int] = {
    optLong(obj, field).map(_.toInt)
  }

  private def optDouble(obj: JsonObject, field: String): Option[Double] = {
    if (obj.has(field)) {
      val el = obj.get(field)
      if (el != null && el.isJsonPrimitive && el.getAsJsonPrimitive.isNumber) {
        try { Some(el.getAsDouble) } catch { case NonFatal(_) => None }
      } else None
    } else None
  }

  private def optLong(obj: JsonObject, field: String): Option[Long] = {
    if (obj.has(field)) {
      val el = obj.get(field)
      if (el != null && el.isJsonPrimitive && el.getAsJsonPrimitive.isNumber) {
        try { Some(el.getAsLong) } catch { case NonFatal(_) => None }
      } else None
    } else None
  }

  private def optStringMap(obj: JsonObject, field: String): Map[String, String] = {
    optObject(obj, field).map { mapObj =>
      mapObj.entrySet().asScala.collect {
        case e if e.getValue != null && e.getValue.isJsonPrimitive && e.getValue.getAsJsonPrimitive.isString =>
          e.getKey -> e.getValue.getAsString
      }.toMap
    }.getOrElse(Map.empty)
  }
}
