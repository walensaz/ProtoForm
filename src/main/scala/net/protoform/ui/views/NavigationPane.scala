package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.application.WorkspaceService
import net.protoform.core.schema.{MethodSchema, RpcPattern, ServiceSchema}
import net.protoform.core.workspace.{CallHistoryEntry, ServiceTemplate, WorkspaceProject}
import net.protoform.infrastructure.storage.LocalJsonWorkspaceRepository
import net.protoform.ui.state.{AppState, ConnectionStatus}
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.jdk.CollectionConverters._

/** Collapsible sidebar drawer hosting Services, Templates, Call History, and Workspaces via a StackPane. */
class NavigationPane(
  appState: AppState,
  workspaceService: WorkspaceService,
  workspaceRepo: LocalJsonWorkspaceRepository,
  onRunSuiteRequested: () => Unit = () => (),
  onCollapseRequested: () => Unit = () => (),
  confirmLoadOverwrite: String => Boolean = _ => true,
  onOpenMethodInNewTab: MethodSchema => Unit = _ => (),
  onOpenTemplateInNewTab: ServiceTemplate => Unit = _ => (),
  onOpenHistoryInNewTab: CallHistoryEntry => Unit = _ => (),
  onSwitchWorkspace: String => Unit = _ => (),
  onCreateWorkspace: () => Unit = () => (),
  onWorkspaceMetadataUpdated: () => Unit = () => (),
  onActiveWorkspaceReplaced: WorkspaceProject => Unit = _ => ()
) extends VBox(8) {
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
  setPadding(new Insets(10))
  setMinWidth(190)

  private var currentSection: String = "services"
  private var syncingSelection: Boolean = false

  // Drawer Header
  private val drawerTitleLabel = new Label("SERVICES")
  drawerTitleLabel.getStyleClass.add("drawer-title")

  private val headerSpacer = new Region()
  HBox.setHgrow(headerSpacer, Priority.ALWAYS)

  val runSuiteBtn = new Button("Suite", FontIcon.of(Feather.CHECK_SQUARE, 12))
  runSuiteBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
  runSuiteBtn.setTooltip(new Tooltip("Open QA Batch Suite Runner"))
  runSuiteBtn.setOnAction(_ => onRunSuiteRequested())

  val exportTplBtn = new Button("", FontIcon.of(Feather.UPLOAD, 12))
  exportTplBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  exportTplBtn.setTooltip(new Tooltip("Export QA Templates to JSON"))
  exportTplBtn.setOnAction(_ => exportTemplates())

  val importTplBtn = new Button("", FontIcon.of(Feather.DOWNLOAD, 12))
  importTplBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  importTplBtn.setTooltip(new Tooltip("Import QA Templates from JSON"))
  importTplBtn.setOnAction(_ => importTemplates())

  private val exportTplMenuItem = new MenuItem("Export Templates to JSON…", FontIcon.of(Feather.UPLOAD, 13))
  exportTplMenuItem.setOnAction(_ => exportTemplates())
  private val importTplMenuItem = new MenuItem("Import Templates from JSON…", FontIcon.of(Feather.DOWNLOAD, 13))
  importTplMenuItem.setOnAction(_ => importTemplates())

  val tplMoreBtn = new MenuButton("", FontIcon.of(Feather.MORE_HORIZONTAL, 13))
  tplMoreBtn.getItems.addAll(exportTplMenuItem, importTplMenuItem)
  tplMoreBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "no-caret", "interactive-hand")
  tplMoreBtn.setTooltip(new Tooltip("Template Import & Export"))

  val clearHistoryBtn = new Button("", FontIcon.of(Feather.TRASH_2, 12))
  clearHistoryBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  clearHistoryBtn.setTooltip(new Tooltip("Clear Call History"))
  clearHistoryBtn.setOnAction { _ =>
    if (!appState.callHistory.isEmpty && CustomDialogStage.showConfirm(
      title = "Clear Call History",
      header = "Clear all recorded call history?",
      message = s"This will permanently delete ${appState.callHistory.size()} history entries.",
      confirmLabel = "Clear History",
      destructive = true
    )) {
      workspaceService.clearCallHistory()
      appState.callHistory.clear()
      refreshHistoryList()
      appState.statusText.set("Cleared call history.")
    }
  }

  private val newWsBtn = new Button("New", FontIcon.of(Feather.PLUS, 12))
  newWsBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
  newWsBtn.setTooltip(new Tooltip("Create a New Workspace"))
  newWsBtn.setOnAction(_ => onCreateWorkspace())

  private val importWsBtn = new Button("", FontIcon.of(Feather.DOWNLOAD, 12))
  importWsBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  importWsBtn.setTooltip(new Tooltip("Import Workspace Bundle (.json)"))
  importWsBtn.setOnAction(_ => importWorkspaceBundle())

  private val collapseBtn = new Button("", FontIcon.of(Feather.CHEVRON_LEFT, 13))
  collapseBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  collapseBtn.setTooltip(new Tooltip("Collapse Sidebar (Ctrl+B)"))
  collapseBtn.setOnAction(_ => onCollapseRequested())

  private val drawerHeader = new HBox(6, drawerTitleLabel, headerSpacer, runSuiteBtn, exportTplBtn, importTplBtn, tplMoreBtn, clearHistoryBtn, newWsBtn, importWsBtn, collapseBtn)
  drawerHeader.setAlignment(Pos.CENTER_LEFT)
  drawerHeader.getStyleClass.add("drawer-header")

  // --- Section 1: Services ---
  val serviceFilterField = new TextField()
  serviceFilterField.setPromptText("Filter services or methods...")
  serviceFilterField.getStyleClass.add("small")
  serviceFilterField.textProperty().addListener((_, _, _) => rebuildFilteredTree())

  sealed trait NavTreeNode
  case object RootNode extends NavTreeNode
  case class ServiceNode(service: ServiceSchema) extends NavTreeNode
  case class MethodNode(method: MethodSchema) extends NavTreeNode

  val treeRoot = new TreeItem[NavTreeNode](RootNode)
  treeRoot.setExpanded(true)
  val treeView = new TreeView[NavTreeNode](treeRoot)
  treeView.setShowRoot(false)
  setupServiceTreeCellFactory()

  val emptyIcon = FontIcon.of(Feather.WIFI_OFF, 22)
  emptyIcon.getStyleClass.add("empty-state-icon")
  val emptyTitle = new Label("Not Connected")
  emptyTitle.getStyleClass.add("empty-state-title")
  val emptyDesc = new Label("Enter an endpoint in the toolbar above and click Introspect, or load a .proto / .desc file.")
  emptyDesc.getStyleClass.add("form-subtext")
  emptyDesc.setWrapText(true)
  emptyDesc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER)
  val emptyStateBox = new VBox(8, emptyIcon, emptyTitle, emptyDesc)
  emptyStateBox.setAlignment(Pos.CENTER)
  emptyStateBox.setPadding(new Insets(20))

  val servicesStack = new StackPane(emptyStateBox, treeView)
  VBox.setVgrow(servicesStack, Priority.ALWAYS)
  treeView.setVisible(false)

  val servicesBox = new VBox(6, serviceFilterField, servicesStack)

  // --- Section 2: QA Templates (Context-Smart Folder Tree) ---
  sealed trait TemplateTreeNode
  case object TplRootNode extends TemplateTreeNode
  case class FolderNode(name: String, count: Int) extends TemplateTreeNode
  case class TemplateNode(template: ServiceTemplate) extends TemplateTreeNode

  val templateSearchField = new TextField()
  templateSearchField.setPromptText("Filter by name, folder, tag...")
  templateSearchField.getStyleClass.add("small")

  private val scopeGroup = new ToggleGroup()
  private val scopeMethodBtn = new ToggleButton("Method")
  private val scopeServiceBtn = new ToggleButton("Service")
  private val scopeAllBtn = new ToggleButton("All")
  List(scopeMethodBtn, scopeServiceBtn, scopeAllBtn).foreach { btn =>
    btn.setToggleGroup(scopeGroup)
    btn.getStyleClass.addAll("scope-toggle-btn", "interactive-hand")
    btn.setMaxWidth(Double.MaxValue)
    HBox.setHgrow(btn, Priority.ALWAYS)
  }
  scopeMethodBtn.setSelected(true)
  scopeMethodBtn.setTooltip(new Tooltip("Show templates matching the selected RPC method"))
  scopeServiceBtn.setTooltip(new Tooltip("Show templates matching the selected service"))
  scopeAllBtn.setTooltip(new Tooltip("Show all templates in the active project"))

  private val scopeBar = new HBox(4, scopeMethodBtn, scopeServiceBtn, scopeAllBtn)
  scopeBar.setAlignment(Pos.CENTER_LEFT)

  val tplTreeRoot = new TreeItem[TemplateTreeNode](TplRootNode)
  tplTreeRoot.setExpanded(true)
  val templatesTree = new TreeView[TemplateTreeNode](tplTreeRoot)
  templatesTree.setShowRoot(false)
  VBox.setVgrow(templatesTree, Priority.ALWAYS)
  setupTemplatesTreeView()

  val templatesBox = new VBox(6, templateSearchField, scopeBar, templatesTree)

  // --- Section 3: Recent Call History ---
  val historySearchField = new TextField()
  historySearchField.setPromptText("Filter recent calls...")
  historySearchField.getStyleClass.add("small")

  val historyList = new ListView[CallHistoryEntry]()
  historyList.setPlaceholder(new Label("No recent RPC calls recorded yet."))
  VBox.setVgrow(historyList, Priority.ALWAYS)
  setupHistoryListView()

  val historyBox = new VBox(6, historySearchField, historyList)

  // --- Section 4: Workspaces ---
  val workspaceSearchField = new TextField()
  workspaceSearchField.setPromptText("Filter workspaces...")
  workspaceSearchField.getStyleClass.add("small")

  val workspacesList = new ListView[WorkspaceProject]()
  workspacesList.setPlaceholder(new Label("No matching workspaces."))
  VBox.setVgrow(workspacesList, Priority.ALWAYS)

  private val wsSwitchBtn = new Button("Switch", FontIcon.of(Feather.CHECK, 12))
  wsSwitchBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
  wsSwitchBtn.setTooltip(new Tooltip("Switch to Selected Workspace"))

  private val wsRenameBtn = new Button("", FontIcon.of(Feather.EDIT_2, 12))
  wsRenameBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  wsRenameBtn.setTooltip(new Tooltip("Rename Selected Workspace"))

  private val wsExportBtn = new Button("", FontIcon.of(Feather.UPLOAD, 12))
  wsExportBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  wsExportBtn.setTooltip(new Tooltip("Export Selected Workspace Bundle (.json)"))

  private val wsDeleteBtn = new Button("", FontIcon.of(Feather.TRASH_2, 12))
  wsDeleteBtn.getStyleClass.addAll("small", "danger", "icon-only-btn", "interactive-hand")
  wsDeleteBtn.setTooltip(new Tooltip("Delete Selected Workspace"))

  private val wsActionSpacer = new Region()
  HBox.setHgrow(wsActionSpacer, Priority.ALWAYS)
  private val wsActionsBar = new HBox(6, wsSwitchBtn, wsActionSpacer, wsRenameBtn, wsExportBtn, wsDeleteBtn)
  wsActionsBar.setAlignment(Pos.CENTER_LEFT)

  setupWorkspacesListView()
  val workspacesBox = new VBox(6, workspaceSearchField, workspacesList, wsActionsBar)

  private val drawerStack = new StackPane(servicesBox, templatesBox, historyBox, workspacesBox)
  VBox.setVgrow(drawerStack, Priority.ALWAYS)

  getChildren.addAll(drawerHeader, drawerStack)

  private val methodItemMap = collection.mutable.Map[TreeItem[NavTreeNode], MethodSchema]()

  def showSection(section: String): Unit = {
    currentSection = section match {
      case "templates"  => "templates"
      case "history"    => "history"
      case "workspaces" => "workspaces"
      case _            => "services"
    }
    drawerTitleLabel.setText(currentSection.toUpperCase)
    servicesBox.setVisible(currentSection == "services")
    servicesBox.setManaged(currentSection == "services")
    templatesBox.setVisible(currentSection == "templates")
    templatesBox.setManaged(currentSection == "templates")
    historyBox.setVisible(currentSection == "history")
    historyBox.setManaged(currentSection == "history")
    workspacesBox.setVisible(currentSection == "workspaces")
    workspacesBox.setManaged(currentSection == "workspaces")
    if (currentSection == "workspaces") {
      refreshWorkspacesList(Option(workspaceSearchField.getText).getOrElse(""))
    }
    refreshHeaderControls()
  }

  private def refreshHeaderControls(): Unit = {
    val isTpl = currentSection == "templates"
    val isHist = currentSection == "history"
    val isWs = currentSection == "workspaces"
    val adv = appState.showAdvancedActions.get
    runSuiteBtn.setVisible(isTpl); runSuiteBtn.setManaged(isTpl)
    exportTplBtn.setVisible(isTpl && adv); exportTplBtn.setManaged(isTpl && adv)
    importTplBtn.setVisible(isTpl && adv); importTplBtn.setManaged(isTpl && adv)
    tplMoreBtn.setVisible(isTpl && !adv); tplMoreBtn.setManaged(isTpl && !adv)
    clearHistoryBtn.setVisible(isHist); clearHistoryBtn.setManaged(isHist)
    newWsBtn.setVisible(isWs); newWsBtn.setManaged(isWs)
    importWsBtn.setVisible(isWs); importWsBtn.setManaged(isWs)
  }

  showSection("services")
  appState.showAdvancedActions.addListener { (_, _, _) =>
    Platform.runLater(() => refreshHeaderControls())
  }

  treeView.getSelectionModel.selectedItemProperty().addListener { (_, _, selectedItem) =>
    if (!syncingSelection && selectedItem != null) {
      methodItemMap.get(selectedItem).foreach { method =>
        appState.selectedMethod.set(Some(method))
        val s = workspaceService.getSettings
        workspaceService.updateSettings(s.copy(lastSelectedMethod = Some(method.fullMethodName)))
        appState.statusText.set(s"Selected: ${method.fullMethodName}")
      }
    }
  }

  appState.discoveredServices.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: ServiceSchema]) =>
    Platform.runLater { () =>
      rebuildFilteredTree()
      val hasServices = !appState.discoveredServices.isEmpty
      treeView.setVisible(hasServices)
      emptyStateBox.setVisible(!hasServices)
      syncSelectedTreeItem(appState.selectedMethod.get)
    }
  }

  appState.selectedMethod.addListener { (_, _, methodOpt) =>
    Platform.runLater { () =>
      syncSelectedTreeItem(methodOpt)
      refreshTemplatesList(Option(templateSearchField.getText).getOrElse(""))
    }
  }

  appState.templates.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: ServiceTemplate]) =>
    Platform.runLater(() => refreshTemplatesList(Option(templateSearchField.getText).getOrElse("")))
  }
  templateSearchField.textProperty().addListener { (_, _, filterText) =>
    refreshTemplatesList(Option(filterText).getOrElse(""))
  }
  scopeGroup.selectedToggleProperty().addListener { (_, _, _) =>
    refreshTemplatesList(Option(templateSearchField.getText).getOrElse(""))
  }

  appState.callHistory.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: CallHistoryEntry]) =>
    refreshHistoryList(Option(historySearchField.getText).getOrElse(""))
  }
  historySearchField.textProperty().addListener { (_, _, filterText) =>
    refreshHistoryList(Option(filterText).getOrElse(""))
  }

  appState.projects.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: WorkspaceProject]) =>
    Platform.runLater(() => refreshWorkspacesList(Option(workspaceSearchField.getText).getOrElse("")))
  }
  appState.activeProject.addListener { (_, _, _) =>
    Platform.runLater(() => refreshWorkspacesList(Option(workspaceSearchField.getText).getOrElse("")))
  }
  workspaceSearchField.textProperty().addListener { (_, _, filterText) =>
    refreshWorkspacesList(Option(filterText).getOrElse(""))
  }

  appState.connectionStatus.addListener { (_, _, newStatus) =>
    Platform.runLater { () =>
      val hasServices = !appState.discoveredServices.isEmpty
      newStatus match {
        case ConnectionStatus.Disconnected if !hasServices =>
          treeView.setVisible(false)
          emptyIcon.setIconCode(Feather.WIFI_OFF)
          emptyTitle.setText("Not Connected")
          emptyDesc.setText("Enter an endpoint in the toolbar above and click Introspect, or load a .proto / .desc file.")
          emptyStateBox.setVisible(true)
        case ConnectionStatus.Connected(_, svcCount) if svcCount == 0 =>
          treeView.setVisible(false)
          emptyIcon.setIconCode(Feather.ALERT_TRIANGLE)
          emptyTitle.setText("No Services Found")
          emptyDesc.setText("Server reflection returned 0 services. Try loading a .desc or .proto file.")
          emptyStateBox.setVisible(true)
        case ConnectionStatus.Connected(_, _) =>
          treeView.setVisible(true)
          emptyStateBox.setVisible(false)
        case ConnectionStatus.Failed(endpoint, reason) if !hasServices =>
          treeView.setVisible(false)
          emptyIcon.setIconCode(Feather.ALERT_CIRCLE)
          emptyTitle.setText("Connection Failed")
          emptyDesc.setText(s"Cannot reach $endpoint:\n$reason")
          emptyStateBox.setVisible(true)
        case _ => ()
      }
    }
  }

  private def syncSelectedTreeItem(methodOpt: Option[MethodSchema]): Unit = {
    syncingSelection = true
    try {
      methodOpt.flatMap(m => methodItemMap.find(_._2.fullMethodName == m.fullMethodName).map(_._1)).foreach { item =>
        treeView.getSelectionModel.select(item)
      }
    } finally {
      syncingSelection = false
    }
  }

  private def rebuildFilteredTree(): Unit = {
    val q = Option(serviceFilterField.getText).map(_.trim.toLowerCase).getOrElse("")
    treeRoot.getChildren.clear()
    methodItemMap.clear()

    appState.discoveredServices.asScala.foreach { svc =>
      val svcMatches = svc.fullName.toLowerCase.contains(q)
      val matchingMethods = if (q.isEmpty || svcMatches) svc.methods else svc.methods.filter(_.name.toLowerCase.contains(q))
      if (matchingMethods.nonEmpty) {
        val svcItem = new TreeItem[NavTreeNode](ServiceNode(svc))
        svcItem.setExpanded(true)
        matchingMethods.foreach { method =>
          val methodItem = new TreeItem[NavTreeNode](MethodNode(method))
          methodItemMap.put(methodItem, method)
          svcItem.getChildren.add(methodItem)
        }
        treeRoot.getChildren.add(svcItem)
      }
    }
  }

  def selectMethodByName(fullMethodName: String): Option[MethodSchema] = {
    val found = appState.discoveredServices.asScala.iterator
      .flatMap(_.methods)
      .find(_.fullMethodName == fullMethodName)
    found.foreach { method =>
      appState.selectedMethod.set(Some(method))
      val s = workspaceService.getSettings
      workspaceService.updateSettings(s.copy(lastSelectedMethod = Some(method.fullMethodName)))
    }
    found
  }

  private def setupServiceTreeCellFactory(): Unit = {
    treeView.setCellFactory { _ =>
      new TreeCell[NavTreeNode]() {
        private val svcIcon = FontIcon.of(Feather.LAYERS, 13)
        private val svcName = new Label()
        svcName.getStyleClass.add("tree-service-name")
        private val svcPkg = new Label()
        svcPkg.getStyleClass.add("tree-service-pkg")
        private val svcRow = new HBox(7, svcIcon, svcName, svcPkg)
        svcRow.setAlignment(Pos.CENTER_LEFT)

        private val methodName = new Label()
        methodName.getStyleClass.add("tree-method-name")
        methodName.setMinWidth(0)
        private val methodSpacer = new Region()
        HBox.setHgrow(methodSpacer, Priority.ALWAYS)
        private val methodPill = new Label()
        methodPill.getStyleClass.add("rpc-pill")
        methodPill.setMinWidth(Region.USE_PREF_SIZE)
        private val methodRow = new HBox(6, methodName, methodSpacer, methodPill)
        methodRow.setAlignment(Pos.CENTER_LEFT)

        private val openTabItem = new MenuItem("Open in New Tab", FontIcon.of(Feather.PLUS_SQUARE, 12))
        openTabItem.setOnAction { _ =>
          getItem match {
            case MethodNode(m) => onOpenMethodInNewTab(m)
            case _             => ()
          }
        }
        private val methodMenu = new ContextMenu(openTabItem)

        setOnMousePressed { ev =>
          if ((ev.isControlDown || ev.isMetaDown) && !isEmpty) {
            getItem match {
              case MethodNode(m) =>
                ev.consume()
                onOpenMethodInNewTab(m)
              case _ => ()
            }
          }
        }

        private val pillClasses = List("rpc-pill-unary", "rpc-pill-server", "rpc-pill-client", "rpc-pill-bidi")

        override def updateItem(item: NavTreeNode, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          setText(null)
          if (empty || item == null) {
            setGraphic(null)
            setContextMenu(null)
          } else {
            item match {
              case ServiceNode(svc) =>
                svcName.setText(svc.simpleName)
                val hasPkg = svc.packageName.nonEmpty
                svcPkg.setText(svc.packageName)
                svcPkg.setVisible(hasPkg)
                svcPkg.setManaged(hasPkg)
                setGraphic(svcRow)
                setContextMenu(null)

              case MethodNode(method) =>
                methodName.setText(method.name)
                methodPill.getStyleClass.removeAll(pillClasses: _*)
                val (pillText, pillClass) = method.pattern match {
                  case RpcPattern.Unary                  => ("UNARY", "rpc-pill-unary")
                  case RpcPattern.ServerStreaming        => ("SERVER", "rpc-pill-server")
                  case RpcPattern.ClientStreaming        => ("CLIENT", "rpc-pill-client")
                  case RpcPattern.BidirectionalStreaming => ("BIDI", "rpc-pill-bidi")
                }
                methodPill.setText(pillText)
                methodPill.getStyleClass.add(pillClass)
                setGraphic(methodRow)
                setContextMenu(methodMenu)

              case RootNode =>
                setGraphic(null)
                setContextMenu(null)
            }
          }
        }
      }
    }
  }

  private def setupTemplatesTreeView(): Unit = {
    refreshTemplatesList()

    templatesTree.setCellFactory { _ =>
      new TreeCell[TemplateTreeNode]() {
        private var currentItem: Option[ServiceTemplate] = None

        private val folderIcon = FontIcon.of(Feather.FOLDER, 13)
        private val folderNameLbl = new Label() { getStyleClass.add("tree-service-name") }
        private val folderCountLbl = new Label() { getStyleClass.add("field-meta-mono") }
        private val folderSpacer = new Region()
        HBox.setHgrow(folderSpacer, Priority.ALWAYS)
        private val folderRow = new HBox(7, folderIcon, folderNameLbl, folderSpacer, folderCountLbl)
        folderRow.setAlignment(Pos.CENTER_LEFT)

        private val nameLabel = new Label()
        nameLabel.getStyleClass.add("form-label")
        private val expectedBadge = new Label()
        expectedBadge.getStyleClass.add("badge-field-num")

        private val titleRow = new HBox(6, nameLabel, expectedBadge)
        titleRow.setAlignment(Pos.CENTER_LEFT)

        private val methodLabel = new Label()
        methodLabel.getStyleClass.add("form-subtext")

        private val descLabel = new Label()
        descLabel.getStyleClass.add("form-subtext")
        descLabel.setWrapText(true)

        private val tagsRow = new HBox(4)
        tagsRow.setAlignment(Pos.CENTER_LEFT)

        private val infoCol = new VBox(3, titleRow, methodLabel, descLabel, tagsRow)
        HBox.setHgrow(infoCol, Priority.ALWAYS)

        private val loadBtn = new Button("Load", FontIcon.of(Feather.CORNER_DOWN_RIGHT, 11))
        loadBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
        loadBtn.setOnAction(_ => currentItem.foreach(tpl => loadTemplate(tpl, autoRun = false)))

        private val runBtn = new Button("", FontIcon.of(Feather.PLAY, 11))
        runBtn.getStyleClass.addAll("small", "success", "icon-only-btn", "interactive-hand")
        runBtn.setTooltip(new Tooltip("Load & Run Template"))
        runBtn.setOnAction(_ => currentItem.foreach(tpl => loadTemplate(tpl, autoRun = true)))

        private val dupBtn = new Button("", FontIcon.of(Feather.COPY, 11))
        dupBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
        dupBtn.setTooltip(new Tooltip("Duplicate QA Template"))
        dupBtn.setOnAction(_ => currentItem.foreach(duplicateTemplate))

        private val deleteBtn = new Button("", FontIcon.of(Feather.TRASH_2, 11))
        deleteBtn.getStyleClass.addAll("small", "danger", "icon-only-btn", "interactive-hand")
        deleteBtn.setTooltip(new Tooltip("Delete Template"))
        deleteBtn.setOnAction { _ =>
          currentItem.foreach { item =>
            if (CustomDialogStage.showConfirm(
              title = "Delete Template",
              header = s"Delete '${item.name}'?",
              message = "This QA template will be permanently removed from your workspace.",
              confirmLabel = "Delete",
              destructive = true
            )) {
              workspaceService.deleteTemplate(item.id)
              appState.templates.remove(item)
              if (appState.activeTemplate.get.exists(_.id == item.id)) {
                appState.activeTemplate.set(None)
              }
              appState.statusText.set(s"Deleted template '${item.name}'.")
            }
          }
        }

        private val btnGrid = new HBox(4, loadBtn, runBtn, dupBtn, deleteBtn)
        btnGrid.setAlignment(Pos.CENTER_RIGHT)

        private val card = new VBox(6, infoCol, btnGrid)
        card.getStyleClass.add("template-card")

        private val openTplTabItem = new MenuItem("Open in New Tab", FontIcon.of(Feather.PLUS_SQUARE, 12))
        openTplTabItem.setOnAction(_ => currentItem.foreach(onOpenTemplateInNewTab))
        private val tplMenu = new ContextMenu(openTplTabItem)

        setOnMousePressed { ev =>
          if ((ev.isControlDown || ev.isMetaDown) && !isEmpty) {
            currentItem.foreach { tpl =>
              ev.consume()
              onOpenTemplateInNewTab(tpl)
            }
          }
        }

        override def updateItem(item: TemplateTreeNode, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          setText(null)
          if (empty || item == null) {
            currentItem = None
            setGraphic(null)
            setContextMenu(null)
          } else {
            item match {
              case FolderNode(name, count) =>
                currentItem = None
                folderNameLbl.setText(name)
                folderCountLbl.setText(s"$count")
                setGraphic(folderRow)
                setContextMenu(null)

              case TemplateNode(tpl) =>
                currentItem = Some(tpl)
                nameLabel.setText(tpl.name)
                methodLabel.setText(tpl.fullMethodName)

                val hasExpected = tpl.expectedStatus.exists(_.nonEmpty)
                expectedBadge.setText(tpl.expectedStatus.map(s => s"exp: $s").getOrElse(""))
                expectedBadge.setVisible(hasExpected)
                expectedBadge.setManaged(hasExpected)

                val hasDesc = tpl.description.nonEmpty
                descLabel.setText(tpl.description)
                descLabel.setVisible(hasDesc)
                descLabel.setManaged(hasDesc)

                tagsRow.getChildren.clear()
                tpl.tags.foreach { tag =>
                  val pill = new Label(tag)
                  pill.getStyleClass.add("tag-pill")
                  tagsRow.getChildren.add(pill)
                }
                tagsRow.setVisible(tpl.tags.nonEmpty)
                tagsRow.setManaged(tpl.tags.nonEmpty)

                setGraphic(card)
                setContextMenu(tplMenu)

              case TplRootNode =>
                currentItem = None
                setGraphic(null)
                setContextMenu(null)
            }
          }
        }
      }
    }
  }

  private def setupHistoryListView(): Unit = {
    refreshHistoryList()

    historyList.setCellFactory { _ =>
      new ListCell[CallHistoryEntry]() {
        private var currentEntry: Option[CallHistoryEntry] = None

        private val methodLbl = new Label()
        methodLbl.getStyleClass.add("form-label")
        private val statusLbl = new Label()
        statusLbl.getStyleClass.add("rpc-pill")
        private val topSpacer = new Region()
        HBox.setHgrow(topSpacer, Priority.ALWAYS)
        private val topRow = new HBox(6, methodLbl, topSpacer, statusLbl)
        topRow.setAlignment(Pos.CENTER_LEFT)

        private val metaLbl = new Label()
        metaLbl.getStyleClass.add("info-strip")

        private val diffBtn = new Button("", FontIcon.of(Feather.COLUMNS, 11))
        diffBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
        diffBtn.setTooltip(new Tooltip("Compare this response in Diff Viewer"))
        diffBtn.setOnAction(_ => currentEntry.foreach(e => ResponseDiffDialog.show(appState, Some(e))))

        private val loadBtn = new Button("Load", FontIcon.of(Feather.CORNER_DOWN_RIGHT, 11))
        loadBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
        loadBtn.setOnAction(_ => currentEntry.foreach(loadHistoryEntry))

        private val bottomSpacer = new Region()
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS)
        private val bottomRow = new HBox(6, metaLbl, bottomSpacer, diffBtn, loadBtn)
        bottomRow.setAlignment(Pos.CENTER_LEFT)

        private val card = new VBox(5, topRow, bottomRow)
        card.getStyleClass.add("template-card")

        private val openHistTabItem = new MenuItem("Open in New Tab", FontIcon.of(Feather.PLUS_SQUARE, 12))
        openHistTabItem.setOnAction(_ => currentEntry.foreach(onOpenHistoryInNewTab))
        private val histMenu = new ContextMenu(openHistTabItem)

        setOnMousePressed { ev =>
          if ((ev.isControlDown || ev.isMetaDown) && !isEmpty) {
            currentEntry.foreach { entry =>
              ev.consume()
              onOpenHistoryInNewTab(entry)
            }
          }
        }

        override def updateItem(item: CallHistoryEntry, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) {
            currentEntry = None
            setText(null)
            setGraphic(null)
            setContextMenu(null)
          } else {
            currentEntry = Some(item)
            methodLbl.setText(item.methodName)
            statusLbl.setText(if (item.statusCode == 0) "OK" else s"ERR ${item.statusCode}")
            statusLbl.getStyleClass.removeAll("rpc-pill-unary", "rpc-pill-client")
            statusLbl.getStyleClass.add(if (item.statusCode == 0) "rpc-pill-unary" else "rpc-pill-client")
            val timeStr = timeFormatter.format(Instant.ofEpochMilli(item.executedAt))
            metaLbl.setText(s"${item.endpoint} · ${item.latencyMs}ms · $timeStr")
            setGraphic(card)
            setContextMenu(histMenu)
          }
        }
      }
    }
  }

  def loadTemplate(tpl: ServiceTemplate, autoRun: Boolean = false): Unit = {
    if (!confirmLoadOverwrite(s"Loading template '${tpl.name}'")) return
    appState.runTemplateAfterLoad.set(autoRun)
    appState.activeTemplate.set(Some(tpl))
    appState.templateToLoad.set(None)
    appState.templateToLoad.set(Some(tpl))
    selectMethodByName(tpl.fullMethodName)
    appState.statusText.set(s"Loaded template '${tpl.name}' for ${tpl.fullMethodName}")
  }

  def loadHistoryEntry(entry: CallHistoryEntry): Unit = {
    if (!confirmLoadOverwrite(s"Loading history for ${entry.methodName}")) return
    appState.historyEntryToLoad.set(None)
    appState.historyEntryToLoad.set(Some(entry))
    selectMethodByName(entry.fullMethodName)
    appState.statusText.set(s"Loaded call history for ${entry.fullMethodName}")
  }

  private def duplicateTemplate(tpl: ServiceTemplate): Unit = {
    val now = System.currentTimeMillis()
    val copy = tpl.copy(
      id = UUID.randomUUID().toString,
      name = s"${tpl.name} (Copy)",
      createdAt = now,
      updatedAt = now
    )
    workspaceService.saveTemplate(copy)
    appState.templates.add(0, copy)
    appState.statusText.set(s"Duplicated template '${tpl.name}'.")
  }

  private def exportTemplates(): Unit = {
    val templates = workspaceService.getAllTemplates
    if (templates.isEmpty) {
      appState.statusText.set("No templates to export.")
      return
    }
    val chooser = new FileChooser()
    chooser.setTitle("Export QA Templates")
    chooser.setInitialFileName("protoform-templates.json")
    Option(chooser.showSaveDialog(getScene.getWindow)).foreach { file =>
      val json = workspaceRepo.serializeTemplates(templates)
      Files.write(file.toPath, json.getBytes(StandardCharsets.UTF_8))
      appState.statusText.set(s"Exported ${templates.size} templates to ${file.getName}")
    }
  }

  private def importTemplates(): Unit = {
    val chooser = new FileChooser()
    chooser.setTitle("Import QA Templates")
    Option(chooser.showOpenDialog(getScene.getWindow)).foreach { file =>
      val content = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
      workspaceRepo.deserializeTemplates(content) match {
        case Right(incoming) =>
          val added = workspaceService.importTemplates(incoming)
          appState.templates.setAll(workspaceService.getAllTemplates.asJava)
          appState.statusText.set(s"Imported $added new template(s) from ${file.getName}")
        case Left(err) =>
          CustomDialogStage.showErrorAlert("Import Failed", err.message)
      }
    }
  }

  private def refreshTemplatesList(filter: String = ""): Unit = {
    val all = workspaceService.getAllTemplates
    val selectedMethodOpt = Option(appState.selectedMethod.get).flatten
    val scoped =
      if (scopeMethodBtn.isSelected && selectedMethodOpt.isDefined) {
        val mFull = selectedMethodOpt.get.fullMethodName
        all.filter(_.fullMethodName == mFull)
      } else if (scopeServiceBtn.isSelected && selectedMethodOpt.isDefined) {
        val svcFull = selectedMethodOpt.get.serviceFullName
        all.filter(_.serviceFullName == svcFull)
      } else {
        all
      }

    val filtered = if (filter.trim.isEmpty) scoped else {
      val lower = filter.trim.toLowerCase
      scoped.filter { t =>
        t.name.toLowerCase.contains(lower) ||
        t.fullMethodName.toLowerCase.contains(lower) ||
        t.folderPath.toLowerCase.contains(lower) ||
        t.description.toLowerCase.contains(lower) ||
        t.tags.exists(_.toLowerCase.contains(lower))
      }
    }

    tplTreeRoot.getChildren.clear()
    val folderItems = scala.collection.mutable.Map[String, TreeItem[TemplateTreeNode]]()

    def ensureFolderChain(segments: List[String]): TreeItem[TemplateTreeNode] = {
      var parent = tplTreeRoot
      var prefix = ""
      segments.foreach { seg =>
        prefix = if (prefix.isEmpty) seg else s"$prefix/$seg"
        val node = folderItems.getOrElseUpdate(prefix, {
          val item = new TreeItem[TemplateTreeNode](FolderNode(seg, 0))
          item.setExpanded(true)
          parent.getChildren.add(item)
          item
        })
        parent = node
      }
      parent
    }

    filtered.foreach { tpl =>
      val segs = tpl.folderPath.split("/").map(_.trim).filter(_.nonEmpty).toList
      val targetParent = if (segs.isEmpty) tplTreeRoot else ensureFolderChain(segs)
      targetParent.getChildren.add(new TreeItem[TemplateTreeNode](TemplateNode(tpl)))
    }

    def updateFolderCounts(item: TreeItem[TemplateTreeNode]): Int = {
      item.getValue match {
        case TemplateNode(_) => 1
        case FolderNode(name, _) =>
          val count = item.getChildren.asScala.map(updateFolderCounts).sum
          item.setValue(FolderNode(name, count))
          count
        case TplRootNode =>
          item.getChildren.asScala.map(updateFolderCounts).sum
      }
    }
    updateFolderCounts(tplTreeRoot)
  }

  private def refreshHistoryList(filter: String = ""): Unit = {
    val all = workspaceService.getCallHistory
    val filtered = if (filter.trim.isEmpty) all else {
      val lower = filter.trim.toLowerCase
      all.filter { h =>
        h.fullMethodName.toLowerCase.contains(lower) ||
        h.endpoint.toLowerCase.contains(lower) ||
        h.statusDescription.toLowerCase.contains(lower)
      }
    }
    historyList.getItems.setAll(filtered.asJava)
  }

  private def setupWorkspacesListView(): Unit = {
    workspacesList.setCellFactory { _ =>
      new ListCell[WorkspaceProject]() {
        private var currentWs: Option[WorkspaceProject] = None
        private val nameLbl = new Label()
        nameLbl.getStyleClass.add("form-label")
        private val activeBadge = new Label("ACTIVE")
        activeBadge.getStyleClass.add("tag-pill")
        activeBadge.setMinWidth(Region.USE_PREF_SIZE)
        private val topSpacer = new Region()
        HBox.setHgrow(topSpacer, Priority.ALWAYS)
        private val topRow = new HBox(6, nameLbl, topSpacer, activeBadge)
        topRow.setAlignment(Pos.CENTER_LEFT)

        private val metaLbl = new Label()
        metaLbl.getStyleClass.add("form-subtext")

        private val card = new VBox(3, topRow, metaLbl)
        card.getStyleClass.add("template-card")

        private val switchItem = new MenuItem("Switch to Workspace", FontIcon.of(Feather.CHECK, 13))
        switchItem.setOnAction(_ => currentWs.foreach(ws => onSwitchWorkspace(ws.id)))
        private val renameItem = new MenuItem("Rename…", FontIcon.of(Feather.EDIT_2, 13))
        renameItem.setOnAction(_ => currentWs.foreach(renameWorkspace))
        private val exportItem = new MenuItem("Export Bundle…", FontIcon.of(Feather.UPLOAD, 13))
        exportItem.setOnAction(_ => currentWs.foreach(exportWorkspaceBundle))
        private val deleteItem = new MenuItem("Delete…", FontIcon.of(Feather.TRASH_2, 13))
        deleteItem.setOnAction(_ => currentWs.foreach(deleteWorkspace))
        private val wsMenu = new ContextMenu(switchItem, renameItem, exportItem, new SeparatorMenuItem(), deleteItem)

        setOnMouseClicked { ev =>
          if (ev.getClickCount == 2) {
            currentWs.foreach(ws => onSwitchWorkspace(ws.id))
          }
        }

        override def updateItem(item: WorkspaceProject, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) {
            currentWs = None
            setText(null)
            setGraphic(null)
            setContextMenu(null)
          } else {
            currentWs = Some(item)
            val isActive = item.id == workspaceService.getActiveProject.id
            nameLbl.setText(item.name)
            activeBadge.setVisible(isActive)
            activeBadge.setManaged(isActive)
            val tabCount = Math.max(1, item.workbenchTabs.size)
            metaLbl.setText(s"${item.templates.size} tpl · ${item.environments.size} env · ${item.authProfiles.size} auth · $tabCount tab(s)")
            switchItem.setDisable(isActive)
            deleteItem.setDisable(workspaceService.getProjects.size <= 1)
            setText(null)
            setGraphic(card)
            setContextMenu(wsMenu)
          }
        }
      }
    }

    workspacesList.getSelectionModel.selectedItemProperty().addListener { (_, _, _) =>
      updateWorkspaceActionStates()
    }
    wsSwitchBtn.setOnAction { _ =>
      Option(workspacesList.getSelectionModel.getSelectedItem).foreach(ws => onSwitchWorkspace(ws.id))
    }
    wsRenameBtn.setOnAction { _ =>
      Option(workspacesList.getSelectionModel.getSelectedItem).foreach(renameWorkspace)
    }
    wsExportBtn.setOnAction { _ =>
      Option(workspacesList.getSelectionModel.getSelectedItem).foreach(exportWorkspaceBundle)
    }
    wsDeleteBtn.setOnAction { _ =>
      Option(workspacesList.getSelectionModel.getSelectedItem).foreach(deleteWorkspace)
    }
  }

  private def updateWorkspaceActionStates(): Unit = {
    val selOpt = Option(workspacesList.getSelectionModel.getSelectedItem)
    val activeId = workspaceService.getActiveProject.id
    val totalCount = workspaceService.getProjects.size
    wsSwitchBtn.setDisable(selOpt.forall(_.id == activeId))
    wsRenameBtn.setDisable(selOpt.isEmpty)
    wsExportBtn.setDisable(selOpt.isEmpty)
    wsDeleteBtn.setDisable(selOpt.isEmpty || totalCount <= 1)
  }

  private def refreshWorkspacesList(filter: String = ""): Unit = {
    val all = workspaceService.getProjects
    val prevSelectedId = Option(workspacesList.getSelectionModel.getSelectedItem).map(_.id)
      .orElse(Some(workspaceService.getActiveProject.id))
    val filtered = if (filter.trim.isEmpty) all else {
      val lower = filter.trim.toLowerCase
      all.filter(_.name.toLowerCase.contains(lower))
    }
    workspacesList.getItems.setAll(filtered.asJava)
    prevSelectedId
      .flatMap(id => filtered.find(_.id == id))
      .orElse(filtered.headOption)
      .foreach(workspacesList.getSelectionModel.select)
    updateWorkspaceActionStates()
  }

  private def renameWorkspace(ws: WorkspaceProject): Unit = {
    CustomDialogStage.showTextPrompt("Rename Workspace", s"Rename '${ws.name}'", "Workspace Name:", ws.name).foreach { raw =>
      val clean = raw.trim
      if (clean.nonEmpty) {
        workspaceService.renameProject(ws.id, clean)
        onWorkspaceMetadataUpdated()
        appState.statusText.set(s"Renamed workspace to '$clean'.")
      }
    }
  }

  private def exportWorkspaceBundle(ws: WorkspaceProject): Unit = {
    val chooser = new FileChooser()
    chooser.setTitle("Export Workspace Bundle (.json)")
    chooser.setInitialFileName(s"${ws.name.toLowerCase.replaceAll("[^a-z0-9-_]", "-")}-protoform-workspace.json")
    Option(chooser.showSaveDialog(getScene.getWindow)).foreach { file =>
      val bundle = workspaceService.buildProjectExportBundle(Some(ws.id))
      val json = workspaceRepo.exportProjectBundleJson(bundle)
      Files.write(file.toPath, json.getBytes(StandardCharsets.UTF_8))
      appState.statusText.set(s"Exported workspace '${ws.name}' to ${file.getName}")
    }
  }

  private def importWorkspaceBundle(): Unit = {
    val chooser = new FileChooser()
    chooser.setTitle("Import Workspace Bundle (.json)")
    Option(chooser.showOpenDialog(getScene.getWindow)).foreach { file =>
      val raw = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
      workspaceRepo.importProjectBundleJson(raw) match {
        case Right(bundle) =>
          val confirmed = CustomDialogStage.showConfirm(
            title = "Import Workspace Bundle",
            header = s"Import '${bundle.project.name}' (${bundle.project.templates.size} templates, ${bundle.project.environments.size} envs)",
            message = "This will create a new workspace from the bundle and switch to it.",
            confirmLabel = "Import Workspace",
            iconCode = Feather.DOWNLOAD
          )
          if (confirmed) {
            val imported = workspaceService.importProjectBundle(bundle, asNewProject = true)
            onActiveWorkspaceReplaced(imported)
          }
        case Left(err) =>
          CustomDialogStage.showErrorAlert("Import Failed", err.message)
      }
    }
  }

  private def deleteWorkspace(ws: WorkspaceProject): Unit = {
    if (workspaceService.getProjects.size <= 1) return
    if (CustomDialogStage.showConfirm(
      title = "Delete Workspace",
      header = s"Delete workspace '${ws.name}'?",
      message = s"All ${ws.templates.size} templates, environments, and history inside '${ws.name}' will be removed.",
      confirmLabel = "Delete",
      destructive = true
    )) {
      val wasActive = ws.id == workspaceService.getActiveProject.id
      val next = workspaceService.deleteProject(ws.id)
      if (wasActive) onActiveWorkspaceReplaced(next)
      else {
        onWorkspaceMetadataUpdated()
        appState.statusText.set(s"Deleted workspace '${ws.name}'.")
      }
    }
  }
}
