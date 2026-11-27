package net.protoform.core.workspace

import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class AppSettings(
  theme: String = "ProtoForm Carbon",
  showDefaultValues: Boolean = true,
  preserveProtoFieldNames: Boolean = true,
  autoFormatJson: Boolean = true,
  autoSavePayloads: Boolean = true,
  autoSelectLastMethod: Boolean = true,
  lastEndpoint: String = "localhost:50051",
  lastUseTls: Boolean = false,
  lastSelectedMethod: Option[String] = None,
  lastAuthProfileId: Option[String] = None,
  requestDeadlineSeconds: Int = 0,
  reflectionTimeoutSeconds: Int = 15,
  maxInboundMessageMb: Int = 32,
  useGzipCompression: Boolean = false,
  insecureSkipTlsVerify: Boolean = false,
  customCaCertPath: Option[String] = None,
  clientCertPath: Option[String] = None,
  clientKeyPath: Option[String] = None,
  protocPath: Option[String] = None,
  protoIncludeDirs: List[String] = Nil,
  editorFontSize: Int = 13,
  windowWidth: Double = 1480.0,
  windowHeight: Double = 920.0,
  windowX: Option[Double] = None,
  windowY: Option[Double] = None,
  isMaximized: Boolean = false,
  splitDividerPositions: List[Double] = List(0.25, 0.62),
  requestResponseVertical: Boolean = false,
  showNavPane: Boolean = false,
  activeNavSection: String = "services",
  showResponsePane: Boolean = true,
  showAdvancedActions: Boolean = false,
  requestTabOrder: List[String] = List("form", "json", "auth"),
  responseTabOrder: List[String] = List("resp-body", "resp-headers", "resp-stream"),
  navTabOrder: List[String] = List("services", "templates", "history")
)

final case class ServiceTemplate(
  id: String,
  name: String,
  description: String = "",
  serviceFullName: String,
  methodName: String,
  requestJson: String,
  authPresetType: String = "None",
  authPresetData: Map[String, String] = Map.empty,
  customHeaders: List[MetadataHeader] = Nil,
  tags: List[String] = Nil,
  expectedStatus: Option[String] = None,
  folderPath: String = "",
  createdAt: Long = System.currentTimeMillis(),
  updatedAt: Long = System.currentTimeMillis()
) {
  def fullMethodName: String = s"$serviceFullName/$methodName"
}

final case class SavedRequest(
  id: String,
  name: String,
  endpoint: String,
  service: String,
  method: String,
  requestJson: String,
  headers: List[MetadataHeader] = Nil
)

final case class CallHistoryEntry(
  id: String,
  fingerprint: String,
  endpoint: String,
  useTls: Boolean = false,
  serviceFullName: String,
  methodName: String,
  requestJson: String,
  authPresetType: String = "None",
  authPresetData: Map[String, String] = Map.empty,
  customHeaders: List[MetadataHeader] = Nil,
  statusCode: Int,
  statusDescription: String,
  latencyMs: Long,
  responsePreview: String = "",
  responseBodyJson: String = "",
  executedAt: Long = System.currentTimeMillis()
) {
  def fullMethodName: String = s"$serviceFullName/$methodName"
  def effectiveResponseBody: String = if (responseBodyJson.nonEmpty) responseBodyJson else responsePreview
}

final case class EnvironmentConfig(
  id: String,
  name: String,
  endpoint: String = "localhost:50051",
  useTls: Boolean = false,
  authProfileId: Option[String] = None,
  insecureSkipTlsVerify: Boolean = false,
  customCaCertPath: Option[String] = None,
  clientCertPath: Option[String] = None,
  clientKeyPath: Option[String] = None,
  variables: Map[String, String] = Map.empty
)

final case class SavedWorkbenchTab(
  id: String,
  title: String,
  methodFullName: Option[String] = None,
  requestJson: String = "{}",
  authPresetType: String = "None",
  authPresetData: Map[String, String] = Map.empty,
  customHeaders: List[MetadataHeader] = Nil,
  selectedAuthProfileId: Option[String] = None,
  activeTemplateId: Option[String] = None,
  lastEditedIsForm: Boolean = true
)

final case class WorkspaceProject(
  id: String = "default",
  name: String = "Default",
  recentEndpoints: List[String] = Nil,
  methodPayloads: Map[String, String] = Map.empty,
  templates: List[ServiceTemplate] = Nil,
  authProfiles: List[AuthProfile] = Nil,
  savedRequests: List[SavedRequest] = Nil,
  defaultHeaders: List[MetadataHeader] = Nil,
  sessionAuthPresetType: String = "None",
  sessionAuthPresetData: Map[String, String] = Map.empty,
  sessionCustomHeaders: List[MetadataHeader] = Nil,
  callHistory: List[CallHistoryEntry] = Nil,
  environments: List[EnvironmentConfig] = Nil,
  activeEnvironmentId: Option[String] = None,
  lastEndpoint: Option[String] = None,
  lastUseTls: Option[Boolean] = None,
  lastSelectedMethod: Option[String] = None,
  lastAuthProfileId: Option[String] = None,
  lastTemplateByMethod: Map[String, String] = Map.empty,
  workbenchTabs: List[SavedWorkbenchTab] = Nil,
  activeWorkbenchTabIndex: Int = 0
)

final case class ProjectExportBundle(
  project: WorkspaceProject,
  globalAuthProfiles: List[AuthProfile] = Nil
)

final case class WorkspaceData(
  settings: AppSettings = AppSettings(),
  projects: List[WorkspaceProject] = List(WorkspaceProject()),
  activeProjectId: String = "default",
  globalAuthProfiles: List[AuthProfile] = Nil
) {
  def activeProject: WorkspaceProject = {
    projects.find(_.id == activeProjectId).orElse(projects.headOption).getOrElse(WorkspaceProject())
  }

  def withActiveProject(transform: WorkspaceProject => WorkspaceProject): WorkspaceData = {
    val current = activeProject
    val updated = transform(current)
    val nextProjects =
      if (projects.exists(_.id == current.id)) projects.map(p => if (p.id == current.id) updated else p)
      else updated :: projects
    copy(projects = nextProjects, activeProjectId = updated.id)
  }

  def recentEndpoints: List[String] = activeProject.recentEndpoints
  def methodPayloads: Map[String, String] = activeProject.methodPayloads
  def templates: List[ServiceTemplate] = activeProject.templates
  def authProfiles: List[AuthProfile] = (activeProject.authProfiles ++ globalAuthProfiles).distinctBy(_.id)
  def savedRequests: List[SavedRequest] = activeProject.savedRequests
  def defaultHeaders: List[MetadataHeader] = activeProject.defaultHeaders
  def sessionAuthPresetType: String = activeProject.sessionAuthPresetType
  def sessionAuthPresetData: Map[String, String] = activeProject.sessionAuthPresetData
  def sessionCustomHeaders: List[MetadataHeader] = activeProject.sessionCustomHeaders
  def callHistory: List[CallHistoryEntry] = activeProject.callHistory
  def environments: List[EnvironmentConfig] = activeProject.environments
  def activeEnvironmentId: Option[String] = activeProject.activeEnvironmentId
  def lastTemplateByMethod: Map[String, String] = activeProject.lastTemplateByMethod
}

object WorkspaceData {
  def fromSingleProject(
    settings: AppSettings = AppSettings(),
    recentEndpoints: List[String] = Nil,
    methodPayloads: Map[String, String] = Map.empty,
    templates: List[ServiceTemplate] = Nil,
    authProfiles: List[AuthProfile] = Nil,
    savedRequests: List[SavedRequest] = Nil,
    defaultHeaders: List[MetadataHeader] = Nil,
    sessionAuthPresetType: String = "None",
    sessionAuthPresetData: Map[String, String] = Map.empty,
    sessionCustomHeaders: List[MetadataHeader] = Nil,
    callHistory: List[CallHistoryEntry] = Nil,
    environments: List[EnvironmentConfig] = Nil,
    activeEnvironmentId: Option[String] = None
  ): WorkspaceData = {
    val (globalProfs, projectProfs) = authProfiles.partition(_.isGlobal)
    val proj = WorkspaceProject(
      id = "default",
      name = "Default",
      recentEndpoints = recentEndpoints,
      methodPayloads = methodPayloads,
      templates = templates,
      authProfiles = projectProfs,
      savedRequests = savedRequests,
      defaultHeaders = defaultHeaders,
      sessionAuthPresetType = sessionAuthPresetType,
      sessionAuthPresetData = sessionAuthPresetData,
      sessionCustomHeaders = sessionCustomHeaders,
      callHistory = callHistory,
      environments = environments,
      activeEnvironmentId = activeEnvironmentId
    )
    WorkspaceData(
      settings = settings,
      projects = List(proj),
      activeProjectId = proj.id,
      globalAuthProfiles = globalProfs
    )
  }
}

object AuthSerialization {
  def encodePreset(preset: AuthPreset): (String, Map[String, String]) = {
    preset match {
      case AuthPreset.ApiKey(h, k)    => ("ApiKey", Map("header" -> h, "key" -> k))
      case AuthPreset.BearerToken(t)  => ("BearerToken", Map("token" -> t))
      case AuthPreset.BasicAuth(u, p) => ("BasicAuth", Map("username" -> u, "password" -> p))
      case g: AuthPreset.GoogleCloud  => (g.presetType, g.toDataMap)
      case j: AuthPreset.SignedJwt    => (j.presetType, j.toDataMap)
      case _                          => ("None", Map.empty)
    }
  }

  def decodePreset(presetType: String, data: Map[String, String]): AuthPreset = {
    presetType match {
      case "ApiKey"      => AuthPreset.ApiKey(data.getOrElse("header", "x-api-key"), data.getOrElse("key", ""))
      case "BearerToken" => AuthPreset.BearerToken(data.getOrElse("token", ""))
      case "BasicAuth"   => AuthPreset.BasicAuth(data.getOrElse("username", ""), data.getOrElse("password", ""))
      case "GoogleCloud" =>
        val scopes = data.getOrElse("scopes", "https://www.googleapis.com/auth/cloud-platform")
          .split(",").map(_.trim).filter(_.nonEmpty).toList
        AuthPreset.GoogleCloud(
          tokenMode = data.getOrElse("tokenMode", "AccessToken"),
          serviceAccountJsonPath = data.get("serviceAccountJsonPath").map(_.trim).filter(_.nonEmpty),
          audience = data.get("audience").map(_.trim).filter(_.nonEmpty),
          scopes = if (scopes.nonEmpty) scopes else List("https://www.googleapis.com/auth/cloud-platform")
        )
      case "SignedJwt" =>
        val ttl = scala.util.Try(data.getOrElse("ttlSeconds", "3600").toLong).getOrElse(3600L)
        AuthPreset.SignedJwt(
          algorithm = data.getOrElse("algorithm", "HS256"),
          keyOrSecret = data.getOrElse("keyOrSecret", ""),
          issuer = data.getOrElse("issuer", ""),
          subject = data.getOrElse("subject", ""),
          audience = data.getOrElse("audience", ""),
          customClaimsJson = data.getOrElse("customClaimsJson", "{}"),
          ttlSeconds = ttl
        )
      case _             => AuthPreset.None
    }
  }
}

object CallFingerprint {
  def compute(
    endpoint: String,
    useTls: Boolean,
    fullMethodName: String,
    requestJson: String,
    authPresetType: String,
    authPresetData: Map[String, String],
    headers: List[MetadataHeader]
  ): String = {
    val normJson = stripUnquotedWhitespace(Option(requestJson).getOrElse(""))
    val sortedAuth = authPresetData.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("&")
    val activeHeaders = headers
      .filter(_.enabled)
      .map(h => s"${h.key.trim.toLowerCase}=${h.value}")
      .sorted
      .mkString("&")

    val canonical = s"${endpoint.trim}|$useTls|${fullMethodName.trim}|$normJson|$authPresetType|$sortedAuth|$activeHeaders"
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))
    digest.map("%02x".format(_)).mkString
  }

  private def stripUnquotedWhitespace(raw: String): String = {
    val sb = new StringBuilder(raw.length)
    var inQuotes = false
    var escaped = false
    for (c <- raw) {
      if (escaped) {
        sb.append(c)
        escaped = false
      } else if (c == '\\' && inQuotes) {
        sb.append(c)
        escaped = true
      } else if (c == '"') {
        inQuotes = !inQuotes
        sb.append(c)
      } else if (inQuotes || !c.isWhitespace) {
        sb.append(c)
      }
    }
    sb.toString()
  }
}
