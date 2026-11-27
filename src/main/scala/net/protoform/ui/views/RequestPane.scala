package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout._
import net.protoform.application.{ConnectionService, FormSchemaService, InvocationService, WorkspaceService}
import net.protoform.core.auth.{AuthPreset, MetadataHeader}
import net.protoform.core.call.{GrpcurlFormatter, RpcCall, RpcCallOptions, RpcCallStatus, StreamChunk, StreamDirection}
import net.protoform.core.model.{CallId, EndpointTarget}
import net.protoform.core.schema.{MethodSchema, RpcPattern, ServiceSchema}
import net.protoform.core.workspace.{AuthSerialization, CallFingerprint, CallHistoryEntry, ServiceTemplate, VariableInterpolator}
import net.protoform.ports.auth.DynamicAuthTokenPort
import net.protoform.ports.rpc.ActiveClientStream
import net.protoform.ui.editor.JsonEditorPane
import net.protoform.ui.forms.DynamicFormView
import net.protoform.ui.state.{AppState, WorkbenchTabSession}
import net.protoform.ui.util.{CustomDialogStage, TabDragSupport}
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._

class RequestPane(
  appState: AppState,
  connectionService: ConnectionService,
  invocationService: InvocationService,
  formSchemaService: FormSchemaService,
  workspaceService: WorkspaceService,
  responsePane: ResponsePane,
  onImportGrpcurlRequested: () => Unit = () => (),
  onIntrospectRequested: () => Unit = () => (),
  onLoadSchemaRequested: () => Unit = () => (),
  onOpenServicesRailRequested: () => Unit = () => (),
  onQuickSwitcherRequested: () => Unit = () => (),
  onSessionStateChanged: () => Unit = () => (),
  resolveSessionById: String => Option[WorkbenchTabSession] = _ => None,
  dynamicAuthPort: Option[DynamicAuthTokenPort] = None
) extends VBox(8) {
  setPadding(new Insets(10))
  setMinWidth(340)

  private var syncingSelectors: Boolean = false

  // Header Card (Symmetric single-row 48px bar matching ResponsePane)
  val headerContainer = new VBox(8)
  headerContainer.getStyleClass.addAll("elevated-card", "pane-header-bar")

  val serviceSelector: SearchableSelector[ServiceSchema] = new SearchableSelector[ServiceSchema](
    placeholder = "Select Service",
    itemText = _.simpleName,
    itemSearchText = s => s"${s.simpleName} ${s.fullName}",
    cellFactory = () => new ListCell[ServiceSchema]() {
      private val nameLbl = new Label() { getStyleClass.add("form-label") }
      private val pkgLbl = new Label() { getStyleClass.add("form-subtext") }
      private val row = new VBox(1, nameLbl, pkgLbl)
      override def updateItem(item: ServiceSchema, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        if (empty || item == null) { setText(null); setGraphic(null) }
        else {
          nameLbl.setText(item.simpleName)
          val hasPkg = item.packageName.nonEmpty
          pkgLbl.setText(item.packageName)
          pkgLbl.setVisible(hasPkg); pkgLbl.setManaged(hasPkg)
          setGraphic(row)
        }
      }
    },
    onSelected = svc => if (!syncingSelectors) selectServiceFromDropdown(svc)
  )
  serviceSelector.getStyleClass.add("selector-btn-left")
  serviceSelector.setMinWidth(105)
  HBox.setHgrow(serviceSelector, Priority.ALWAYS)

  val methodSelector: SearchableSelector[MethodSchema] = new SearchableSelector[MethodSchema](
    placeholder = "Select Method",
    itemText = _.name,
    itemSearchText = m => s"${m.name} ${m.pattern}",
    cellFactory = () => new ListCell[MethodSchema]() {
      private val nameLbl = new Label() { getStyleClass.add("form-label") }
      private val sp = new Region()
      HBox.setHgrow(sp, Priority.ALWAYS)
      private val pill = new Label() { getStyleClass.add("rpc-pill"); setMinWidth(Region.USE_PREF_SIZE) }
      private val row = new HBox(8, nameLbl, sp, pill) { setAlignment(Pos.CENTER_LEFT) }
      private val pillClasses = List("rpc-pill-unary", "rpc-pill-server", "rpc-pill-client", "rpc-pill-bidi")
      override def updateItem(item: MethodSchema, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        if (empty || item == null) { setText(null); setGraphic(null) }
        else {
          nameLbl.setText(item.name)
          pill.getStyleClass.removeAll(pillClasses: _*)
          val (txt, cls) = item.pattern match {
            case RpcPattern.Unary                  => ("UNARY", "rpc-pill-unary")
            case RpcPattern.ServerStreaming        => ("SERVER", "rpc-pill-server")
            case RpcPattern.ClientStreaming        => ("CLIENT", "rpc-pill-client")
            case RpcPattern.BidirectionalStreaming => ("BIDI", "rpc-pill-bidi")
          }
          pill.setText(txt); pill.getStyleClass.add(cls)
          setGraphic(row)
        }
      }
    },
    onSelected = method => if (!syncingSelectors) selectMethodFromDropdown(method)
  )
  methodSelector.getStyleClass.add("selector-btn-right")
  methodSelector.setMinWidth(105)
  HBox.setHgrow(methodSelector, Priority.ALWAYS)

  val methodPillBox = new HBox(0, serviceSelector, methodSelector)
  methodPillBox.setAlignment(Pos.CENTER_LEFT)
  methodPillBox.getStyleClass.add("method-pill-box")
  HBox.setHgrow(methodPillBox, Priority.ALWAYS)

  val activeTplBadge = new Label("")
  activeTplBadge.getStyleClass.add("tag-pill")
  activeTplBadge.setVisible(false)
  activeTplBadge.setManaged(false)

  val patternBadge = new Label("")
  patternBadge.getStyleClass.add("rpc-pill")
  patternBadge.setMinWidth(Region.USE_PREF_SIZE)
  patternBadge.setVisible(false)
  patternBadge.setManaged(false)

  // Action Controls (Clean overflow menu vs. Advanced inline strip)
  val resetDefaultsBtn = new Button("Fill Defaults", FontIcon.of(Feather.REFRESH_CW, 12))
  resetDefaultsBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  resetDefaultsBtn.setMinWidth(Region.USE_PREF_SIZE)
  resetDefaultsBtn.setTooltip(new Tooltip("Reset form & JSON to Protobuf default values"))

  val saveTemplateBtn = new Button("Save Template", FontIcon.of(Feather.BOOKMARK, 12))
  saveTemplateBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  saveTemplateBtn.setMinWidth(Region.USE_PREF_SIZE)
  saveTemplateBtn.setDisable(true)
  saveTemplateBtn.setTooltip(new Tooltip("Save or update reusable QA request template"))

  val copyCodeBtn = new Button("Python / Code", FontIcon.of(Feather.CODE, 12))
  copyCodeBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  copyCodeBtn.setMinWidth(Region.USE_PREF_SIZE)
  copyCodeBtn.setDisable(true)
  copyCodeBtn.setTooltip(new Tooltip("Export runnable Python (grpcio) or grpcurl snippet"))

  val copyGrpcurlBtn = new Button("grpcurl", FontIcon.of(Feather.TERMINAL, 12))
  copyGrpcurlBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  copyGrpcurlBtn.setMinWidth(Region.USE_PREF_SIZE)
  copyGrpcurlBtn.setDisable(true)
  copyGrpcurlBtn.setTooltip(new Tooltip("Copy equivalent grpcurl CLI command to clipboard"))

  val importGrpcurlBtn = new Button("Import", FontIcon.of(Feather.DOWNLOAD, 12))
  importGrpcurlBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  importGrpcurlBtn.setMinWidth(Region.USE_PREF_SIZE)
  importGrpcurlBtn.setTooltip(new Tooltip("Import a pasted grpcurl CLI command"))
  importGrpcurlBtn.setOnAction(_ => onImportGrpcurlRequested())

  val sendChunkBtn = new Button("Send Chunk", FontIcon.of(Feather.UPLOAD, 12))
  sendChunkBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
  sendChunkBtn.setMinWidth(Region.USE_PREF_SIZE)
  sendChunkBtn.setVisible(false)
  sendChunkBtn.setManaged(false)
  sendChunkBtn.setTooltip(new Tooltip("Send current payload into active stream (Ctrl+Enter)"))

  val completeStreamBtn = new Button("Complete", FontIcon.of(Feather.CHECK_CIRCLE, 12))
  completeStreamBtn.getStyleClass.addAll("small", "success", "interactive-hand")
  completeStreamBtn.setMinWidth(Region.USE_PREF_SIZE)
  completeStreamBtn.setVisible(false)
  completeStreamBtn.setManaged(false)
  completeStreamBtn.setTooltip(new Tooltip("Half-close client stream and await final response"))

  val streamScriptToggle = new ToggleButton("Script", FontIcon.of(Feather.FAST_FORWARD, 12))
  streamScriptToggle.getStyleClass.addAll("small", "subtle", "interactive-hand")
  streamScriptToggle.setMinWidth(Region.USE_PREF_SIZE)
  streamScriptToggle.setVisible(false)
  streamScriptToggle.setManaged(false)
  streamScriptToggle.setTooltip(new Tooltip("Open Stream Script / Replay drawer for Client or Bidi streaming"))

  val primaryActionIcon = FontIcon.of(Feather.PLAY, 13)
  val primaryActionBtn = new Button("", primaryActionIcon)
  primaryActionBtn.getStyleClass.addAll("btn-run", "play-icon-btn", "interactive-hand")
  primaryActionBtn.setMinWidth(Region.USE_PREF_SIZE)
  primaryActionBtn.setDisable(true)
  primaryActionBtn.setTooltip(new Tooltip("Send RPC Request (Ctrl+Enter)"))

  val fillDefaultsMenuItem = new MenuItem("Fill Defaults", FontIcon.of(Feather.REFRESH_CW, 13))
  fillDefaultsMenuItem.setOnAction(_ => resetDefaultsBtn.fire())

  val saveTemplateMenuItem = new MenuItem("Save as Template…", FontIcon.of(Feather.BOOKMARK, 13))
  saveTemplateMenuItem.setOnAction(_ => saveTemplateBtn.fire())

  val reloadTemplateMenuItem = new MenuItem("Reload Linked Template", FontIcon.of(Feather.ROTATE_CCW, 13))
  reloadTemplateMenuItem.setDisable(true)
  reloadTemplateMenuItem.setOnAction { _ =>
    appState.activeTemplate.get.foreach { tpl =>
      val ok = !hasUnsavedEditsOrValues || CustomDialogStage.showConfirm(
        title = "Reload Linked Template?",
        header = s"Reload '${tpl.name}'?",
        message = "Your unsaved form/JSON edits will be replaced with the saved template payload.",
        confirmLabel = "Reload Template",
        destructive = true
      )
      if (ok) {
        applyTemplate(tpl)
        appState.statusText.set(s"Reloaded template '${tpl.name}'.")
      }
    }
  }

  val copyCodeMenuItem = new MenuItem("Copy as Python / Code…", FontIcon.of(Feather.CODE, 13))
  copyCodeMenuItem.setDisable(true)
  copyCodeMenuItem.setOnAction(_ => copyCodeBtn.fire())

  val copyGrpcurlMenuItem = new MenuItem("Copy as grpcurl", FontIcon.of(Feather.TERMINAL, 13))
  copyGrpcurlMenuItem.setDisable(true)
  copyGrpcurlMenuItem.setOnAction(_ => copyGrpcurlBtn.fire())

  val importGrpcurlMenuItem = new MenuItem("Import grpcurl…", FontIcon.of(Feather.DOWNLOAD, 13))
  importGrpcurlMenuItem.setOnAction(_ => onImportGrpcurlRequested())

  val streamScriptMenuSep = new SeparatorMenuItem()
  val streamScriptMenuItem = new CheckMenuItem("Stream Script Drawer")
  streamScriptMenuItem.selectedProperty().bindBidirectional(streamScriptToggle.selectedProperty())
  streamScriptMenuSep.setVisible(false)
  streamScriptMenuItem.setVisible(false)

  val moreActionsBtn = new MenuButton("", FontIcon.of(Feather.MORE_HORIZONTAL, 13))
  moreActionsBtn.getItems.addAll(
    fillDefaultsMenuItem,
    saveTemplateMenuItem,
    reloadTemplateMenuItem,
    new SeparatorMenuItem(),
    copyCodeMenuItem,
    copyGrpcurlMenuItem,
    importGrpcurlMenuItem,
    streamScriptMenuSep,
    streamScriptMenuItem
  )
  moreActionsBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "no-caret", "interactive-hand")
  moreActionsBtn.setMinWidth(Region.USE_PREF_SIZE)
  moreActionsBtn.setTooltip(new Tooltip("Fill Defaults, Templates & Code/grpcurl Export"))

  val rightActions = new HBox(6, moreActionsBtn, primaryActionBtn)
  rightActions.setAlignment(Pos.CENTER_RIGHT)
  rightActions.setMinWidth(Region.USE_PREF_SIZE)

  val selectorRow = new HBox(8, methodPillBox, activeTplBadge, patternBadge, rightActions)
  selectorRow.setAlignment(Pos.CENTER_LEFT)

  private val streamBarLabel = new Label("STREAM")
  streamBarLabel.getStyleClass.add("field-meta-mono")
  val streamingBar = new HBox(8, streamBarLabel, streamScriptToggle, sendChunkBtn, completeStreamBtn)
  streamingBar.getStyleClass.add("streaming-control-bar")
  streamingBar.setAlignment(Pos.CENTER_LEFT)
  streamingBar.setVisible(false)
  streamingBar.setManaged(false)

  val leftActions = new FlowPane(6, 6)
  leftActions.setAlignment(Pos.CENTER_LEFT)
  leftActions.getChildren.addAll(resetDefaultsBtn, saveTemplateBtn, copyCodeBtn, copyGrpcurlBtn, importGrpcurlBtn)

  headerContainer.getChildren.addAll(selectorRow, leftActions)

  // Non-blocking warning banner for JSON -> Form parse issues
  val syncWarnLabel = new Label("")
  syncWarnLabel.setWrapText(true)
  HBox.setHgrow(syncWarnLabel, Priority.ALWAYS)
  private val closeWarnBtn = new Button("", FontIcon.of(Feather.X, 11))
  closeWarnBtn.getStyleClass.addAll("row-delete-btn", "interactive-hand")
  closeWarnBtn.setTooltip(new Tooltip("Dismiss Warning"))
  val syncWarnBanner = new HBox(8, FontIcon.of(Feather.ALERT_TRIANGLE, 13), syncWarnLabel, closeWarnBtn)
  syncWarnBanner.setAlignment(Pos.CENTER_LEFT)
  syncWarnBanner.getStyleClass.add("warn-banner")
  syncWarnBanner.setVisible(false)
  syncWarnBanner.setManaged(false)
  closeWarnBtn.setOnAction { _ =>
    syncWarnBanner.setVisible(false)
    syncWarnBanner.setManaged(false)
  }

  // Tabs
  val tabPane = new TabPane()
  VBox.setVgrow(tabPane, Priority.ALWAYS)

  val formContainer = new StackPane()
  val formScrollPane = new ScrollPane(formContainer)
  formScrollPane.setFitToWidth(true)
  formScrollPane.setFitToHeight(true)

  val emptyMethodIcon = FontIcon.of(Feather.TERMINAL, 24)
  emptyMethodIcon.getStyleClass.add("empty-state-icon")
  val emptyMethodTitle = new Label("Connect to a gRPC Target")
  emptyMethodTitle.getStyleClass.add("empty-state-title")
  val emptyMethodDesc = new Label("Enter an endpoint above and click Introspect, load an offline .proto/.desc file, or import a grpcurl command.")
  emptyMethodDesc.getStyleClass.add("form-subtext")
  emptyMethodDesc.setWrapText(true)
  emptyMethodDesc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER)
  emptyMethodDesc.setMaxWidth(360)

  val emptyIntrospectBtn = new Button("Introspect Target", FontIcon.of(Feather.SEARCH, 12))
  emptyIntrospectBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
  emptyIntrospectBtn.setOnAction(_ => onIntrospectRequested())

  val emptyLoadSchemaBtn = new Button("Load .desc / .proto", FontIcon.of(Feather.FILE_TEXT, 12))
  emptyLoadSchemaBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  emptyLoadSchemaBtn.setOnAction(_ => onLoadSchemaRequested())

  val emptyImportBtn = new Button("Import grpcurl…", FontIcon.of(Feather.DOWNLOAD, 12))
  emptyImportBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  emptyImportBtn.setOnAction(_ => onImportGrpcurlRequested())

  val emptyQuickJumpBtn = new Button("Quick Jump (Ctrl+K)", FontIcon.of(Feather.COMMAND, 12))
  emptyQuickJumpBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
  emptyQuickJumpBtn.setOnAction(_ => onQuickSwitcherRequested())

  val emptyOpenServicesBtn = new Button("Services Rail (Ctrl+B)", FontIcon.of(Feather.LAYERS, 12))
  emptyOpenServicesBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  emptyOpenServicesBtn.setOnAction(_ => onOpenServicesRailRequested())

  val emptyActionsFlow = new FlowPane(8, 8)
  emptyActionsFlow.setAlignment(Pos.CENTER)
  emptyActionsFlow.getChildren.addAll(emptyIntrospectBtn, emptyLoadSchemaBtn, emptyImportBtn)

  val noMethodEmptyBox = new VBox(10, emptyMethodIcon, emptyMethodTitle, emptyMethodDesc, emptyActionsFlow)
  noMethodEmptyBox.setAlignment(Pos.CENTER)
  noMethodEmptyBox.setPadding(new Insets(28))

  val formTab = new Tab("Form View", formScrollPane)
  formTab.setClosable(false)
  TabDragSupport.initDraggableTab(formTab, "form", "Form View")

  val jsonEditorPane = new JsonEditorPane()
  val jsonTab = new Tab("JSON View", jsonEditorPane)
  jsonTab.setClosable(false)
  TabDragSupport.initDraggableTab(jsonTab, "json", "JSON View")

  val metadataEditor = new MetadataEditor(appState, workspaceService, dynamicAuthPort)
  val authTab = new Tab("Headers & Auth", metadataEditor)
  authTab.setClosable(false)
  TabDragSupport.initDraggableTab(authTab, "auth", "Headers & Auth")

  val streamScriptPane = new StreamScriptPane(
    appState = appState,
    getCurrentPayload = () => currentJsonPayload,
    ensureActiveStream = () => ensureClientStreamOpen(),
    onChunkSent = resolved => {
      val idx = sentChunkCounter.incrementAndGet()
      responsePane.appendStreamChunk(StreamChunk(idx, System.currentTimeMillis(), resolved, StreamDirection.Sent))
    },
    onAutoComplete = () => if (!completeStreamBtn.isDisabled) completeStreamBtn.fire()
  )
  streamScriptPane.setVisible(false)
  streamScriptPane.setManaged(false)

  streamScriptToggle.selectedProperty().addListener { (_, _, open) =>
    streamScriptPane.setVisible(open)
    streamScriptPane.setManaged(open)
  }

  tabPane.getTabs.addAll(formTab, jsonTab, authTab)
  getChildren.addAll(headerContainer, streamingBar, syncWarnBanner, streamScriptPane, tabPane)

  private var currentDynamicForm: Option[DynamicFormView] = None
  private var activeStreamingCallId: Option[CallId] = None
  private var activeClientStream: Option[ActiveClientStream] = None
  private var currentMethodSchema: Option[MethodSchema] = None
  private val sentChunkCounter = new AtomicLong(0)
  private var lastEditedIsForm: Boolean = true
  private var programmaticJsonSet: Boolean = false
  private var userEditedSinceLoad: Boolean = false
  private var activeSessionId: String = "default"
  private var suppressMethodListener: Boolean = false

  def currentSessionId: String = activeSessionId

  def hasUnsavedEditsOrValues: Boolean = {
    currentMethodSchema.isDefined && (userEditedSinceLoad || currentDynamicForm.exists(!_.isAllDefault))
  }

  private def refreshActionBarVisibility(): Unit = {
    val hasServices = !appState.discoveredServices.isEmpty
    val hasMethod = currentMethodSchema.isDefined
    val advanced = appState.showAdvancedActions.get
    val isClientOrBidi = currentMethodSchema.exists(m =>
      m.pattern == RpcPattern.ClientStreaming || m.pattern == RpcPattern.BidirectionalStreaming
    )

    headerContainer.setVisible(hasServices || hasMethod)
    headerContainer.setManaged(hasServices || hasMethod)
    streamingBar.setVisible(hasMethod && isClientOrBidi)
    streamingBar.setManaged(hasMethod && isClientOrBidi)
    rightActions.setVisible(hasMethod)
    rightActions.setManaged(hasMethod)
    moreActionsBtn.setVisible(hasMethod && !advanced)
    moreActionsBtn.setManaged(hasMethod && !advanced)
    leftActions.setVisible(hasMethod && advanced)
    leftActions.setManaged(hasMethod && advanced)
    streamScriptToggle.setVisible(hasMethod && isClientOrBidi)
    streamScriptToggle.setManaged(hasMethod && isClientOrBidi)
    streamScriptMenuSep.setVisible(hasMethod && isClientOrBidi)
    streamScriptMenuItem.setVisible(hasMethod && isClientOrBidi)

    if (!hasMethod) {
      if (hasServices) {
        emptyMethodTitle.setText("Select an RPC Method")
        emptyMethodDesc.setText("Pick a service and method above, press Ctrl+K to Quick Jump, or open the Services drawer (Ctrl+B).")
        emptyActionsFlow.getChildren.setAll(emptyQuickJumpBtn, emptyOpenServicesBtn, emptyImportBtn)
      } else {
        emptyMethodTitle.setText("Connect to a gRPC Target")
        emptyMethodDesc.setText("Enter an endpoint above and click Introspect, load an offline .proto/.desc file, or import a grpcurl command.")
        emptyActionsFlow.getChildren.setAll(emptyIntrospectBtn, emptyLoadSchemaBtn, emptyImportBtn)
      }
    }
  }

  private def syncSelectorsWithServices(): Unit = {
    syncingSelectors = true
    try {
      val services = appState.discoveredServices.asScala.toList
      serviceSelector.setItems(services)
      currentMethodSchema match {
        case Some(m) =>
          val svcOpt = services.find(_.fullName == m.serviceFullName)
          serviceSelector.setSelected(svcOpt)
          methodSelector.setItems(svcOpt.map(_.methods).getOrElse(Nil))
          methodSelector.setSelected(Some(m))
        case None =>
          val firstSvc = services.headOption
          serviceSelector.setSelected(firstSvc)
          methodSelector.setItems(firstSvc.map(_.methods).getOrElse(Nil))
          methodSelector.setSelected(None)
      }
    } finally {
      syncingSelectors = false
    }
  }

  private def selectServiceFromDropdown(svc: ServiceSchema): Unit = {
    methodSelector.setItems(svc.methods)
    val nextMethod = svc.methods.headOption
    methodSelector.setSelected(nextMethod)
    nextMethod.foreach { m =>
      appState.selectedMethod.set(Some(m))
      workspaceService.updateActiveProjectLastMethod(Some(m.fullMethodName))
    }
  }

  private def selectMethodFromDropdown(method: MethodSchema): Unit = {
    appState.selectedMethod.set(Some(method))
    workspaceService.updateActiveProjectLastMethod(Some(method.fullMethodName))
  }

  formContainer.getChildren.setAll(noMethodEmptyBox)
  syncSelectorsWithServices()
  refreshActionBarVisibility()
  appState.discoveredServices.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: ServiceSchema]) =>
    Platform.runLater { () =>
      syncSelectorsWithServices()
      refreshActionBarVisibility()
    }
  }
  appState.showAdvancedActions.addListener { (_, _, advanced) =>
    Platform.runLater { () =>
      refreshActionBarVisibility()
      currentDynamicForm.foreach(_.setAdvancedBadges(advanced))
    }
  }

  jsonEditorPane.codeEditor.codeArea.textProperty().addListener { (_, _, _) =>
    if (!programmaticJsonSet) {
      val wasEdited = userEditedSinceLoad
      userEditedSinceLoad = true
      if (jsonTab.isSelected) {
        lastEditedIsForm = false
      }
      if (!wasEdited) onSessionStateChanged()
    }
  }

  // Track active template badge
  appState.activeTemplate.addListener { (_, _, tplOpt) =>
    Platform.runLater { () =>
      tplOpt match {
        case Some(tpl) =>
          activeTplBadge.setText(s"Template: ${tpl.name}")
          activeTplBadge.setVisible(true)
          activeTplBadge.setManaged(true)
          reloadTemplateMenuItem.setDisable(false)
        case None =>
          activeTplBadge.setText("")
          activeTplBadge.setVisible(false)
          activeTplBadge.setManaged(false)
          reloadTemplateMenuItem.setDisable(true)
      }
    }
  }

  // React to selected method changes
  appState.selectedMethod.addListener { (_, oldMethod, newMethodOpt) =>
    if (!suppressMethodListener) {
      oldMethod.foreach { m =>
        if (workspaceService.getSettings.autoSavePayloads) {
          workspaceService.saveMethodPayload(m.fullMethodName, currentJsonPayload)
        }
      }

      Platform.runLater { () =>
        currentMethodSchema = newMethodOpt
        newMethodOpt match {
          case Some(method) =>
            if (!appState.activeTemplate.get.exists(_.fullMethodName == method.fullMethodName)) {
              appState.activeTemplate.set(workspaceService.getLastTemplateForMethod(method.fullMethodName))
            }
            setupMethod(method)
            appState.templateToLoad.get.filter(_.fullMethodName == method.fullMethodName).foreach { tpl =>
              applyTemplate(tpl)
              appState.templateToLoad.set(None)
            }
            appState.historyEntryToLoad.get.filter(_.fullMethodName == method.fullMethodName).foreach { entry =>
              applyHistoryEntry(entry)
              appState.historyEntryToLoad.set(None)
            }
          case None =>
            clearMethod()
        }
        onSessionStateChanged()
      }
    }
  }

  appState.templateToLoad.addListener { (_, _, tplOpt) =>
    tplOpt.foreach { tpl =>
      Platform.runLater { () =>
        if (currentMethodSchema.exists(_.fullMethodName == tpl.fullMethodName) && appState.templateToLoad.get.contains(tpl)) {
          applyTemplate(tpl)
          appState.templateToLoad.set(None)
        }
      }
    }
  }

  appState.historyEntryToLoad.addListener { (_, _, entryOpt) =>
    entryOpt.foreach { entry =>
      Platform.runLater { () =>
        if (currentMethodSchema.exists(_.fullMethodName == entry.fullMethodName) && appState.historyEntryToLoad.get.contains(entry)) {
          applyHistoryEntry(entry)
          appState.historyEntryToLoad.set(None)
        }
      }
    }
  }

  // Live Show Defaults & Preserve Field Names reactivity
  appState.showDefaultValues.addListener { (_, _, showDefaults) =>
    Platform.runLater { () =>
      currentDynamicForm.foreach { form =>
        form.refreshDefaultBadges(showDefaults)
        if (formTab.isSelected || form.isAllDefault) {
          setFormattedJson(form.collectJson(showDefaults, appState.preserveProtoFieldNames.get))
        }
      }
    }
  }

  appState.preserveProtoFieldNames.addListener { (_, _, preserveNames) =>
    Platform.runLater { () =>
      currentDynamicForm.foreach { form =>
        if (formTab.isSelected) {
          setFormattedJson(form.collectJson(appState.showDefaultValues.get, preserveNames))
        }
      }
    }
  }

  // Bi-directional Form <-> JSON sync (supports both same-pane and cross-pane docked tabs)
  jsonTab.selectedProperty().addListener { (_, _, isSelected) =>
    if (isSelected && lastEditedIsForm) {
      syncWarnBanner.setVisible(false); syncWarnBanner.setManaged(false)
      currentDynamicForm.foreach { form =>
        val json = form.collectJson(appState.showDefaultValues.get, appState.preserveProtoFieldNames.get)
        setFormattedJson(json)
      }
    }
  }

  formTab.selectedProperty().addListener { (_, _, isSelected) =>
    if (isSelected && !lastEditedIsForm) {
      hydrateFormFromJson(jsonEditorPane.getText)
    }
  }

  resetDefaultsBtn.setOnAction { _ =>
    appState.selectedMethod.get.foreach { method =>
      val proceed = !hasUnsavedEditsOrValues || CustomDialogStage.showConfirm(
        title = "Fill Defaults?",
        header = s"Reset '${method.name}' to Protobuf defaults?",
        message = "Your current form and JSON edits for this method will be replaced.",
        confirmLabel = "Fill Defaults",
        destructive = true
      )
      if (proceed) {
        val includeDefaults = appState.showDefaultValues.get
        val preserveNames = appState.preserveProtoFieldNames.get
        currentDynamicForm.foreach(_.resetAllToDefaults())
        val defaultJson = formSchemaService.generateDefaultJson(method, includeDefaults, preserveNames)
        setFormattedJson(defaultJson)
        userEditedSinceLoad = false
        syncWarnBanner.setVisible(false); syncWarnBanner.setManaged(false)
        if (workspaceService.getSettings.autoSavePayloads) {
          workspaceService.saveMethodPayload(method.fullMethodName, defaultJson)
        }
        appState.statusText.set("Reset form & JSON to Protobuf defaults.")
      }
    }
  }

  saveTemplateBtn.setOnAction { _ =>
    currentMethodSchema.foreach(showSaveTemplateDialog)
  }

  copyCodeBtn.setOnAction { _ =>
    showCodeExportDialog()
  }

  copyGrpcurlBtn.setOnAction { _ =>
    copyAsGrpcurl()
  }

  sendChunkBtn.setOnAction(_ => dispatchStreamChunk())

  completeStreamBtn.setOnAction { _ =>
    activeClientStream.foreach { stream =>
      stream.complete()
      appState.statusText.set("Client stream completed. Awaiting server response...")
      sendChunkBtn.setDisable(true)
      completeStreamBtn.setDisable(true)
    }
  }

  primaryActionBtn.setOnAction { _ =>
    if (activeStreamingCallId.isDefined || activeClientStream.isDefined) {
      activeStreamingCallId.foreach(invocationService.cancel)
      activeClientStream.foreach(_.cancel("User cancelled stream"))
      resetStreamState()
      appState.statusText.set("Stream cancelled by user.")
    } else {
      executeRequest()
    }
  }

  def triggerPrimaryOrChunkAction(): Unit = {
    if (activeClientStream.isDefined && sendChunkBtn.isVisible && !sendChunkBtn.isDisabled) {
      dispatchStreamChunk()
    } else if (!primaryActionBtn.isDisabled) {
      primaryActionBtn.fire()
    }
  }

  def applyImportedAuth(preset: AuthPreset, headers: List[MetadataHeader]): Unit = {
    metadataEditor.syncFromExternalAuth(preset, headers)
  }

  def applyImportedJson(json: String): Unit = {
    setFormattedJson(json)
    hydrateFormFromJson(json)
    userEditedSinceLoad = false
    currentMethodSchema.foreach { m =>
      if (workspaceService.getSettings.autoSavePayloads) {
        workspaceService.saveMethodPayload(m.fullMethodName, json)
      }
    }
  }

  def flushUnsavedPayload(): Unit = {
    if (workspaceService.getSettings.autoSavePayloads) {
      currentMethodSchema.foreach { m =>
        workspaceService.saveMethodPayload(m.fullMethodName, currentJsonPayload)
      }
    }
  }

  private def ensureClientStreamOpen(): Option[ActiveClientStream] = {
    if (activeClientStream.isDefined) {
      activeClientStream
    } else {
      (appState.activeTarget.get, appState.selectedMethod.get) match {
        case (Some(target), Some(method)) if method.pattern == RpcPattern.ClientStreaming || method.pattern == RpcPattern.BidirectionalStreaming =>
          connectionService.connect(target, workspaceService.getSettings.maxInboundMessageMb) match {
            case Right(channel) =>
              val headers = appState.getResolvedHeaders
              val opts = currentCallOptions
              if (method.pattern == RpcPattern.ClientStreaming) executeClientStreaming(channel, target, method, headers, opts)
              else executeBidiStreaming(channel, target, method, headers, opts)
              activeClientStream
            case Left(err) =>
              appState.statusText.set(s"Cannot start stream: ${err.message}")
              None
          }
        case _ => None
      }
    }
  }

  def shutdown(): Unit = {
    streamScriptPane.shutdown()
  }

  private def dispatchStreamChunk(): Unit = {
    activeClientStream.foreach { stream =>
      val payload = currentJsonPayload
      stream.sendNext(payload) match {
        case Right(_) =>
          val idx = sentChunkCounter.incrementAndGet()
          responsePane.appendStreamChunk(StreamChunk(idx, System.currentTimeMillis(), payload, StreamDirection.Sent))
          appState.statusText.set("Sent chunk into stream.")
        case Left(err) =>
          appState.statusText.set(s"Failed to send chunk: ${err.message}")
      }
    }
  }

  private def currentCallOptions: RpcCallOptions = {
    val s = workspaceService.getSettings
    RpcCallOptions(
      deadlineSeconds = s.requestDeadlineSeconds,
      useGzipCompression = appState.useGzipCompression.get,
      includeDefaultFields = appState.showDefaultValues.get,
      preserveProtoFieldNames = appState.preserveProtoFieldNames.get
    )
  }

  private def currentJsonPayload: String = {
    if (currentDynamicForm.isDefined && (formTab.isSelected && (!jsonTab.isSelected || lastEditedIsForm))) {
      currentDynamicForm.get.collectJson(appState.showDefaultValues.get, appState.preserveProtoFieldNames.get)
    } else {
      jsonEditorPane.getText
    }
  }

  private def setFormattedJson(raw: String): Unit = {
    programmaticJsonSet = true
    try {
      if (appState.autoFormatJson.get) {
        FormSchemaService.formatJson(raw) match {
          case Right(pretty) => jsonEditorPane.setText(pretty)
          case Left(_)       => jsonEditorPane.setText(raw)
        }
      } else {
        jsonEditorPane.setText(raw)
      }
    } finally {
      programmaticJsonSet = false
    }
  }

  private def hydrateFormFromJson(json: String): Unit = {
    currentDynamicForm.foreach { form =>
      form.populateFromJson(json) match {
        case Right(_) =>
          syncWarnBanner.setVisible(false)
          syncWarnBanner.setManaged(false)
        case Left(err) =>
          syncWarnLabel.setText(s"Could not sync JSON to Form View: $err")
          syncWarnBanner.setVisible(true)
          syncWarnBanner.setManaged(true)
      }
    }
  }

  private def setupMethod(method: MethodSchema, initialJsonOverride: Option[String] = None): Unit = {
    patternBadge.getStyleClass.removeAll("rpc-pill-unary", "rpc-pill-server", "rpc-pill-client", "rpc-pill-bidi")
    method.pattern match {
      case RpcPattern.Unary =>
        patternBadge.setText("")
        patternBadge.setVisible(false); patternBadge.setManaged(false)
      case RpcPattern.ServerStreaming =>
        patternBadge.setText("SERVER")
        patternBadge.getStyleClass.add("rpc-pill-server")
        patternBadge.setVisible(true); patternBadge.setManaged(true)
      case RpcPattern.ClientStreaming =>
        patternBadge.setText("CLIENT")
        patternBadge.getStyleClass.add("rpc-pill-client")
        patternBadge.setVisible(true); patternBadge.setManaged(true)
      case RpcPattern.BidirectionalStreaming =>
        patternBadge.setText("BIDI")
        patternBadge.getStyleClass.add("rpc-pill-bidi")
        patternBadge.setVisible(true); patternBadge.setManaged(true)
    }
    primaryActionBtn.setDisable(false)
    saveTemplateBtn.setDisable(false)
    copyCodeBtn.setDisable(false)
    copyCodeMenuItem.setDisable(false)
    copyGrpcurlBtn.setDisable(false)
    copyGrpcurlMenuItem.setDisable(false)

    primaryActionIcon.setIconCode(Feather.PLAY)
    primaryActionBtn.setText("")
    primaryActionBtn.setTooltip(new Tooltip(if (method.pattern == RpcPattern.Unary) "Send Request (Ctrl+Enter)" else "Start Stream (Ctrl+Enter)"))
    syncSelectorsWithServices()

    val isClientOrBidi = method.pattern == RpcPattern.ClientStreaming || method.pattern == RpcPattern.BidirectionalStreaming
    if (!isClientOrBidi) {
      streamScriptToggle.setSelected(false)
      streamScriptPane.stopReplay("Idle")
    }
    refreshActionBarVisibility()

    sendChunkBtn.setVisible(false); sendChunkBtn.setManaged(false)
    completeStreamBtn.setVisible(false); completeStreamBtn.setManaged(false)
    syncWarnBanner.setVisible(false); syncWarnBanner.setManaged(false)

    val savedPayload = initialJsonOverride.orElse(workspaceService.getMethodPayload(method.fullMethodName))
    val initialJson = savedPayload.getOrElse(
      formSchemaService.generateDefaultJson(method, appState.showDefaultValues.get, appState.preserveProtoFieldNames.get)
    )

    val formView = new DynamicFormView(
      method.inputSchema,
      initialAdvancedBadges = appState.showAdvancedActions.get,
      responseTypeSimpleName = Some(method.outputSchema.simpleName)
    )
    formView.onFormChanged { () =>
      lastEditedIsForm = true
      val wasEdited = userEditedSinceLoad
      userEditedSinceLoad = true
      if (!wasEdited) onSessionStateChanged()
    }
    currentDynamicForm = Some(formView)
    formContainer.getChildren.setAll(formView)

    setFormattedJson(initialJson)
    hydrateFormFromJson(initialJson)
    formView.refreshDefaultBadges(appState.showDefaultValues.get)
    userEditedSinceLoad = false
  }

  private def clearMethod(): Unit = {
    patternBadge.setText("")
    patternBadge.setVisible(false); patternBadge.setManaged(false)
    primaryActionBtn.setDisable(true)
    saveTemplateBtn.setDisable(true)
    copyCodeBtn.setDisable(true)
    copyCodeMenuItem.setDisable(true)
    copyGrpcurlBtn.setDisable(true)
    copyGrpcurlMenuItem.setDisable(true)
    streamScriptToggle.setSelected(false)
    streamScriptPane.stopReplay("Idle")
    formContainer.getChildren.setAll(noMethodEmptyBox)
    currentDynamicForm = None
    syncSelectorsWithServices()
    refreshActionBarVisibility()
    setFormattedJson("{}")
    userEditedSinceLoad = false
    syncWarnBanner.setVisible(false); syncWarnBanner.setManaged(false)
    resetStreamState()
  }

  private def resetStreamState(): Unit = {
    streamScriptPane.stopReplay("Idle")
    activeStreamingCallId = None
    activeClientStream = None
    sentChunkCounter.set(0)
    sendChunkBtn.setVisible(false); sendChunkBtn.setManaged(false)
    completeStreamBtn.setVisible(false); completeStreamBtn.setManaged(false)
    primaryActionBtn.getStyleClass.removeAll("danger")
    if (!primaryActionBtn.getStyleClass.contains("btn-run")) primaryActionBtn.getStyleClass.add("btn-run")
    primaryActionIcon.setIconCode(Feather.PLAY)
    currentMethodSchema.foreach { m =>
      primaryActionBtn.setText("")
      primaryActionBtn.setTooltip(new Tooltip(if (m.pattern == RpcPattern.Unary) "Send Request (Ctrl+Enter)" else "Start Stream (Ctrl+Enter)"))
    }
  }

  def refreshMetadataForProjectChange(): Unit = {
    metadataEditor.refreshForProjectChange()
  }

  def captureIntoSession(session: WorkbenchTabSession): Unit = {
    session.methodFullName = currentMethodSchema.map(_.fullMethodName)
    session.title = currentMethodSchema.map(_.name).getOrElse("New Request")
    session.requestJson = currentJsonPayload
    session.authPreset = Option(appState.authPreset.get).getOrElse(AuthPreset.None)
    session.customHeaders = appState.customHeaders.asScala.toList
    session.selectedAuthProfileId = Option(appState.selectedAuthProfile.get).flatten.map(_.id)
    session.activeTemplateId = Option(appState.activeTemplate.get).flatten.map(_.id)
    session.userEdited = userEditedSinceLoad
    session.lastEditedIsForm = lastEditedIsForm
    session.activeStreamingCallId = activeStreamingCallId
    session.activeClientStream = activeClientStream
    session.sentChunkCounter = sentChunkCounter.get()
  }

  def restoreFromSession(session: WorkbenchTabSession, allMethods: List[MethodSchema]): Unit = {
    activeSessionId = session.id
    val methodOpt = session.methodFullName.flatMap(full => allMethods.find(_.fullMethodName == full))
    val tplOpt = session.activeTemplateId.flatMap(id => workspaceService.getAllTemplates.find(_.id == id))
      .orElse(methodOpt.flatMap(m => workspaceService.getLastTemplateForMethod(m.fullMethodName)))

    suppressMethodListener = true
    try {
      appState.selectedMethod.set(methodOpt)
      appState.activeTemplate.set(tplOpt)
    } finally {
      suppressMethodListener = false
    }

    currentMethodSchema = methodOpt
    methodOpt match {
      case Some(method) =>
        val jsonOpt = Option(session.requestJson).filter(_.trim.nonEmpty).filter(_ != "{}")
        setupMethod(method, initialJsonOverride = jsonOpt)
      case None =>
        clearMethod()
        if (session.requestJson != null && session.requestJson.trim.nonEmpty) {
          setFormattedJson(session.requestJson)
        }
    }

    metadataEditor.syncFromExternalAuth(session.authPreset, session.customHeaders)
    session.selectedAuthProfileId.flatMap(id => workspaceService.getAuthProfiles.find(_.id == id)).foreach { prof =>
      appState.selectedAuthProfile.set(Some(prof))
    }

    userEditedSinceLoad = session.userEdited
    lastEditedIsForm = session.lastEditedIsForm
    activeStreamingCallId = session.activeStreamingCallId
    activeClientStream = session.activeClientStream
    sentChunkCounter.set(session.sentChunkCounter)
    syncStreamControlsVisual()
  }

  private def syncStreamControlsVisual(): Unit = {
    val isStreaming = activeStreamingCallId.isDefined || activeClientStream.isDefined
    val isClientOrBidi = currentMethodSchema.exists(m =>
      m.pattern == RpcPattern.ClientStreaming || m.pattern == RpcPattern.BidirectionalStreaming
    )
    if (isStreaming) {
      primaryActionBtn.setTooltip(new Tooltip("Cancel Stream"))
      primaryActionIcon.setIconCode(Feather.SQUARE)
      primaryActionBtn.getStyleClass.removeAll("btn-run", "accent")
      if (!primaryActionBtn.getStyleClass.contains("danger")) primaryActionBtn.getStyleClass.add("danger")
      sendChunkBtn.setVisible(isClientOrBidi); sendChunkBtn.setManaged(isClientOrBidi); sendChunkBtn.setDisable(false)
      completeStreamBtn.setVisible(isClientOrBidi); completeStreamBtn.setManaged(isClientOrBidi); completeStreamBtn.setDisable(false)
    } else {
      resetStreamState()
    }
  }

  private def applyTemplate(tpl: ServiceTemplate): Unit = {
    setFormattedJson(tpl.requestJson)
    hydrateFormFromJson(tpl.requestJson)
    userEditedSinceLoad = false

    val preset = AuthSerialization.decodePreset(tpl.authPresetType, tpl.authPresetData)
    metadataEditor.syncFromExternalAuth(preset, tpl.customHeaders)
    workspaceService.saveMethodPayload(tpl.fullMethodName, tpl.requestJson)
    workspaceService.setLastTemplateForMethod(tpl.fullMethodName, Some(tpl.id))

    if (appState.runTemplateAfterLoad.get) {
      appState.runTemplateAfterLoad.set(false)
      executeRequest()
    }
  }

  private def applyHistoryEntry(entry: CallHistoryEntry): Unit = {
    setFormattedJson(entry.requestJson)
    hydrateFormFromJson(entry.requestJson)
    userEditedSinceLoad = false

    val preset = AuthSerialization.decodePreset(entry.authPresetType, entry.authPresetData)
    metadataEditor.syncFromExternalAuth(preset, entry.customHeaders)
    workspaceService.saveMethodPayload(entry.fullMethodName, entry.requestJson)
  }

  private def showSaveTemplateDialog(method: MethodSchema): Unit = {
    val existingActive = appState.activeTemplate.get.filter(_.fullMethodName == method.fullMethodName)

    val grid = new GridPane()
    grid.setHgap(12); grid.setVgap(10); grid.setPadding(new Insets(16))
    val labelCol = new ColumnConstraints()
    labelCol.setMinWidth(120)
    val fieldCol = new ColumnConstraints()
    fieldCol.setHgrow(Priority.ALWAYS)
    grid.getColumnConstraints.addAll(labelCol, fieldCol)

    val nameField = new TextField(existingActive.map(_.name).getOrElse(s"${method.name} - Smoke"))
    nameField.setPromptText("Template Name (e.g. Create Order - Valid User)")

    val folderField = new TextField(existingActive.map(_.folderPath).getOrElse(""))
    folderField.setPromptText("Optional folder path (e.g. Orders/Negative)")

    val descField = new TextField(existingActive.map(_.description).getOrElse(""))
    descField.setPromptText("QA notes / scenario description")

    val tagsField = new TextField(existingActive.map(_.tags.mkString(", ")).getOrElse(""))
    tagsField.setPromptText("Comma-separated tags (e.g. smoke, regression, negative)")

    val expectedCombo = new ComboBox[String]()
    expectedCombo.setEditable(true)
    expectedCombo.setMaxWidth(Double.MaxValue)
    expectedCombo.getItems.addAll("", "OK", "INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND", "FAILED_PRECONDITION")
    expectedCombo.setValue(existingActive.flatMap(_.expectedStatus).getOrElse(""))
    expectedCombo.setPromptText("Optional expected gRPC status")

    val updateExistingCheck = new CheckBox(existingActive.map(t => s"Update existing '${t.name}'").getOrElse(""))
    updateExistingCheck.setSelected(existingActive.isDefined)
    updateExistingCheck.setVisible(existingActive.isDefined)
    updateExistingCheck.setManaged(existingActive.isDefined)

    val methodInfo = new Label(method.fullMethodName)
    methodInfo.getStyleClass.add("mono-text")
    methodInfo.setWrapText(true)

    grid.addRow(0, new Label("Template Name:"), nameField)
    grid.addRow(1, new Label("Folder Path:"), folderField)
    grid.addRow(2, new Label("Description:"), descField)
    grid.addRow(3, new Label("QA Tags:"), tagsField)
    grid.addRow(4, new Label("Expected Status:"), expectedCombo)
    grid.addRow(5, new Label("Target Method:"), methodInfo)
    if (existingActive.isDefined) {
      grid.add(updateExistingCheck, 1, 6)
    }

    val scroll = new ScrollPane(grid)
    scroll.setFitToWidth(true)

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val saveBtn = new Button("Save Template", FontIcon.of(Feather.CHECK, 12))
    saveBtn.getStyleClass.addAll("accent", "interactive-hand")
    saveBtn.setDefaultButton(true)

    var savedOpt: Option[ServiceTemplate] = None
    val stage = CustomDialogStage.createStage(
      title = "Save QA Request Template",
      subtitle = Some(s"Save reusable template for ${method.name}"),
      iconCode = Feather.BOOKMARK,
      content = scroll,
      footer = Some(CustomDialogStage.buildFooterBar(cancelBtn, saveBtn)),
      prefWidth = 540,
      prefHeight = 390,
      minWidth = 420,
      minHeight = 320,
      resizable = true
    )

    cancelBtn.setOnAction(_ => stage.close())
    saveBtn.setOnAction { _ =>
      val rawName = nameField.getText.trim
      val finalName = if (rawName.isEmpty) s"${method.name} Template" else rawName
      val (authType, authData) = AuthSerialization.encodePreset(Option(appState.authPreset.get).getOrElse(AuthPreset.None))
      val tags = tagsField.getText.split(",").map(_.trim).filter(_.nonEmpty).distinct.toList
      val expectedOpt = Option(expectedCombo.getValue).map(_.trim).filter(_.nonEmpty)
      val now = System.currentTimeMillis()
      val targetId = existingActive.filter(_ => updateExistingCheck.isSelected).map(_.id).getOrElse(UUID.randomUUID().toString)
      val created = existingActive.filter(_ => updateExistingCheck.isSelected).map(_.createdAt).getOrElse(now)

      savedOpt = Some(ServiceTemplate(
        id = targetId,
        name = finalName,
        description = descField.getText.trim,
        serviceFullName = method.serviceFullName,
        methodName = method.name,
        requestJson = currentJsonPayload,
        authPresetType = authType,
        authPresetData = authData,
        customHeaders = appState.customHeaders.asScala.toList,
        tags = tags,
        expectedStatus = expectedOpt,
        folderPath = folderField.getText.trim,
        createdAt = created,
        updatedAt = now
      ))
      stage.close()
    }

    stage.showAndWait()
    savedOpt.foreach { tpl =>
      workspaceService.saveTemplate(tpl)
      workspaceService.setLastTemplateForMethod(method.fullMethodName, Some(tpl.id))
      appState.templates.setAll(workspaceService.getAllTemplates.asJava)
      appState.activeTemplate.set(Some(tpl))
      appState.statusText.set(s"Saved template '${tpl.name}'.")
    }
  }

  private def showCodeExportDialog(): Unit = {
    (appState.activeTarget.get, currentMethodSchema) match {
      case (Some(target), Some(method)) =>
        val vars = appState.activeEnvVariables
        val resolvedPayload = VariableInterpolator.interpolateString(currentJsonPayload, vars)
        val resolvedStreamChunks = streamScriptPane.currentScriptChunks.map(c => VariableInterpolator.interpolateString(c, vars))
        val resolvedHeaders = appState.getResolvedHeaders
        val serviceOpt = appState.discoveredServices.asScala.find(_.fullName == method.serviceFullName)
        CodeExportDialog.show(
          target = target,
          method = method,
          service = serviceOpt,
          resolvedPayload = resolvedPayload,
          resolvedHeaders = resolvedHeaders,
          callOptions = currentCallOptions,
          streamPayloads = resolvedStreamChunks,
          onCopied = msg => appState.statusText.set(msg)
        )
      case _ =>
        appState.statusText.set("Connect to a target and select a method first.")
    }
  }

  private def copyAsGrpcurl(): Unit = {
    (appState.activeTarget.get, currentMethodSchema) match {
      case (Some(target), Some(method)) =>
        val vars = appState.activeEnvVariables
        val resolvedPayload = VariableInterpolator.interpolateString(currentJsonPayload, vars)
        val resolvedHeaders = appState.getResolvedHeaders
        val cmd = GrpcurlFormatter.format(target, method, resolvedPayload, resolvedHeaders)

        val clipboard = Clipboard.getSystemClipboard
        val content = new ClipboardContent()
        content.putString(cmd)
        clipboard.setContent(content)
        appState.statusText.set(s"Copied grpcurl command for ${method.name}.")
      case _ =>
        appState.statusText.set("Connect to a target and select a method first.")
    }
  }

  private def executeRequest(): Unit = {
    val methodOpt = appState.selectedMethod.get
    val targetOpt = appState.activeTarget.get

    (targetOpt, methodOpt) match {
      case (Some(target), Some(method)) =>
        val rawJson = currentJsonPayload
        if (workspaceService.getSettings.autoSavePayloads) {
          workspaceService.saveMethodPayload(method.fullMethodName, rawJson)
        }
        val vars = appState.activeEnvVariables
        val interpolatedPayload = VariableInterpolator.interpolate(rawJson, vars)
        val rawPreset = Option(appState.authPreset.get).getOrElse(AuthPreset.None)
        val resolvedPreset = VariableInterpolator.interpolatePreset(rawPreset, vars)
        val customHdrs = VariableInterpolator.interpolateHeaders(appState.customHeaders.asScala.toList.filter(_.enabled), vars)
        val missingVars = (interpolatedPayload.unresolvedKeys ++
          VariableInterpolator.findUnresolved(customHdrs.map(_.value), vars)).distinct
        if (missingVars.nonEmpty) {
          syncWarnLabel.setText(s"Unresolved environment variable(s): ${missingVars.map(v => s"{{$v}}").mkString(", ")}")
          syncWarnBanner.setVisible(true)
          syncWarnBanner.setManaged(true)
        } else {
          syncWarnBanner.setVisible(false)
          syncWarnBanner.setManaged(false)
        }

        val requestJson = interpolatedPayload.value
        val maxMb = workspaceService.getSettings.maxInboundMessageMb

        def dispatchWithHeaders(finalHeaders: List[MetadataHeader]): Unit = {
          connectionService.connect(target, maxMb) match {
            case Right(channel) =>
              val opts = currentCallOptions
              method.pattern match {
                case RpcPattern.Unary =>
                  executeUnary(channel, target, method, requestJson, finalHeaders, opts)
                case RpcPattern.ServerStreaming =>
                  executeServerStreaming(channel, target, method, requestJson, finalHeaders, opts)
                case RpcPattern.ClientStreaming =>
                  executeClientStreaming(channel, target, method, finalHeaders, opts)
                case RpcPattern.BidirectionalStreaming =>
                  executeBidiStreaming(channel, target, method, finalHeaders, opts)
              }
            case Left(err) =>
              appState.statusText.set(s"Cannot connect: ${err.message}")
          }
        }

        if (resolvedPreset.requiresDynamicResolution && dynamicAuthPort.isDefined) {
          appState.statusText.set(s"Resolving ${resolvedPreset.presetType} credentials...")
          primaryActionBtn.setDisable(true)
          scala.concurrent.Future(dynamicAuthPort.get.resolveHeaders(resolvedPreset)).foreach { res =>
            Platform.runLater { () =>
              primaryActionBtn.setDisable(false)
              res match {
                case Right(dynHeaders) => dispatchWithHeaders(dynHeaders ++ customHdrs)
                case Left(authErr) =>
                  appState.statusText.set(s"Auth error: ${authErr.message}")
                  CustomDialogStage.showErrorAlert("Authentication Failed", authErr.message)
              }
            }
          }
        } else {
          dispatchWithHeaders(resolvedPreset.toHeaders ++ customHdrs)
        }

      case _ =>
        appState.statusText.set("Select a method and introspect/connect to a target before sending.")
    }
  }

  private def recordCallInHistory(
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    statusCode: Int,
    statusDesc: String,
    latencyMs: Long,
    responseBody: String
  ): Unit = {
    val (authType, authData) = AuthSerialization.encodePreset(Option(appState.authPreset.get).getOrElse(AuthPreset.None))
    val custom = appState.customHeaders.asScala.toList
    val fp = CallFingerprint.compute(
      target.name,
      target.useTls,
      method.fullMethodName,
      requestJson,
      authType,
      authData,
      custom
    )
    val rawBody = Option(responseBody).getOrElse("")
    val preview = rawBody.take(300)
    val boundedBody = rawBody.take(131072)
    val entry = CallHistoryEntry(
      id = UUID.randomUUID().toString,
      fingerprint = fp,
      endpoint = target.name,
      useTls = target.useTls,
      serviceFullName = method.serviceFullName,
      methodName = method.name,
      requestJson = requestJson,
      authPresetType = authType,
      authPresetData = authData,
      customHeaders = custom,
      statusCode = statusCode,
      statusDescription = statusDesc,
      latencyMs = latencyMs,
      responsePreview = preview,
      responseBodyJson = boundedBody
    )
    val updated = workspaceService.recordCallHistory(entry)
    appState.callHistory.setAll(updated.asJava)
  }

  private def deliverCallResult(originTabId: String, call: RpcCall): Unit = {
    if (originTabId == activeSessionId) {
      appState.activeCall.set(Some(call))
      primaryActionBtn.setDisable(false)
    } else {
      resolveSessionById(originTabId).foreach { s =>
        s.lastCall = Some(call)
      }
    }
    onSessionStateChanged()
  }

  private def deliverStreamChunk(originTabId: String, chunk: StreamChunk): Unit = {
    if (originTabId == activeSessionId) {
      responsePane.appendStreamChunk(chunk)
    } else {
      Platform.runLater { () =>
        resolveSessionById(originTabId).foreach { s =>
          val nextIdx = s.streamChunks.size + 1L
          val numbered = if (chunk.index <= s.streamChunks.size) chunk.copy(index = nextIdx) else chunk
          s.streamChunks = s.streamChunks :+ numbered
        }
      }
    }
  }

  private def finishStreamForSession(originTabId: String, call: RpcCall): Unit = {
    if (originTabId == activeSessionId) {
      appState.activeCall.set(Some(call))
      resetStreamState()
    } else {
      resolveSessionById(originTabId).foreach { s =>
        s.lastCall = Some(call)
        s.activeStreamingCallId = None
        s.activeClientStream = None
      }
    }
    onSessionStateChanged()
  }

  private def executeUnary(
    channel: io.grpc.Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    opts: RpcCallOptions
  ): Unit = {
    val originTabId = activeSessionId
    primaryActionBtn.setDisable(true)
    appState.statusText.set(s"Executing unary call ${method.name}...")
    appState.activeCall.set(Some(RpcCall(target = target, method = method, status = RpcCallStatus.InFlight, requestJson = requestJson)))

    invocationService.invokeUnary(channel, target, method, requestJson, headers, opts).onComplete {
      case scala.util.Success(Right(call)) =>
        Platform.runLater { () =>
          deliverCallResult(originTabId, call)
          val (code, desc, lat) = call.status match {
            case RpcCallStatus.Completed(c, d, l) => (c, d, l)
            case RpcCallStatus.Failed(c, d, _)    => (c, d, call.latencyMs.getOrElse(0L))
            case _                                => (0, "OK", call.latencyMs.getOrElse(0L))
          }
          recordCallInHistory(target, method, requestJson, code, desc, lat, call.responseBodyJson.getOrElse(""))
          appState.statusText.set(s"Call completed ($desc) in ${lat}ms")
        }
      case scala.util.Success(Left(err)) =>
        Platform.runLater { () =>
          val failed = RpcCall(
            target = target,
            method = method,
            status = RpcCallStatus.Failed(-1, err.message),
            requestJson = requestJson,
            responseBodyJson = Some(s"""{"error": "${err.message}"}""")
          )
          deliverCallResult(originTabId, failed)
          recordCallInHistory(target, method, requestJson, -1, err.message, 0L, failed.responseBodyJson.getOrElse(""))
          appState.statusText.set(s"Call error: ${err.message}")
        }
      case scala.util.Failure(ex) =>
        Platform.runLater { () =>
          val msg = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
          val failed = RpcCall(
            target = target,
            method = method,
            status = RpcCallStatus.Failed(-1, msg),
            requestJson = requestJson,
            responseBodyJson = Some(s"""{"error": "$msg"}""")
          )
          deliverCallResult(originTabId, failed)
          appState.statusText.set(s"Execution exception: $msg")
        }
    }
  }

  private def executeServerStreaming(
    channel: io.grpc.Channel,
    target: EndpointTarget,
    method: MethodSchema,
    requestJson: String,
    headers: List[MetadataHeader],
    opts: RpcCallOptions
  ): Unit = {
    val originTabId = activeSessionId
    primaryActionBtn.setText("")
    primaryActionBtn.setTooltip(new Tooltip("Cancel Stream"))
    primaryActionIcon.setIconCode(Feather.SQUARE)
    primaryActionBtn.getStyleClass.removeAll("btn-run", "accent")
    primaryActionBtn.getStyleClass.add("danger")

    appState.statusText.set(s"Listening to stream ${method.name}...")
    appState.activeCall.set(Some(RpcCall(target = target, method = method, status = RpcCallStatus.Streaming, requestJson = requestJson)))

    val startTime = System.currentTimeMillis()
    val callId = invocationService.invokeServerStreaming(
      channel = channel,
      target = target,
      method = method,
      requestJson = requestJson,
      headers = headers,
      onChunk = chunk => deliverStreamChunk(originTabId, chunk),
      onComplete = { finalStatus =>
        Platform.runLater { () =>
          val elapsed = System.currentTimeMillis() - startTime
          val existing =
            if (originTabId == activeSessionId) Option(appState.activeCall.get).flatten
            else resolveSessionById(originTabId).flatMap(_.lastCall)
          val updated = existing.map(_.copy(status = finalStatus, latencyMs = Some(elapsed)))
            .getOrElse(RpcCall(target = target, method = method, status = finalStatus, requestJson = requestJson, latencyMs = Some(elapsed)))
          finishStreamForSession(originTabId, updated)
          appState.statusText.set(s"Stream ended in ${elapsed}ms")
        }
      },
      callOptions = opts
    )
    activeStreamingCallId = Some(callId)
    resolveSessionById(originTabId).foreach(_.activeStreamingCallId = Some(callId))
    onSessionStateChanged()
  }

  private def executeClientStreaming(
    channel: io.grpc.Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    opts: RpcCallOptions
  ): Unit = {
    val originTabId = activeSessionId
    primaryActionBtn.setText("")
    primaryActionBtn.setTooltip(new Tooltip("Cancel Stream"))
    primaryActionIcon.setIconCode(Feather.SQUARE)
    primaryActionBtn.getStyleClass.removeAll("btn-run", "accent")
    primaryActionBtn.getStyleClass.add("danger")

    sentChunkCounter.set(0)
    sendChunkBtn.setVisible(true); sendChunkBtn.setManaged(true); sendChunkBtn.setDisable(false)
    completeStreamBtn.setVisible(true); completeStreamBtn.setManaged(true); completeStreamBtn.setDisable(false)

    appState.statusText.set(s"Client stream open for ${method.name}. Send chunks, then click Complete.")
    appState.activeCall.set(Some(RpcCall(target = target, method = method, status = RpcCallStatus.Streaming, requestJson = "[Client Stream Active]")))

    val stream = invocationService.invokeClientStreaming(
      channel = channel,
      target = target,
      method = method,
      headers = headers,
      onComplete = {
        case Right(call) =>
          Platform.runLater { () =>
            finishStreamForSession(originTabId, call)
            appState.statusText.set(s"Client stream finished in ${call.latencyMs.getOrElse(0)}ms")
          }
        case Left(err) =>
          Platform.runLater { () =>
            val failed = RpcCall(
              target = target,
              method = method,
              status = RpcCallStatus.Failed(-1, err.message),
              requestJson = "[Client Stream Failed]",
              responseBodyJson = Some(s"""{"error": "${err.message}"}""")
            )
            finishStreamForSession(originTabId, failed)
            appState.statusText.set(s"Client stream error: ${err.message}")
          }
      },
      callOptions = opts
    )
    activeClientStream = Some(stream)
    resolveSessionById(originTabId).foreach(_.activeClientStream = Some(stream))
    onSessionStateChanged()
  }

  private def executeBidiStreaming(
    channel: io.grpc.Channel,
    target: EndpointTarget,
    method: MethodSchema,
    headers: List[MetadataHeader],
    opts: RpcCallOptions
  ): Unit = {
    val originTabId = activeSessionId
    primaryActionBtn.setText("")
    primaryActionBtn.setTooltip(new Tooltip("Cancel Stream"))
    primaryActionIcon.setIconCode(Feather.SQUARE)
    primaryActionBtn.getStyleClass.removeAll("btn-run", "accent")
    primaryActionBtn.getStyleClass.add("danger")

    sentChunkCounter.set(0)
    sendChunkBtn.setVisible(true); sendChunkBtn.setManaged(true); sendChunkBtn.setDisable(false)
    completeStreamBtn.setVisible(true); completeStreamBtn.setManaged(true); completeStreamBtn.setDisable(false)

    appState.statusText.set(s"Bidi stream active for ${method.name}. Send chunks and observe responses.")
    appState.activeCall.set(Some(RpcCall(target = target, method = method, status = RpcCallStatus.Streaming, requestJson = "[Bidirectional Stream Active]")))

    val startTime = System.currentTimeMillis()
    val stream = invocationService.invokeBidiStreaming(
      channel = channel,
      target = target,
      method = method,
      headers = headers,
      onChunk = chunk => deliverStreamChunk(originTabId, chunk),
      onComplete = { finalStatus =>
        Platform.runLater { () =>
          val elapsed = System.currentTimeMillis() - startTime
          val existing =
            if (originTabId == activeSessionId) Option(appState.activeCall.get).flatten
            else resolveSessionById(originTabId).flatMap(_.lastCall)
          val updated = existing.map(_.copy(status = finalStatus, latencyMs = Some(elapsed)))
            .getOrElse(RpcCall(target = target, method = method, status = finalStatus, requestJson = "[Bidi Stream]", latencyMs = Some(elapsed)))
          finishStreamForSession(originTabId, updated)
          appState.statusText.set(s"Bidi stream ended in ${elapsed}ms")
        }
      },
      callOptions = opts
    )
    activeClientStream = Some(stream)
    resolveSessionById(originTabId).foreach(_.activeClientStream = Some(stream))
    onSessionStateChanged()
  }
}
