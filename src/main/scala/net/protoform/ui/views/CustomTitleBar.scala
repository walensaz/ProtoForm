package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos, Side}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.scene.shape.{Circle, SVGPath, StrokeLineCap, StrokeLineJoin}
import javafx.stage.Stage
import net.protoform.application.WorkspaceService
import net.protoform.ui.state.AppState
import net.protoform.ui.theme.ThemeManager
import net.protoform.ui.util.{WindowResizeHelper, WindowStateController}
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.jdk.CollectionConverters._

trait TitleBarActions {
  def openSettingsDialog(): Unit
  def openQuickSwitcher(): Unit
  def loadDescriptorSet(): Unit
  def loadProtoFiles(): Unit
  def importGrpcurl(): Unit
  def selectProject(projectId: String): Unit
  def createWorkspace(): Unit
  def manageProjects(): Unit
  def selectEnvironment(envId: Option[String]): Unit
  def manageEnvironments(): Unit
  def toggleNavPane(): Unit
  def toggleResponsePane(): Unit
  def toggleSplitOrientation(): Unit
  def resetLayout(): Unit
  def openWorkspaceFolder(): Unit
}

class CustomTitleBar(
  stage: Stage,
  windowController: WindowStateController,
  appState: AppState,
  workspaceService: WorkspaceService,
  targetBar: TargetBarControl,
  actions: TitleBarActions,
  onCloseRequested: () => Unit
) extends HBox(8) {

  setAlignment(Pos.CENTER_LEFT)
  setPadding(new Insets(6, 10, 6, 10))
  setMinWidth(0)
  getStyleClass.add("custom-title-bar")

  // --- Left Section: Brand Quick Menu + Unified Target Pill + Environment ---
  private def createBrandOwlMark(): Pane = {
    val shield = new SVGPath()
    shield.setContent("M 8 4.6 L 13.6 2.6 L 13.0 9.5 L 8 14.2 L 3.0 9.5 L 2.4 2.6 Z")
    shield.getStyleClass.add("brand-owl-shield")
    shield.setStrokeLineJoin(StrokeLineJoin.ROUND)

    val brow = new SVGPath()
    brow.setContent("M 2.2 2.8 L 8 5.9 L 13.8 2.8")
    brow.getStyleClass.add("brand-owl-brow")
    brow.setStrokeLineCap(StrokeLineCap.ROUND)
    brow.setStrokeLineJoin(StrokeLineJoin.ROUND)

    val leftEye = new Circle(5.3, 7.8, 1.55)
    leftEye.getStyleClass.add("brand-owl-eye")
    val leftPupil = new Circle(5.3, 7.8, 0.68)
    leftPupil.getStyleClass.add("brand-owl-pupil")

    val rightEye = new Circle(10.7, 7.8, 1.55)
    rightEye.getStyleClass.add("brand-owl-eye")
    val rightPupil = new Circle(10.7, 7.8, 0.68)
    rightPupil.getStyleClass.add("brand-owl-pupil")

    val beak = new SVGPath()
    beak.setContent("M 8 8.6 L 9.25 10.35 L 8 12.6 L 6.75 10.35 Z")
    beak.getStyleClass.add("brand-owl-beak")

    val pane = new Pane(shield, brow, leftEye, leftPupil, rightEye, rightPupil, beak)
    pane.setMinSize(16, 16)
    pane.setPrefSize(16, 16)
    pane.setMaxSize(16, 16)
    pane
  }

  val brandMark = new StackPane(createBrandOwlMark())
  brandMark.getStyleClass.add("brand-mark")

  val brandTitle = new Label("ProtoForm")
  brandTitle.getStyleClass.add("brand-title")

  val chevron = FontIcon.of(Feather.CHEVRON_DOWN, 12)
  chevron.getStyleClass.add("brand-chevron")

  val brandContent = new HBox(7, brandMark, brandTitle, chevron)
  brandContent.setAlignment(Pos.CENTER_LEFT)
  brandContent.setMouseTransparent(true)

  val brandMenuButton = new Button()
  brandMenuButton.setGraphic(brandContent)
  brandMenuButton.setFocusTraversable(false)
  brandMenuButton.getStyleClass.addAll("brand-menu-btn", "interactive-hand")
  brandMenuButton.setMinWidth(Region.USE_PREF_SIZE)
  brandMenuButton.setTooltip(new Tooltip("Quick Settings, Layout & Workspace Menu"))

  val quickMenu = buildQuickMenu()
  brandMenuButton.setOnAction { _ =>
    if (quickMenu.isShowing) {
      quickMenu.hide()
    } else {
      quickMenu.show(brandMenuButton, Side.BOTTOM, 0, 4)
    }
  }

  // --- Workspace Switcher ---
  private val wsIcon = FontIcon.of(Feather.BRIEFCASE, 12)
  wsIcon.getStyleClass.add("workspace-pill-icon")
  private val wsPrefixLbl = new Label("Workspace:")
  wsPrefixLbl.getStyleClass.add("workspace-pill-prefix")
  private val wsNameLbl = new Label("Default")
  wsNameLbl.getStyleClass.add("workspace-pill-name")
  wsNameLbl.setMinWidth(20)
  wsNameLbl.setMaxWidth(135)
  private val wsChevron = FontIcon.of(Feather.CHEVRON_DOWN, 11)
  wsChevron.getStyleClass.add("brand-chevron")

  private val wsContent = new HBox(5, wsIcon, wsPrefixLbl, wsNameLbl, wsChevron)
  wsContent.setAlignment(Pos.CENTER_LEFT)
  wsContent.setMouseTransparent(true)

  val workspaceMenuButton = new Button()
  workspaceMenuButton.setGraphic(wsContent)
  workspaceMenuButton.setFocusTraversable(false)
  workspaceMenuButton.getStyleClass.addAll("workspace-pill-btn", "interactive-hand")
  workspaceMenuButton.setMinWidth(76)
  workspaceMenuButton.setTooltip(new Tooltip("Active Workspace (switch, create, or manage templates/environments per workspace)"))

  private val workspaceMenu = new ContextMenu()

  private def refreshWorkspaceMenu(): Unit = {
    val projs = appState.projects.asScala.toList
    val activeOpt = Option(appState.activeProject.get).flatten.orElse(projs.headOption)
    val activeId = activeOpt.map(_.id).getOrElse("")
    wsNameLbl.setText(activeOpt.map(_.name).getOrElse("Default"))

    workspaceMenu.getItems.clear()
    val group = new ToggleGroup()
    projs.foreach { p =>
      val item = new RadioMenuItem(p.name)
      item.setToggleGroup(group)
      item.setSelected(p.id == activeId)
      item.setOnAction(_ => actions.selectProject(p.id))
      workspaceMenu.getItems.add(item)
    }

    val newWsItem = new MenuItem("New Workspace…", FontIcon.of(Feather.PLUS, 13))
    newWsItem.setOnAction(_ => actions.createWorkspace())

    val manageWsItem = new MenuItem("Manage Workspaces (Sidebar)…", FontIcon.of(Feather.BRIEFCASE, 13))
    manageWsItem.setOnAction(_ => actions.manageProjects())

    workspaceMenu.getItems.addAll(new SeparatorMenuItem(), newWsItem, manageWsItem)
  }

  refreshWorkspaceMenu()
  appState.projects.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: net.protoform.core.workspace.WorkspaceProject]) =>
    Platform.runLater(() => refreshWorkspaceMenu())
  }
  appState.activeProject.addListener { (_, _, _) =>
    Platform.runLater(() => refreshWorkspaceMenu())
  }
  workspaceMenuButton.setOnAction { _ =>
    if (workspaceMenu.isShowing) {
      workspaceMenu.hide()
    } else {
      refreshWorkspaceMenu()
      workspaceMenu.show(workspaceMenuButton, Side.BOTTOM, 0, 4)
    }
  }

  // --- Environment Switcher ---
  private val NoEnvLabel = "Env: None"
  private val ManageEnvLabel = "Manage Environments…"
  private var updatingEnvCombo = false

  val envCombo = new ComboBox[String]()
  envCombo.getStyleClass.addAll("small", "interactive-hand")
  envCombo.setMinWidth(75)
  envCombo.setPrefWidth(120)
  envCombo.setMaxWidth(165)
  envCombo.setTooltip(new Tooltip("Switch active environment (endpoint, TLS, auth profile & {{var}} variables)"))

  private def refreshEnvironmentCombo(): Unit = {
    updatingEnvCombo = true
    try {
      val envs = appState.environments.asScala.toList
      val items = (NoEnvLabel :: envs.map(_.name)) :+ ManageEnvLabel
      envCombo.getItems.setAll(items: _*)
      val activeName = Option(appState.activeEnvironment.get).flatten.map(_.name).getOrElse(NoEnvLabel)
      envCombo.setValue(if (items.contains(activeName)) activeName else NoEnvLabel)
    } finally {
      updatingEnvCombo = false
    }
  }

  refreshEnvironmentCombo()
  appState.environments.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: net.protoform.core.workspace.EnvironmentConfig]) =>
    Platform.runLater(() => refreshEnvironmentCombo())
  }
  appState.activeEnvironment.addListener { (_, _, _) =>
    Platform.runLater(() => refreshEnvironmentCombo())
  }
  envCombo.valueProperty().addListener { (_, _, chosen) =>
    if (!updatingEnvCombo && chosen != null) {
      if (chosen == ManageEnvLabel) {
        Platform.runLater { () =>
          refreshEnvironmentCombo()
          actions.manageEnvironments()
        }
      } else if (chosen == NoEnvLabel) {
        actions.selectEnvironment(None)
      } else {
        appState.environments.asScala.find(_.name == chosen).foreach(e => actions.selectEnvironment(Some(e.id)))
      }
    }
  }

  // --- Center Draggable Region ---
  val dragSpacer = new Region()
  dragSpacer.setMinWidth(0)
  HBox.setHgrow(dragSpacer, Priority.ALWAYS)

  if (!WindowResizeHelper.isMacOS) {
    WindowResizeHelper.installHeaderDrag(
      header = this,
      stage = stage,
      isMaximized = () => windowController.isMaximized || stage.isMaximized,
      onRestoreForDrag = (sx, sy) => {
        if (stage.isMaximized) stage.setMaximized(false)
        windowController.restoreForDrag(sx, sy)
      },
      onDoubleClick = Some(() => windowController.toggleMaximize())
    )
  }

  // --- Right Section: Quick Switcher, Response Toggle & Window Controls ---
  val quickSearchBtn = new Button("Jump", FontIcon.of(Feather.COMMAND, 12))
  quickSearchBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  quickSearchBtn.setMinWidth(Region.USE_PREF_SIZE)
  quickSearchBtn.setTooltip(new Tooltip("Quick Jump to Method, Template, History, or Suite Runner (Ctrl+K)"))
  quickSearchBtn.setOnAction(_ => actions.openQuickSwitcher())

  val toggleRespBtn = new Button("", FontIcon.of(Feather.COLUMNS, 13))
  toggleRespBtn.getStyleClass.addAll("window-control-btn", "interactive-hand")
  toggleRespBtn.setTooltip(new Tooltip("Toggle Response Pane"))
  toggleRespBtn.setOnAction(_ => actions.toggleResponsePane())

  private def syncRespToggleStyle(visible: Boolean): Unit = {
    toggleRespBtn.getStyleClass.removeAll("window-control-btn-active")
    if (visible) toggleRespBtn.getStyleClass.add("window-control-btn-active")
  }
  syncRespToggleStyle(appState.showResponsePane.get)
  appState.showResponsePane.addListener { (_, _, v) =>
    Platform.runLater(() => syncRespToggleStyle(v))
  }

  val settingsIconBtn = new Button("", FontIcon.of(Feather.SETTINGS, 13))
  settingsIconBtn.getStyleClass.addAll("window-control-btn", "interactive-hand")
  settingsIconBtn.setTooltip(new Tooltip("All Settings (Ctrl+,)"))
  settingsIconBtn.setOnAction(_ => actions.openSettingsDialog())

  // Window Controls
  val minimizeBtn = new Button("", FontIcon.of(Feather.MINUS, 12))
  minimizeBtn.getStyleClass.addAll("window-control-btn", "interactive-hand")
  minimizeBtn.setTooltip(new Tooltip("Minimize"))
  minimizeBtn.setOnAction(_ => stage.setIconified(true))

  val maximizeIcon = FontIcon.of(Feather.SQUARE, 12)
  val maximizeBtn = new Button("", maximizeIcon)
  maximizeBtn.getStyleClass.addAll("window-control-btn", "interactive-hand")
  maximizeBtn.setTooltip(new Tooltip("Maximize / Restore"))
  maximizeBtn.setOnAction { _ =>
    windowController.toggleMaximize()
  }

  windowController.isMaximizedProperty.addListener { (_, _, isMax) =>
    Platform.runLater { () =>
      maximizeIcon.setIconCode(if (isMax) Feather.COPY else Feather.SQUARE)
    }
  }

  val closeBtn = new Button("", FontIcon.of(Feather.X, 13))
  closeBtn.getStyleClass.addAll("window-control-btn", "window-control-btn-close", "interactive-hand")
  closeBtn.setTooltip(new Tooltip("Close"))
  closeBtn.setOnAction(_ => onCloseRequested())

  val windowControlsBox =
    if (WindowResizeHelper.isMacOS) new HBox(2, toggleRespBtn, settingsIconBtn)
    else new HBox(2, toggleRespBtn, settingsIconBtn, minimizeBtn, maximizeBtn, closeBtn)
  windowControlsBox.setAlignment(Pos.CENTER_RIGHT)
  windowControlsBox.setMinWidth(Region.USE_PREF_SIZE)

  getChildren.addAll(
    brandMenuButton,
    workspaceMenuButton,
    targetBar,
    envCombo,
    dragSpacer,
    quickSearchBtn,
    new Separator(javafx.geometry.Orientation.VERTICAL),
    windowControlsBox
  )

  private def buildQuickMenu(): ContextMenu = {
    val menu = new ContextMenu()

    val showDefaultsItem = new CheckMenuItem("Show Default Values")
    showDefaultsItem.selectedProperty().bindBidirectional(appState.showDefaultValues)
    showDefaultsItem.selectedProperty().addListener { (_, _, v) =>
      workspaceService.updateSettings(workspaceService.getSettings.copy(showDefaultValues = v))
    }

    val preserveNamesItem = new CheckMenuItem("Preserve Proto Field Names (snake_case)")
    preserveNamesItem.selectedProperty().bindBidirectional(appState.preserveProtoFieldNames)
    preserveNamesItem.selectedProperty().addListener { (_, _, v) =>
      workspaceService.updateSettings(workspaceService.getSettings.copy(preserveProtoFieldNames = v))
    }

    val autoFormatItem = new CheckMenuItem("Auto-Format Request JSON")
    autoFormatItem.selectedProperty().bindBidirectional(appState.autoFormatJson)
    autoFormatItem.selectedProperty().addListener { (_, _, v) =>
      workspaceService.updateSettings(workspaceService.getSettings.copy(autoFormatJson = v))
    }

    val gzipItem = new CheckMenuItem("Enable gzip Request Compression")
    gzipItem.selectedProperty().bindBidirectional(appState.useGzipCompression)
    gzipItem.selectedProperty().addListener { (_, _, v) =>
      workspaceService.updateSettings(workspaceService.getSettings.copy(useGzipCompression = v))
    }

    val showAdvancedItem = new CheckMenuItem("Show Advanced Toolbar Actions")
    showAdvancedItem.selectedProperty().bindBidirectional(appState.showAdvancedActions)
    showAdvancedItem.selectedProperty().addListener { (_, _, v) =>
      workspaceService.updateSettings(workspaceService.getSettings.copy(showAdvancedActions = v))
    }

    val themeSubMenu = new Menu("Theme", FontIcon.of(Feather.EYE, 13))
    val themeGroup = new ToggleGroup()
    val themeItems = ThemeManager.themes.map { themeName =>
      val item = new RadioMenuItem(themeName)
      item.setToggleGroup(themeGroup)
      item.setSelected(appState.currentTheme.get == themeName)
      item.setOnAction { _ =>
        if (item.isSelected && appState.currentTheme.get != themeName) {
          ThemeManager.applyTheme(themeName)
          appState.currentTheme.set(themeName)
          workspaceService.updateSettings(workspaceService.getSettings.copy(theme = themeName))
        }
      }
      themeName -> item
    }.toMap
    themeSubMenu.getItems.addAll(ThemeManager.themes.map(themeItems): _*)
    appState.currentTheme.addListener { (_, _, newTheme) =>
      if (newTheme != null) {
        Platform.runLater { () =>
          themeItems.get(newTheme).foreach(_.setSelected(true))
        }
      }
    }

    val layoutSubMenu = new Menu("Layout & Modular Panes", FontIcon.of(Feather.LAYOUT, 13))
    val toggleNav = new MenuItem("Toggle Services & Templates Sidebar")
    toggleNav.setOnAction(_ => actions.toggleNavPane())
    val toggleResp = new MenuItem("Toggle Response Pane")
    toggleResp.setOnAction(_ => actions.toggleResponsePane())
    val swapOrientation = new MenuItem("Swap Request / Response Split (Horizontal ↔ Vertical)")
    swapOrientation.setOnAction(_ => actions.toggleSplitOrientation())
    val resetLayout = new MenuItem("Reset Window & Pane Layout")
    resetLayout.setOnAction(_ => actions.resetLayout())
    layoutSubMenu.getItems.addAll(toggleNav, toggleResp, swapOrientation, new SeparatorMenuItem(), resetLayout)

    val loadSchemaItem = new MenuItem("Load Offline Schema (.desc / .binpb)…", FontIcon.of(Feather.FILE_TEXT, 13))
    loadSchemaItem.setOnAction(_ => actions.loadDescriptorSet())

    val loadProtoItem = new MenuItem("Load Raw .proto Files…", FontIcon.of(Feather.CODE, 13))
    loadProtoItem.setOnAction(_ => actions.loadProtoFiles())

    val importGrpcurlItem = new MenuItem("Import grpcurl Command…", FontIcon.of(Feather.TERMINAL, 13))
    importGrpcurlItem.setOnAction(_ => actions.importGrpcurl())

    val manageProjectsItem = new MenuItem("Workspaces… (Ctrl+4)", FontIcon.of(Feather.BRIEFCASE, 13))
    manageProjectsItem.setOnAction(_ => actions.manageProjects())

    val manageEnvItem = new MenuItem("Manage Environments…", FontIcon.of(Feather.GLOBE, 13))
    manageEnvItem.setOnAction(_ => actions.manageEnvironments())

    val quickJumpItem = new MenuItem("Quick Jump (Ctrl+K)…", FontIcon.of(Feather.COMMAND, 13))
    quickJumpItem.setOnAction(_ => actions.openQuickSwitcher())

    val openFolderItem = new MenuItem("Open Workspace Folder (~/.protoform)", FontIcon.of(Feather.FOLDER, 13))
    openFolderItem.setOnAction(_ => actions.openWorkspaceFolder())

    val allSettingsItem = new MenuItem("All Settings… (Ctrl+,)", FontIcon.of(Feather.SLIDERS, 13))
    allSettingsItem.setOnAction(_ => actions.openSettingsDialog())

    menu.getItems.addAll(
      showDefaultsItem,
      preserveNamesItem,
      autoFormatItem,
      gzipItem,
      showAdvancedItem,
      new SeparatorMenuItem(),
      themeSubMenu,
      layoutSubMenu,
      loadSchemaItem,
      loadProtoItem,
      importGrpcurlItem,
      manageProjectsItem,
      manageEnvItem,
      quickJumpItem,
      openFolderItem,
      new SeparatorMenuItem(),
      allSettingsItem
    )
    menu
  }
}

