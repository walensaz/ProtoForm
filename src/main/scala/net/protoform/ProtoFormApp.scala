package net.protoform

import javafx.animation.PauseTransition
import javafx.application.{Application, Platform}
import javafx.geometry.{Orientation, Pos}
import javafx.scene.Scene
import javafx.scene.control._
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import javafx.scene.layout._
import javafx.stage.{Stage, StageStyle}
import javafx.util.Duration
import net.protoform.application._
import net.protoform.infrastructure.auth.DynamicAuthResolver
import net.protoform.infrastructure.grpc.{DynamicCallAdapter, NettyGrpcChannelAdapter}
import net.protoform.infrastructure.kube.KubectlPortForwardAdapter
import net.protoform.infrastructure.reflection.GrpcServerReflectionAdapter
import net.protoform.infrastructure.schema.{DescriptorRegistry, DescriptorSetSchemaAdapter, ProtocProtoSchemaAdapter}
import net.protoform.infrastructure.storage.LocalJsonWorkspaceRepository
import net.protoform.ui.state.{AppState, ConnectionStatus, WorkbenchTabSession}
import net.protoform.ui.theme.ThemeManager
import net.protoform.ui.util.{CustomDialogStage, DockingManager, TabDragSupport, WindowResizeHelper, WindowStateController}
import net.protoform.ui.views._

import java.awt.Desktop
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class ProtoFormApp extends Application {

  private val appState = new AppState()
  private val closingStarted = new AtomicBoolean(false)

  // Outbound Adapters
  private val descriptorRegistry = new DescriptorRegistry()
  private val channelAdapter = new NettyGrpcChannelAdapter()
  private val reflectionAdapter = new GrpcServerReflectionAdapter(registry = descriptorRegistry)
  private val schemaAdapter = new DescriptorSetSchemaAdapter(descriptorRegistry)
  private val protoSourceAdapter = new ProtocProtoSchemaAdapter(schemaAdapter)
  private val callAdapter = new DynamicCallAdapter(descriptorRegistry)
  private val workspaceAdapter = new LocalJsonWorkspaceRepository()
  private val kubePortForwardAdapter = new KubectlPortForwardAdapter()
  private val dynamicAuthResolver = new DynamicAuthResolver()

  // Application Services
  private val connectionService = new ConnectionService(channelAdapter)
  private val reflectionService = new ReflectionService(reflectionAdapter)
  private val schemaService = new SchemaService(schemaAdapter, Some(protoSourceAdapter))
  private val invocationService = new InvocationService(callAdapter)
  private val formSchemaService = new FormSchemaService()
  private val workspaceService = new WorkspaceService(workspaceAdapter)

  private var windowController: WindowStateController = _
  private var mainSplitPane: SplitPane = _
  private var reqRespSplitPane: SplitPane = _
  private var targetBar: TargetBarControl = _
  private var activityRail: ActivityRail = _
  private var navPane: NavigationPane = _
  private var requestPane: RequestPane = _
  private var responsePane: ResponsePane = _
  private var workbenchTabBar: WorkbenchTabBar = _
  private var workbenchShell: VBox = _
  private var dockingManager: DockingManager = _

  private val workbenchSessions = scala.collection.mutable.ListBuffer.empty[WorkbenchTabSession]
  private var activeWorkbenchTabId: String = ""

  private var showNavPane: Boolean = false
  private var activeNavSection: String = "services"
  private var showResponsePane: Boolean = true
  private var reqRespVertical: Boolean = false
  private var rebuildingSplits: Boolean = false

  private val layoutPersistDebounce = new PauseTransition(Duration.millis(400))
  layoutPersistDebounce.setOnFinished(_ => persistLayoutSettings())

  override def start(primaryStage: Stage): Unit = {
    primaryStage.initStyle(StageStyle.UNDECORATED)
    ThemeManager.loadFonts()

    workspaceService.initialize()
    val savedSettings = workspaceService.getSettings

    // Restore Theme, Preferences & Persisted Lists
    val initialTheme =
      if (savedSettings.theme.nonEmpty && ThemeManager.themes.contains(savedSettings.theme)) savedSettings.theme
      else ThemeManager.DefaultTheme
    ThemeManager.applyTheme(initialTheme)
    appState.currentTheme.set(initialTheme)
    appState.showDefaultValues.set(savedSettings.showDefaultValues)
    appState.preserveProtoFieldNames.set(savedSettings.preserveProtoFieldNames)
    appState.autoFormatJson.set(savedSettings.autoFormatJson)
    appState.useGzipCompression.set(savedSettings.useGzipCompression)
    appState.showAdvancedActions.set(savedSettings.showAdvancedActions)
    appState.templates.setAll(workspaceService.getAllTemplates.asJava)
    appState.authProfiles.setAll(workspaceService.getAuthProfiles.asJava)
    appState.callHistory.setAll(workspaceService.getCallHistory.asJava)
    appState.environments.setAll(workspaceService.getEnvironments.asJava)
    appState.activeEnvironment.set(workspaceService.getActiveEnvironment)
    appState.projects.setAll(workspaceService.getProjects.asJava)
    appState.activeProject.set(Some(workspaceService.getActiveProject))

    showNavPane = savedSettings.showNavPane
    activeNavSection = savedSettings.activeNavSection
    showResponsePane = savedSettings.showResponsePane
    appState.showResponsePane.set(showResponsePane)
    reqRespVertical = savedSettings.requestResponseVertical

    // Construct Modular Panes
    dockingManager = new DockingManager(onRedock = () => Platform.runLater(() => redockResponsePane()))
    targetBar = new TargetBarControl(
      appState = appState,
      connectionService = connectionService,
      reflectionService = reflectionService,
      schemaService = schemaService,
      workspaceService = workspaceService,
      kubePortForwardPort = Some(kubePortForwardAdapter)
    )
    responsePane = new ResponsePane(appState, onPopOutRequested = () => popOutResponsePane())
    requestPane = new RequestPane(
      appState,
      connectionService,
      invocationService,
      formSchemaService,
      workspaceService,
      responsePane,
      onImportGrpcurlRequested = () => triggerImportGrpcurl(),
      onIntrospectRequested = () => targetBar.triggerIntrospection(),
      onLoadSchemaRequested = () => targetBar.triggerLoadDescriptorSet(),
      onOpenServicesRailRequested = () => openDrawerSection("services"),
      onQuickSwitcherRequested = () => openQuickSwitcher(),
      onSessionStateChanged = () => refreshActiveTabChip(),
      resolveSessionById = id => workbenchSessions.find(_.id == id),
      dynamicAuthPort = Some(dynamicAuthResolver)
    )
    navPane = new NavigationPane(
      appState = appState,
      workspaceService = workspaceService,
      workspaceRepo = workspaceAdapter,
      onRunSuiteRequested = () => openSuiteRunner(),
      onCollapseRequested = () => toggleDrawerCollapsed(),
      confirmLoadOverwrite = actionLabel => !requestPane.hasUnsavedEditsOrValues || CustomDialogStage.showConfirm(
        title = "Overwrite Current Request?",
        header = s"$actionLabel will replace current request values",
        message = "Your current form/JSON payload and auth settings will be overwritten.",
        confirmLabel = "Overwrite"
      ),
      onOpenMethodInNewTab = m => openMethodInNewTab(m),
      onOpenTemplateInNewTab = tpl => openTemplateInNewTab(tpl),
      onOpenHistoryInNewTab = entry => openHistoryInNewTab(entry),
      onSwitchWorkspace = id => switchToProject(id),
      onCreateWorkspace = () => createWorkspaceWithPrompt(),
      onWorkspaceMetadataUpdated = () => syncWorkspaceMetadataOnly(),
      onActiveWorkspaceReplaced = proj => refreshAfterProjectSwitch(proj)
    )
    navPane.showSection(activeNavSection)

    workbenchTabBar = new WorkbenchTabBar(
      onSelectTab = id => switchWorkbenchTab(id),
      onNewTab = () => openBlankTab(),
      onCloseTab = id => closeWorkbenchTab(id),
      onDuplicateTab = id => duplicateWorkbenchTab(id),
      onCloseOtherTabs = id => closeOtherWorkbenchTabs(id)
    )

    activityRail = new ActivityRail(
      appState = appState,
      onSectionClicked = section => handleRailSectionClick(section),
      onRunSuiteRequested = () => openSuiteRunner()
    )
    activityRail.setRailState(showNavPane, activeNavSection)

    workspaceService.getActiveEnvironment.foreach { env =>
      applyEnvironmentSelection(Some(env.id), persist = false)
    }

    setupTabDragAndRestore(savedSettings)

    reqRespSplitPane = new SplitPane()
    reqRespSplitPane.setOrientation(if (reqRespVertical) Orientation.VERTICAL else Orientation.HORIZONTAL)
    VBox.setVgrow(reqRespSplitPane, Priority.ALWAYS)
    workbenchShell = new VBox(workbenchTabBar, reqRespSplitPane)

    initWorkbenchSessionsFromProject(workspaceService.getActiveProject)

    mainSplitPane = new SplitPane()
    rebuildLayoutPanes(savedSettings.splitDividerPositions)

    val titleBarActions = new TitleBarActions {
      override def openSettingsDialog(): Unit = ProtoFormApp.this.openSettingsDialog()
      override def openQuickSwitcher(): Unit = ProtoFormApp.this.openQuickSwitcher()
      override def loadDescriptorSet(): Unit = targetBar.triggerLoadDescriptorSet()
      override def loadProtoFiles(): Unit = targetBar.triggerLoadProtoFiles()
      override def importGrpcurl(): Unit = ProtoFormApp.this.triggerImportGrpcurl()
      override def selectProject(projectId: String): Unit = ProtoFormApp.this.switchToProject(projectId)
      override def createWorkspace(): Unit = ProtoFormApp.this.createWorkspaceWithPrompt()
      override def manageProjects(): Unit = openDrawerSection("workspaces")
      override def selectEnvironment(envId: Option[String]): Unit = ProtoFormApp.this.applyEnvironmentSelection(envId)
      override def manageEnvironments(): Unit = ProtoFormApp.this.openEnvironmentManager()
      override def toggleNavPane(): Unit = toggleDrawerCollapsed()
      override def toggleResponsePane(): Unit = {
        showResponsePane = !showResponsePane
        appState.showResponsePane.set(showResponsePane)
        if (!showResponsePane && dockingManager.isFloating) {
          dockingManager.closeAndRedock()
        }
        rebuildLayoutPanes(workspaceService.getSettings.splitDividerPositions)
        persistLayoutSettings()
      }
      override def toggleSplitOrientation(): Unit = {
        reqRespVertical = !reqRespVertical
        reqRespSplitPane.setOrientation(if (reqRespVertical) Orientation.VERTICAL else Orientation.HORIZONTAL)
        persistLayoutSettings()
      }
      override def resetLayout(): Unit = resetPaneLayout()
      override def openWorkspaceFolder(): Unit = ProtoFormApp.this.openWorkspaceFolder()
    }

    windowController = new WindowStateController(
      stage = primaryStage,
      initialWidth = savedSettings.windowWidth,
      initialHeight = savedSettings.windowHeight,
      initialX = savedSettings.windowX,
      initialY = savedSettings.windowY,
      onBoundsChanged = bounds => persistWindowBounds(bounds)
    )

    val titleBar = new CustomTitleBar(primaryStage, windowController, appState, workspaceService, targetBar, titleBarActions, () => handleAppClose(primaryStage))

    val mainShell = new HBox(activityRail, mainSplitPane)
    HBox.setHgrow(mainSplitPane, Priority.ALWAYS)

    val root = new BorderPane()
    root.getStyleClass.add("app-root")
    root.setTop(titleBar)
    root.setCenter(mainShell)
    root.setBottom(createStatusBar())

    val width = Math.max(1000.0, savedSettings.windowWidth)
    val height = Math.max(640.0, savedSettings.windowHeight)
    val scene = new Scene(root, width, height)
    ThemeManager.registerScene(scene, primaryStage)
    installKeyboardShortcuts(scene)

    WindowResizeHelper.install(primaryStage, () => windowController.isMaximized)

    primaryStage.setTitle("ProtoForm — Dynamic gRPC Client")
    ThemeManager.applyStageIcons(primaryStage)
    primaryStage.setScene(scene)
    primaryStage.setMinWidth(1000)
    primaryStage.setMinHeight(640)
    windowController.install()

    primaryStage.setOnCloseRequest { _ =>
      handleAppClose(primaryStage)
    }

    primaryStage.show()
    if (savedSettings.isMaximized) {
      Platform.runLater(() => windowController.setMaximized(true))
    }
  }

  private def handleRailSectionClick(section: String): Unit = {
    if (showNavPane && activeNavSection == section) {
      showNavPane = false
    } else {
      activeNavSection = section
      navPane.showSection(section)
      showNavPane = true
    }
    activityRail.setRailState(showNavPane, activeNavSection)
    rebuildLayoutPanes(workspaceService.getSettings.splitDividerPositions)
    persistLayoutSettings()
  }

  private def openDrawerSection(section: String): Unit = {
    activeNavSection = section
    navPane.showSection(section)
    showNavPane = true
    activityRail.setRailState(showNavPane, activeNavSection)
    rebuildLayoutPanes(workspaceService.getSettings.splitDividerPositions)
    persistLayoutSettings()
  }

  private def toggleDrawerCollapsed(): Unit = {
    showNavPane = !showNavPane
    activityRail.setRailState(showNavPane, activeNavSection)
    rebuildLayoutPanes(workspaceService.getSettings.splitDividerPositions)
    persistLayoutSettings()
  }

  private def attachDividerListeners(pane: SplitPane): Unit = {
    pane.getDividers.asScala.foreach { div =>
      div.positionProperty().addListener { (_, _, _) =>
        if (!rebuildingSplits) {
          layoutPersistDebounce.playFromStart()
        }
      }
    }
  }

  private def rebuildLayoutPanes(savedDividers: List[Double]): Unit = {
    rebuildingSplits = true
    try {
      reqRespSplitPane.getItems.clear()
      reqRespSplitPane.getItems.add(requestPane)
      if (showResponsePane && !dockingManager.isFloating) {
        reqRespSplitPane.getItems.add(responsePane)
        val ratio = savedDividers.lift(1).filter(r => r > 0.15 && r < 0.85).getOrElse(0.50)
        reqRespSplitPane.setDividerPositions(ratio)
      }

      mainSplitPane.getItems.clear()
      if (showNavPane) {
        mainSplitPane.getItems.addAll(navPane, workbenchShell)
        val navRatio = savedDividers.headOption.filter(r => r > 0.12 && r < 0.50).getOrElse(0.22)
        mainSplitPane.setDividerPositions(navRatio)
      } else {
        mainSplitPane.getItems.add(workbenchShell)
      }
      attachDividerListeners(reqRespSplitPane)
      attachDividerListeners(mainSplitPane)
    } finally {
      rebuildingSplits = false
    }
  }

  private def currentDividerSnapshot: List[Double] = {
    val saved = workspaceService.getSettings.splitDividerPositions
    val navRatio =
      if (mainSplitPane.getDividers.size() >= 1) mainSplitPane.getDividerPositions.head
      else saved.headOption.getOrElse(0.22)
    val respRatio =
      if (reqRespSplitPane.getDividers.size() >= 1) reqRespSplitPane.getDividerPositions.head
      else saved.lift(1).getOrElse(0.50)
    List(navRatio, respRatio)
  }

  private def setupTabDragAndRestore(saved: net.protoform.core.workspace.AppSettings): Unit = {
    val reqRespTabs: Map[String, Tab] = Map(
      "form"         -> requestPane.formTab,
      "json"         -> requestPane.jsonTab,
      "auth"         -> requestPane.authTab,
      "resp-body"    -> responsePane.bodyTab,
      "resp-headers" -> responsePane.headersTab,
      "resp-stream"  -> responsePane.streamTab
    )

    applyTabOrder(saved.requestTabOrder, saved.responseTabOrder, reqRespTabs)

    val lookupReqResp: String => Option[(String, TabPane, Tab)] = id => {
      reqRespTabs.get(id).flatMap { tab =>
        if (requestPane.tabPane.getTabs.contains(tab)) Some(("req", requestPane.tabPane, tab))
        else if (responsePane.tabPane.getTabs.contains(tab)) Some(("resp", responsePane.tabPane, tab))
        else None
      }
    }

    TabDragSupport.registerTabPane("req", requestPane.tabPane, Set("req", "resp"), lookupReqResp, () => persistLayoutSettings())
    TabDragSupport.registerTabPane("resp", responsePane.tabPane, Set("req", "resp"), lookupReqResp, () => persistLayoutSettings())
  }

  private def applyTabOrder(
    reqOrder: List[String],
    respOrder: List[String],
    reqRespTabs: Map[String, Tab]
  ): Unit = {
    val reqTabs = reqOrder.flatMap(reqRespTabs.get)
    val respTabs = respOrder.flatMap(reqRespTabs.get).filterNot(reqTabs.contains)
    val assigned = (reqTabs ++ respTabs).toSet
    val missingReq = List("form", "json", "auth").flatMap(reqRespTabs.get).filterNot(assigned.contains)
    val missingResp = List("resp-body", "resp-headers", "resp-stream").flatMap(reqRespTabs.get).filterNot(assigned.contains)

    requestPane.tabPane.getTabs.setAll((reqTabs ++ missingReq): _*)
    responsePane.tabPane.getTabs.setAll((respTabs ++ missingResp): _*)
    if (!requestPane.tabPane.getTabs.isEmpty) requestPane.tabPane.getSelectionModel.selectFirst()
    if (!responsePane.tabPane.getTabs.isEmpty) responsePane.tabPane.getSelectionModel.selectFirst()
  }

  private def currentTabIds(pane: TabPane): List[String] = {
    pane.getTabs.asScala.toList.flatMap(TabDragSupport.getTabId)
  }

  private def persistWindowBounds(bounds: net.protoform.ui.util.WindowBounds): Unit = {
    val s = workspaceService.getSettings
    workspaceService.updateSettings(s.copy(
      windowWidth = bounds.width,
      windowHeight = bounds.height,
      windowX = bounds.x,
      windowY = bounds.y,
      isMaximized = bounds.isMaximized
    ))
  }

  private def persistLayoutSettings(): Unit = {
    captureActiveSession()
    val activeIdx = Math.max(0, workbenchSessions.indexWhere(_.id == activeWorkbenchTabId))
    workspaceService.updateWorkbenchTabs(workbenchSessions.toList.map(_.toSavedTab), activeIdx)
    val s = workspaceService.getSettings
    workspaceService.updateSettings(s.copy(
      showNavPane = showNavPane,
      activeNavSection = activeNavSection,
      showResponsePane = showResponsePane,
      showAdvancedActions = appState.showAdvancedActions.get,
      requestResponseVertical = reqRespVertical,
      splitDividerPositions = currentDividerSnapshot,
      requestTabOrder = currentTabIds(requestPane.tabPane),
      responseTabOrder = currentTabIds(responsePane.tabPane)
    ))
  }

  private def resetPaneLayout(): Unit = {
    if (dockingManager.isFloating) {
      dockingManager.closeAndRedock()
    }
    showNavPane = false
    activeNavSection = "services"
    navPane.showSection(activeNavSection)
    activityRail.setRailState(showNavPane, activeNavSection)
    showResponsePane = true
    appState.showResponsePane.set(true)
    reqRespVertical = false
    reqRespSplitPane.setOrientation(Orientation.HORIZONTAL)
    requestPane.tabPane.getTabs.setAll(requestPane.formTab, requestPane.jsonTab, requestPane.authTab)
    responsePane.tabPane.getTabs.setAll(responsePane.bodyTab, responsePane.headersTab, responsePane.streamTab)
    requestPane.tabPane.getSelectionModel.selectFirst()
    responsePane.tabPane.getSelectionModel.selectFirst()
    rebuildLayoutPanes(List(0.22, 0.50))
    persistLayoutSettings()
    appState.statusText.set("Restored default 2-pane layout and tab order.")
  }

  private def popOutResponsePane(): Unit = {
    if (!dockingManager.isFloating) {
      reqRespSplitPane.getItems.remove(responsePane)
      responsePane.setPopOutButtonVisible(false)
      dockingManager.popOut("Response Viewer", responsePane)
      appState.statusText.set("Popped out Response Viewer into floating window.")
    }
  }

  private def redockResponsePane(): Unit = {
    responsePane.setPopOutButtonVisible(true)
    if (showResponsePane && !reqRespSplitPane.getItems.contains(responsePane)) {
      rebuildLayoutPanes(workspaceService.getSettings.splitDividerPositions)
    }
    appState.statusText.set("Re-docked Response Viewer.")
  }

  private def openSettingsDialog(): Unit = {
    SettingsDialog.show(
      currentSettings = workspaceService.getSettings,
      workspaceService = workspaceService,
      workspaceRepo = workspaceAdapter,
      onResetLayout = () => resetPaneLayout(),
      onStatusMessage = msg => appState.statusText.set(msg)
    ).foreach { updated =>
      workspaceService.updateSettings(updated)
      ThemeManager.applyTheme(updated.theme)
      appState.currentTheme.set(updated.theme)
      appState.showDefaultValues.set(updated.showDefaultValues)
      appState.preserveProtoFieldNames.set(updated.preserveProtoFieldNames)
      appState.autoFormatJson.set(updated.autoFormatJson)
      appState.useGzipCompression.set(updated.useGzipCompression)
      appState.showAdvancedActions.set(updated.showAdvancedActions)

      showNavPane = updated.showNavPane
      activityRail.setRailState(showNavPane, activeNavSection)
      showResponsePane = updated.showResponsePane
      appState.showResponsePane.set(showResponsePane)
      reqRespVertical = updated.requestResponseVertical
      reqRespSplitPane.setOrientation(if (reqRespVertical) Orientation.VERTICAL else Orientation.HORIZONTAL)
      rebuildLayoutPanes(updated.splitDividerPositions)
      appState.statusText.set("Settings saved.")
    }
  }

  private def openQuickSwitcher(): Unit = {
    val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
    val templates = appState.templates.asScala.toList
    val history = appState.callHistory.asScala.toList
    val projects = workspaceService.getProjects
    val activeId = workspaceService.getActiveProject.id

    QuickSwitcherDialog.show(methods, templates, history, projects, activeId).foreach {
      case QuickSwitcherDialog.MethodTarget(m) =>
        navPane.selectMethodByName(m.fullMethodName)
      case QuickSwitcherDialog.TemplateTarget(t) =>
        navPane.loadTemplate(t)
      case QuickSwitcherDialog.HistoryTarget(h) =>
        navPane.loadHistoryEntry(h)
      case QuickSwitcherDialog.WorkspaceTarget(p, _) =>
        switchToProject(p.id)
      case QuickSwitcherDialog.ActionTarget("new-workspace", _, _, _) =>
        createWorkspaceWithPrompt()
      case QuickSwitcherDialog.ActionTarget("suite-runner", _, _, _) =>
        openSuiteRunner()
      case _ => ()
    }
  }

  private val suiteRunnerService = new SuiteRunnerService(invocationService, Some(dynamicAuthResolver))

  private def switchToProject(projectId: String): Unit = {
    if (workspaceService.getActiveProject.id == projectId) return
    requestPane.flushUnsavedPayload()
    val proj = workspaceService.switchProject(projectId)
    refreshAfterProjectSwitch(proj)
  }

  private def createWorkspaceWithPrompt(): Unit = {
    CustomDialogStage.showTextPrompt(
      title = "New Workspace",
      header = "Create a new isolated workspace",
      label = "Workspace Name:",
      initialValue = "My Workspace"
    ).foreach { rawName =>
      val name = rawName.trim
      if (name.nonEmpty) {
        requestPane.flushUnsavedPayload()
        val created = workspaceService.createProject(name)
        refreshAfterProjectSwitch(created)
      }
    }
  }

  private def syncWorkspaceMetadataOnly(): Unit = {
    val active = workspaceService.getActiveProject
    appState.projects.setAll(workspaceService.getProjects.asJava)
    appState.activeProject.set(Some(active))
  }

  private def refreshAfterProjectSwitch(proj: net.protoform.core.workspace.WorkspaceProject): Unit = {
    appState.projects.setAll(workspaceService.getProjects.asJava)
    appState.activeProject.set(Some(proj))
    appState.activeTemplate.set(None)
    appState.templates.setAll(workspaceService.getAllTemplates.asJava)
    appState.authProfiles.setAll(workspaceService.getAuthProfiles.asJava)
    appState.callHistory.setAll(workspaceService.getCallHistory.asJava)
    appState.environments.setAll(workspaceService.getEnvironments.asJava)
    appState.activeEnvironment.set(workspaceService.getActiveEnvironment)
    targetBar.refreshFromActiveProject()
    requestPane.refreshMetadataForProjectChange()
    initWorkbenchSessionsFromProject(proj)
    appState.statusText.set(s"Active workspace: ${proj.name}")
  }

  private def activeSessionOpt: Option[WorkbenchTabSession] = {
    workbenchSessions.find(_.id == activeWorkbenchTabId)
  }

  private def captureActiveSession(): Unit = {
    activeSessionOpt.foreach { s =>
      requestPane.captureIntoSession(s)
      responsePane.captureIntoSession(s)
    }
  }

  private def refreshActiveTabChip(): Unit = {
    captureActiveSession()
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
  }

  private def initWorkbenchSessionsFromProject(proj: net.protoform.core.workspace.WorkspaceProject): Unit = {
    workbenchSessions.clear()
    val restored = proj.workbenchTabs.map(WorkbenchTabSession.fromSavedTab)
    if (restored.nonEmpty) {
      workbenchSessions ++= restored
    } else {
      val initialTitle = proj.lastSelectedMethod.map(_.split('/').last).getOrElse("New Request")
      workbenchSessions += new WorkbenchTabSession(
        title = initialTitle,
        methodFullName = proj.lastSelectedMethod,
        authPreset = net.protoform.core.workspace.AuthSerialization.decodePreset(proj.sessionAuthPresetType, proj.sessionAuthPresetData),
        customHeaders = proj.sessionCustomHeaders,
        selectedAuthProfileId = proj.lastAuthProfileId
      )
    }
    val safeIdx = Math.max(0, Math.min(proj.activeWorkbenchTabIndex, workbenchSessions.size - 1))
    val active = workbenchSessions(safeIdx)
    activeWorkbenchTabId = active.id
    val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
    requestPane.restoreFromSession(active, methods)
    responsePane.restoreFromSession(active)
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
  }

  private def switchWorkbenchTab(tabId: String): Unit = {
    if (tabId == activeWorkbenchTabId) return
    captureActiveSession()
    workbenchSessions.find(_.id == tabId).foreach { next =>
      activeWorkbenchTabId = next.id
      val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
      requestPane.restoreFromSession(next, methods)
      responsePane.restoreFromSession(next)
      workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
      persistLayoutSettings()
    }
  }

  private def openBlankTab(): Unit = {
    captureActiveSession()
    val s = new WorkbenchTabSession(
      title = "New Request",
      authPreset = Option(appState.authPreset.get).getOrElse(net.protoform.core.auth.AuthPreset.None),
      customHeaders = appState.customHeaders.asScala.toList,
      selectedAuthProfileId = Option(appState.selectedAuthProfile.get).flatten.map(_.id)
    )
    workbenchSessions += s
    activeWorkbenchTabId = s.id
    val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
    requestPane.restoreFromSession(s, methods)
    responsePane.restoreFromSession(s)
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
  }

  private def openMethodInNewTab(method: net.protoform.core.schema.MethodSchema): Unit = {
    captureActiveSession()
    val s = new WorkbenchTabSession(
      title = method.name,
      methodFullName = Some(method.fullMethodName),
      authPreset = Option(appState.authPreset.get).getOrElse(net.protoform.core.auth.AuthPreset.None),
      customHeaders = appState.customHeaders.asScala.toList,
      selectedAuthProfileId = Option(appState.selectedAuthProfile.get).flatten.map(_.id)
    )
    workbenchSessions += s
    activeWorkbenchTabId = s.id
    val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
    requestPane.restoreFromSession(s, methods)
    responsePane.restoreFromSession(s)
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
    appState.statusText.set(s"Opened ${method.name} in new tab.")
  }

  private def openTemplateInNewTab(tpl: net.protoform.core.workspace.ServiceTemplate): Unit = {
    captureActiveSession()
    val s = new WorkbenchTabSession(
      title = tpl.name,
      methodFullName = Some(tpl.fullMethodName),
      requestJson = tpl.requestJson,
      authPreset = net.protoform.core.workspace.AuthSerialization.decodePreset(tpl.authPresetType, tpl.authPresetData),
      customHeaders = tpl.customHeaders,
      activeTemplateId = Some(tpl.id)
    )
    workbenchSessions += s
    activeWorkbenchTabId = s.id
    val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
    requestPane.restoreFromSession(s, methods)
    responsePane.restoreFromSession(s)
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
    appState.statusText.set(s"Opened template '${tpl.name}' in new tab.")
  }

  private def openHistoryInNewTab(entry: net.protoform.core.workspace.CallHistoryEntry): Unit = {
    captureActiveSession()
    val s = new WorkbenchTabSession(
      title = entry.methodName,
      methodFullName = Some(entry.fullMethodName),
      requestJson = entry.requestJson,
      authPreset = net.protoform.core.workspace.AuthSerialization.decodePreset(entry.authPresetType, entry.authPresetData),
      customHeaders = entry.customHeaders
    )
    workbenchSessions += s
    activeWorkbenchTabId = s.id
    val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
    requestPane.restoreFromSession(s, methods)
    responsePane.restoreFromSession(s)
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
    appState.statusText.set(s"Opened history ${entry.methodName} in new tab.")
  }

  private def duplicateWorkbenchTab(tabId: String): Unit = {
    captureActiveSession()
    workbenchSessions.find(_.id == tabId).foreach { src =>
      val copy = new WorkbenchTabSession(
        title = s"${src.title} (Copy)",
        methodFullName = src.methodFullName,
        requestJson = src.requestJson,
        authPreset = src.authPreset,
        customHeaders = src.customHeaders,
        selectedAuthProfileId = src.selectedAuthProfileId,
        activeTemplateId = src.activeTemplateId,
        lastEditedIsForm = src.lastEditedIsForm
      )
      workbenchSessions += copy
      switchWorkbenchTab(copy.id)
    }
  }

  private def closeWorkbenchTab(tabId: String): Unit = {
    captureActiveSession()
    val idx = workbenchSessions.indexWhere(_.id == tabId)
    if (idx < 0) return
    val target = workbenchSessions(idx)
    if (target.userEdited && !CustomDialogStage.showConfirm(
      title = "Close Unsaved Tab?",
      header = s"Close '${target.title}'?",
      message = "This tab has unsaved edits that will be lost.",
      confirmLabel = "Close Tab",
      destructive = true
    )) return

    target.activeStreamingCallId.foreach(invocationService.cancel)
    target.activeClientStream.foreach(_.cancel("Tab closed"))

    if (workbenchSessions.size == 1) {
      val blank = new WorkbenchTabSession(title = "New Request")
      workbenchSessions.update(0, blank)
      activeWorkbenchTabId = blank.id
      val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
      requestPane.restoreFromSession(blank, methods)
      responsePane.restoreFromSession(blank)
    } else {
      workbenchSessions.remove(idx)
      if (tabId == activeWorkbenchTabId) {
        val nextIdx = Math.max(0, Math.min(idx, workbenchSessions.size - 1))
        val next = workbenchSessions(nextIdx)
        activeWorkbenchTabId = next.id
        val methods = appState.discoveredServices.asScala.toList.flatMap(_.methods)
        requestPane.restoreFromSession(next, methods)
        responsePane.restoreFromSession(next)
      }
    }
    workbenchTabBar.refresh(workbenchSessions.toList, activeWorkbenchTabId)
    persistLayoutSettings()
  }

  private def closeOtherWorkbenchTabs(keepId: String): Unit = {
    captureActiveSession()
    val others = workbenchSessions.filterNot(_.id == keepId).toList
    others.foreach { s =>
      s.activeStreamingCallId.foreach(invocationService.cancel)
      s.activeClientStream.foreach(_.cancel("Tabs closed"))
    }
    workbenchSessions.filterInPlace(_.id == keepId)
    switchWorkbenchTab(keepId)
  }

  private def applyEnvironmentSelection(envIdOpt: Option[String], persist: Boolean = true): Unit = {
    if (persist) {
      workspaceService.setActiveEnvironment(envIdOpt)
    }
    val envOpt = envIdOpt.flatMap(id => workspaceService.getEnvironments.find(_.id == id))
    appState.activeEnvironment.set(envOpt)
    envOpt.foreach { env =>
      targetBar.applyEnvironmentBundle(env)
      env.authProfileId.flatMap(pid => workspaceService.getAuthProfiles.find(_.id == pid)).foreach { profile =>
        appState.applyAuthProfile(profile)
        requestPane.applyImportedAuth(profile.preset, profile.customHeaders)
      }
      appState.statusText.set(s"Switched environment to '${env.name}' (${env.endpoint}).")
    }
    if (envOpt.isEmpty && persist) {
      appState.statusText.set("Cleared active environment.")
    }
  }

  private def openEnvironmentManager(): Unit = {
    val activeId = Option(appState.activeEnvironment.get).flatten.map(_.id)
    EnvironmentManagerDialog.show(
      workspaceService = workspaceService,
      authProfiles = workspaceService.getAuthProfiles,
      initialSelectedId = activeId
    ).foreach { nextActiveId =>
      appState.environments.setAll(workspaceService.getEnvironments.asJava)
      applyEnvironmentSelection(nextActiveId)
    }
  }

  private def openSuiteRunner(): Unit = {
    val templates = workspaceService.getAllTemplates
    val services = appState.discoveredServices.asScala.toList
    SuiteRunnerDialog.show(
      appState = appState,
      templates = templates,
      services = services,
      connectionService = connectionService,
      suiteRunnerService = suiteRunnerService,
      workspaceService = workspaceService,
      resolveTarget = () => targetBar.currentEndpointTarget
    )
  }

  private def triggerImportGrpcurl(): Unit = {
    GrpcurlImportDialog.show().foreach { parsed =>
      val settings = workspaceService.getSettings
      val updatedSettings = settings.copy(
        insecureSkipTlsVerify = parsed.insecureSkipTlsVerify,
        customCaCertPath = parsed.caCertPath.orElse(settings.customCaCertPath),
        clientCertPath = parsed.clientCertPath.orElse(settings.clientCertPath),
        clientKeyPath = parsed.clientKeyPath.orElse(settings.clientKeyPath)
      )
      workspaceService.updateSettings(updatedSettings)

      val (preset, remainingHeaders) = parsed.extractAuthAndHeaders
      requestPane.applyImportedAuth(preset, remainingHeaders)

      targetBar.applyImportedConnection(
        endpointOpt = parsed.endpoint,
        useTls = parsed.useTls,
        overrideAuthority = parsed.overrideAuthority,
        protoFiles = parsed.protoFiles,
        importPaths = parsed.importPaths,
        targetMethodOpt = parsed.fullMethodName,
        onMethodReady = () => {
          requestPane.applyImportedJson(parsed.requestJson)
          appState.statusText.set(
            s"Imported grpcurl command${parsed.fullMethodName.map(m => s" for $m").getOrElse("")}."
          )
        }
      )
    }
  }

  private def openWorkspaceFolder(): Unit = {
    try {
      val dir = workspaceAdapter.configDir.toFile
      if (!dir.exists()) dir.mkdirs()
      if (Desktop.isDesktopSupported) Desktop.getDesktop.open(dir)
    } catch {
      case NonFatal(e) => appState.statusText.set(s"Could not open folder: ${e.getMessage}")
    }
  }

  private def installKeyboardShortcuts(scene: Scene): Unit = {
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN),
      () => requestPane.triggerPrimaryOrChunkAction()
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN),
      () => openQuickSwitcher()
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
      () => openSettingsDialog()
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
      () => toggleDrawerCollapsed()
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
      () => openDrawerSection("services")
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN),
      () => openDrawerSection("templates")
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN),
      () => openDrawerSection("history")
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.SHORTCUT_DOWN),
      () => openDrawerSection("workspaces")
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN),
      () => targetBar.focusEndpoint()
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN),
      () => openBlankTab()
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN),
      () => closeWorkbenchTab(activeWorkbenchTabId)
    )
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
      () => if (workbenchSessions.size > 1) {
        val cur = workbenchSessions.indexWhere(_.id == activeWorkbenchTabId)
        val next = workbenchSessions((cur + 1) % workbenchSessions.size)
        switchWorkbenchTab(next.id)
      }
    )
  }

  private def handleAppClose(primaryStage: Stage): Unit = {
    if (!closingStarted.compareAndSet(false, true)) return

    captureActiveSession()
    val activeIdx = Math.max(0, workbenchSessions.indexWhere(_.id == activeWorkbenchTabId))
    workspaceService.updateWorkbenchTabs(workbenchSessions.toList.map(_.toSavedTab), activeIdx)
    requestPane.flushUnsavedPayload()
    requestPane.shutdown()
    if (dockingManager != null && dockingManager.isFloating) {
      dockingManager.closeAndRedock()
    }
    val bounds = windowController.currentSnapshot
    val currentSettings = workspaceService.getSettings.copy(
      theme = appState.currentTheme.get,
      showDefaultValues = appState.showDefaultValues.get,
      preserveProtoFieldNames = appState.preserveProtoFieldNames.get,
      autoFormatJson = appState.autoFormatJson.get,
      useGzipCompression = appState.useGzipCompression.get,
      windowWidth = bounds.width,
      windowHeight = bounds.height,
      windowX = bounds.x,
      windowY = bounds.y,
      isMaximized = bounds.isMaximized,
      splitDividerPositions = currentDividerSnapshot,
      requestResponseVertical = reqRespVertical,
      showNavPane = showNavPane,
      activeNavSection = activeNavSection,
      showResponsePane = showResponsePane,
      showAdvancedActions = appState.showAdvancedActions.get,
      requestTabOrder = currentTabIds(requestPane.tabPane),
      responseTabOrder = currentTabIds(responsePane.tabPane)
    )
    workspaceService.updateSettings(currentSettings)
    kubePortForwardAdapter.stopAll()
    connectionService.shutdown()
    primaryStage.close()
    Platform.exit()
  }

  private def createStatusBar(): HBox = {
    val bar = new HBox(12)
    bar.setAlignment(Pos.CENTER_LEFT)
    bar.getStyleClass.add("status-bar")

    val statusDot = new Region()
    statusDot.getStyleClass.add("status-dot")

    val connectionText = new Label("Disconnected")
    connectionText.getStyleClass.add("status-text-idle")

    val connectionBox = new HBox(7, statusDot, connectionText)
    connectionBox.setAlignment(Pos.CENTER_LEFT)

    val dotStates = List("status-dot-connecting", "status-dot-connected", "status-dot-warn", "status-dot-error")
    val textStates = List("status-text-idle", "status-text-connecting", "status-text-connected", "status-text-warn", "status-text-error")

    appState.connectionStatus.addListener { (_, _, status) =>
      Platform.runLater { () =>
        statusDot.getStyleClass.removeAll(dotStates: _*)
        connectionText.getStyleClass.removeAll(textStates: _*)
        status match {
          case ConnectionStatus.Disconnected =>
            connectionText.setText("Disconnected")
            connectionText.getStyleClass.add("status-text-idle")
          case ConnectionStatus.Connecting(ep) =>
            connectionText.setText(s"Connecting to $ep...")
            statusDot.getStyleClass.add("status-dot-connecting")
            connectionText.getStyleClass.add("status-text-connecting")
          case ConnectionStatus.Connected(ep, count) =>
            connectionText.setText(s"Connected ($ep) · $count services")
            statusDot.getStyleClass.add(if (count > 0) "status-dot-connected" else "status-dot-warn")
            connectionText.getStyleClass.add(if (count > 0) "status-text-connected" else "status-text-warn")
          case ConnectionStatus.Failed(ep, _) =>
            connectionText.setText(s"Connection Failed ($ep)")
            statusDot.getStyleClass.add("status-dot-error")
            connectionText.getStyleClass.add("status-text-error")
        }
      }
    }

    val spinner = new ProgressIndicator()
    spinner.setPrefSize(14, 14)
    spinner.setMaxSize(14, 14)
    spinner.visibleProperty().bind(appState.isBusy)
    spinner.managedProperty().bind(appState.isBusy)

    val statusLabel = new Label()
    statusLabel.textProperty().bind(appState.statusText)
    statusLabel.getStyleClass.add("form-subtext")

    val spacer = new Region()
    HBox.setHgrow(spacer, Priority.ALWAYS)

    val shortcutHint = new Label("Ctrl+Enter Send  ·  Ctrl+K Jump  ·  Ctrl+B Sidebar  ·  Ctrl+L Target  ·  Ctrl+, Settings")
    shortcutHint.getStyleClass.add("info-strip")

    bar.getChildren.addAll(connectionBox, new Separator(Orientation.VERTICAL), spinner, statusLabel, spacer, shortcutHint)
    bar
  }
}

object ProtoFormApp {
  def main(args: Array[String]): Unit = {
    Application.launch(classOf[ProtoFormApp], args: _*)
  }
}
