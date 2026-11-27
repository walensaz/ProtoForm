package net.protoform.application

import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}
import net.protoform.core.error.DomainError
import net.protoform.core.workspace._
import net.protoform.ports.storage.WorkspacePort
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference

object WorkspaceService {
  val MaxCallHistoryEntries: Int = 30
}

class WorkspaceService(workspacePort: WorkspacePort) {
  import WorkspaceService.MaxCallHistoryEntries

  private val logger = LoggerFactory.getLogger(getClass)
  private val currentData = new AtomicReference[WorkspaceData](WorkspaceData())
  private val lastError = new AtomicReference[Option[DomainError]](None)
  // Serializes disk writes so concurrent in-memory updates cannot persist stale snapshots out of order.
  private val saveLock = new Object()

  def initialize(): Either[DomainError, WorkspaceData] = {
    workspacePort.loadWorkspace() match {
      case Right(data) =>
        currentData.set(data)
        lastError.set(None)
        Right(data)
      case Left(err) =>
        logger.warn("Failed to load workspace: {}", err.message)
        Left(err)
    }
  }

  def getData: WorkspaceData = currentData.get()

  def getSettings: AppSettings = currentData.get().settings

  def getLastSaveError: Option[DomainError] = lastError.get()

  def updateSettings(settings: AppSettings): Unit = {
    updateAndPersist(d => d.copy(settings = settings))
  }

  def replaceWorkspace(data: WorkspaceData): Unit = {
    updateAndPersist(_ => data)
  }

  def getProjects: List[WorkspaceProject] = currentData.get().projects

  def getActiveProject: WorkspaceProject = currentData.get().activeProject

  def createProject(name: String): WorkspaceProject = {
    val clean = Option(name).map(_.trim).filter(_.nonEmpty).getOrElse("New Project")
    val proj = WorkspaceProject(
      id = java.util.UUID.randomUUID().toString,
      name = clean,
      recentEndpoints = List("localhost:50051")
    )
    updateAndPersist { d =>
      d.copy(projects = d.projects :+ proj, activeProjectId = proj.id)
    }
    proj
  }

  def renameProject(projectId: String, newName: String): Unit = {
    val clean = Option(newName).map(_.trim).filter(_.nonEmpty).getOrElse("Project")
    updateAndPersist { d =>
      d.copy(projects = d.projects.map(p => if (p.id == projectId) p.copy(name = clean) else p))
    }
  }

  def switchProject(projectId: String): WorkspaceProject = {
    val next = updateAndPersist { d =>
      if (d.projects.exists(_.id == projectId)) d.copy(activeProjectId = projectId) else d
    }
    next.activeProject
  }

  def deleteProject(projectId: String): WorkspaceProject = {
    val next = updateAndPersist { d =>
      val remaining = d.projects.filterNot(_.id == projectId)
      val safeProjects = if (remaining.nonEmpty) remaining else List(WorkspaceProject())
      val nextActive = if (safeProjects.exists(_.id == d.activeProjectId)) d.activeProjectId else safeProjects.head.id
      d.copy(projects = safeProjects, activeProjectId = nextActive)
    }
    next.activeProject
  }

  def importProjectBundle(bundle: ProjectExportBundle, asNewProject: Boolean): WorkspaceProject = {
    val importedProj =
      if (asNewProject) {
        val existingIds = currentData.get().projects.map(_.id).toSet
        val uniqueId = if (existingIds.contains(bundle.project.id)) java.util.UUID.randomUUID().toString else bundle.project.id
        bundle.project.copy(id = uniqueId)
      } else {
        val curr = getActiveProject
        curr.copy(
          recentEndpoints = (bundle.project.recentEndpoints ++ curr.recentEndpoints).distinct.take(15),
          methodPayloads = curr.methodPayloads ++ bundle.project.methodPayloads,
          templates = (bundle.project.templates ++ curr.templates).distinctBy(_.id),
          authProfiles = (bundle.project.authProfiles ++ curr.authProfiles).distinctBy(_.id),
          environments = (bundle.project.environments ++ curr.environments).distinctBy(_.id),
          lastTemplateByMethod = curr.lastTemplateByMethod ++ bundle.project.lastTemplateByMethod
        )
      }

    val next = updateAndPersist { d =>
      val mergedGlobals = (bundle.globalAuthProfiles.map(_.copy(isGlobal = true)) ++ d.globalAuthProfiles).distinctBy(_.id)
      val nextProjects =
        if (asNewProject) d.projects :+ importedProj
        else d.projects.map(p => if (p.id == importedProj.id) importedProj else p)
      d.copy(projects = nextProjects, activeProjectId = importedProj.id, globalAuthProfiles = mergedGlobals)
    }
    next.activeProject
  }

  def buildProjectExportBundle(projectId: Option[String] = None): ProjectExportBundle = {
    val d = currentData.get()
    val proj = projectId.flatMap(id => d.projects.find(_.id == id)).getOrElse(d.activeProject)
    ProjectExportBundle(project = proj, globalAuthProfiles = d.globalAuthProfiles)
  }

  def addEndpoint(endpoint: String): Unit = {
    if (endpoint != null) {
      val clean = endpoint.trim
      if (clean.nonEmpty) {
        updateActiveProject { p =>
          val updatedList = (clean :: p.recentEndpoints.filterNot(_ == clean)).take(15)
          p.copy(recentEndpoints = updatedList)
        }
      }
    }
  }

  def saveMethodPayload(methodFullName: String, json: String): Unit = {
    if (methodFullName != null && methodFullName.trim.nonEmpty && json != null) {
      val key = methodFullName.trim
      val value = json.trim
      updateActiveProject { p =>
        p.copy(methodPayloads = p.methodPayloads + (key -> value))
      }
    }
  }

  def getMethodPayload(methodFullName: String): Option[String] = {
    if (methodFullName == null) {
      None
    } else {
      getActiveProject.methodPayloads.get(methodFullName.trim)
    }
  }

  def clearMethodPayloads(): Unit = {
    updateActiveProject(_.copy(methodPayloads = Map.empty))
  }

  def saveTemplate(template: ServiceTemplate): Unit = {
    updateActiveProject { p =>
      val existingRemoved = p.templates.filterNot(_.id == template.id)
      p.copy(templates = template :: existingRemoved)
    }
  }

  def importTemplates(incoming: List[ServiceTemplate]): Int = {
    if (incoming.isEmpty) {
      0
    } else {
      var importedCount = 0
      updateActiveProject { p =>
        val existingById = p.templates.map(t => t.id -> t).toMap
        val merged = incoming.foldLeft(p.templates) { (acc, tpl) =>
          if (existingById.contains(tpl.id)) {
            acc
          } else {
            importedCount += 1
            tpl :: acc
          }
        }
        p.copy(templates = merged)
      }
      importedCount
    }
  }

  def deleteTemplate(templateId: String): Unit = {
    updateActiveProject { p =>
      val prunedMap = p.lastTemplateByMethod.filterNot(_._2 == templateId)
      p.copy(templates = p.templates.filterNot(_.id == templateId), lastTemplateByMethod = prunedMap)
    }
  }

  def setLastTemplateForMethod(methodFullName: String, templateId: Option[String]): Unit = {
    if (methodFullName != null && methodFullName.trim.nonEmpty) {
      val key = methodFullName.trim
      updateActiveProject { p =>
        val nextMap = templateId match {
          case Some(id) => p.lastTemplateByMethod + (key -> id)
          case None     => p.lastTemplateByMethod - key
        }
        p.copy(lastTemplateByMethod = nextMap)
      }
    }
  }

  def getLastTemplateForMethod(methodFullName: String): Option[ServiceTemplate] = {
    if (methodFullName == null) None
    else {
      val p = getActiveProject
      p.lastTemplateByMethod.get(methodFullName.trim).flatMap(tid => p.templates.find(_.id == tid))
    }
  }

  def getTemplatesForMethod(methodFullName: String): List[ServiceTemplate] = {
    getActiveProject.templates.filter(_.fullMethodName == methodFullName)
  }

  def getAllTemplates: List[ServiceTemplate] = getActiveProject.templates

  def saveAuthProfile(profile: AuthProfile): Unit = {
    updateAndPersist { d =>
      if (profile.isGlobal) {
        val nextGlobals = profile :: d.globalAuthProfiles.filterNot(_.id == profile.id)
        d.withActiveProject(p => p.copy(authProfiles = p.authProfiles.filterNot(_.id == profile.id)))
          .copy(globalAuthProfiles = nextGlobals)
      } else {
        d.copy(globalAuthProfiles = d.globalAuthProfiles.filterNot(_.id == profile.id))
          .withActiveProject(p => p.copy(authProfiles = profile :: p.authProfiles.filterNot(_.id == profile.id)))
      }
    }
  }

  def deleteAuthProfile(profileId: String): Unit = {
    updateAndPersist { d =>
      d.copy(globalAuthProfiles = d.globalAuthProfiles.filterNot(_.id == profileId))
        .withActiveProject(p => p.copy(authProfiles = p.authProfiles.filterNot(_.id == profileId)))
    }
  }

  def getAuthProfiles: List[AuthProfile] = currentData.get().authProfiles

  def updateSessionAuth(
    preset: AuthPreset,
    customHeaders: List[MetadataHeader],
    profileId: Option[String]
  ): Unit = {
    val (pType, pData) = AuthSerialization.encodePreset(preset)
    updateAndPersist { d =>
      d.copy(settings = d.settings.copy(lastAuthProfileId = profileId))
        .withActiveProject(_.copy(
          lastAuthProfileId = profileId,
          sessionAuthPresetType = pType,
          sessionAuthPresetData = pData,
          sessionCustomHeaders = customHeaders
        ))
    }
  }

  def updateActiveProjectEndpoint(endpoint: String, useTls: Boolean): Unit = {
    updateAndPersist { d =>
      d.copy(settings = d.settings.copy(lastEndpoint = endpoint, lastUseTls = useTls))
        .withActiveProject(_.copy(lastEndpoint = Some(endpoint), lastUseTls = Some(useTls)))
    }
  }

  def updateActiveProjectLastMethod(methodFullName: Option[String]): Unit = {
    updateAndPersist { d =>
      d.copy(settings = d.settings.copy(lastSelectedMethod = methodFullName))
        .withActiveProject(_.copy(lastSelectedMethod = methodFullName))
    }
  }

  def updateWorkbenchTabs(tabs: List[SavedWorkbenchTab], activeIndex: Int): Unit = {
    updateActiveProject(_.copy(workbenchTabs = tabs, activeWorkbenchTabIndex = Math.max(0, activeIndex)))
  }

  def recordCallHistory(entry: CallHistoryEntry): List[CallHistoryEntry] = {
    val updated = updateActiveProject { p =>
      val withoutDup = p.callHistory.filterNot(_.fingerprint == entry.fingerprint)
      val next = (entry :: withoutDup).take(MaxCallHistoryEntries)
      p.copy(callHistory = next)
    }
    updated.callHistory
  }

  def getCallHistory: List[CallHistoryEntry] = getActiveProject.callHistory

  def clearCallHistory(): Unit = {
    updateActiveProject(_.copy(callHistory = Nil))
  }

  def getEnvironments: List[EnvironmentConfig] = getActiveProject.environments

  def getActiveEnvironment: Option[EnvironmentConfig] = {
    val p = getActiveProject
    p.activeEnvironmentId.flatMap(id => p.environments.find(_.id == id))
  }

  def saveEnvironment(env: EnvironmentConfig): Unit = {
    updateActiveProject { p =>
      val without = p.environments.filterNot(_.id == env.id)
      p.copy(environments = (env :: without).sortBy(_.name.toLowerCase))
    }
  }

  def deleteEnvironment(envId: String): Unit = {
    updateActiveProject { p =>
      val nextActive = if (p.activeEnvironmentId.contains(envId)) None else p.activeEnvironmentId
      p.copy(
        environments = p.environments.filterNot(_.id == envId),
        activeEnvironmentId = nextActive
      )
    }
  }

  def setActiveEnvironment(envId: Option[String]): Unit = {
    updateActiveProject { p =>
      val validId = envId.filter(id => p.environments.exists(_.id == id))
      p.copy(activeEnvironmentId = validId)
    }
  }

  private def updateActiveProject(transform: WorkspaceProject => WorkspaceProject): WorkspaceData = {
    updateAndPersist(_.withActiveProject(transform))
  }

  private def updateAndPersist(transform: WorkspaceData => WorkspaceData): WorkspaceData = {
    val next = currentData.updateAndGet(d => transform(d))
    val result = saveLock.synchronized {
      workspacePort.saveWorkspace(next)
    }
    result match {
      case Right(_) =>
        lastError.set(None)
      case Left(err) =>
        lastError.set(Some(err))
        logger.warn("Failed to persist workspace: {}", err.message)
    }
    next
  }
}
