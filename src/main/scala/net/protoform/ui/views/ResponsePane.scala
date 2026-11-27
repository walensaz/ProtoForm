package net.protoform.ui.views

import javafx.application.Platform
import javafx.beans.property.{SimpleLongProperty, SimpleStringProperty}
import javafx.collections.FXCollections
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout._
import net.protoform.core.call.{RpcCall, RpcCallStatus, StreamChunk, StreamDirection}
import net.protoform.ui.editor.JsonCodeArea
import net.protoform.ui.state.{AppState, PinnedResponse, WorkbenchTabSession}
import net.protoform.ui.util.TabDragSupport
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._

class ResponsePane(
  appState: AppState,
  onPopOutRequested: () => Unit = () => ()
) extends VBox(8) {
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
  setPadding(new Insets(10))
  setMinWidth(300)

  private var popOutVisible: Boolean = true

  // Header Card: Single-row clean summary + optional advanced row
  val statusBadge = new Label("Ready")
  statusBadge.getStyleClass.addAll("status-badge")
  statusBadge.setMinWidth(Region.USE_PREF_SIZE)

  val latencyLabel = new Label("")
  latencyLabel.getStyleClass.add("latency-pill")

  val sizeLabel = new Label("")
  sizeLabel.getStyleClass.add("info-strip")

  val headerSpacer = new Region()
  HBox.setHgrow(headerSpacer, Priority.ALWAYS)

  private def copyCurrentBody(): Unit = {
    copyToClipboard(bodyCodeArea.getText)
    appState.statusText.set("Copied response body to clipboard.")
  }

  private def pinCurrentResponse(): Unit = {
    val body = bodyCodeArea.getText
    if (body != null && body.trim.nonEmpty) {
      val callOpt = Option(appState.activeCall.get).flatten
      val label = callOpt.map(_.method.name).getOrElse("Pinned Response")
      val methodFull = callOpt.map(_.method.fullMethodName).getOrElse("")
      val lat = callOpt.flatMap(_.latencyMs)
      appState.pinnedBaseline.set(Some(PinnedResponse(label, methodFull, body, lat, statusBadge.getText)))
      appState.statusText.set(s"Pinned '$label' as baseline response.")
    }
  }

  private def clearStreamLog(): Unit = {
    streamTable.getItems.clear()
    TabDragSupport.setTabTitle(streamTab, "Stream Log (0)")
  }

  // Clean Mode (Compact Icon Controls + Overflow Menu)
  val copyIconBtn = new Button("", FontIcon.of(Feather.COPY, 13))
  copyIconBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  copyIconBtn.setMinWidth(Region.USE_PREF_SIZE)
  copyIconBtn.setTooltip(new Tooltip("Copy Response Body"))
  copyIconBtn.setOnAction(_ => copyCurrentBody())

  val popOutIconBtn = new Button("", FontIcon.of(Feather.EXTERNAL_LINK, 13))
  popOutIconBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  popOutIconBtn.setMinWidth(Region.USE_PREF_SIZE)
  popOutIconBtn.setTooltip(new Tooltip("Pop out Response Pane into floating window"))
  popOutIconBtn.setOnAction(_ => onPopOutRequested())

  val pinMenuItem = new MenuItem("Pin as Baseline", FontIcon.of(Feather.BOOKMARK, 13))
  pinMenuItem.setOnAction(_ => pinCurrentResponse())
  val diffMenuItem = new MenuItem("Compare / Diff with Baseline…", FontIcon.of(Feather.COLUMNS, 13))
  diffMenuItem.setOnAction(_ => ResponseDiffDialog.show(appState))
  val clearStreamMenuItem = new MenuItem("Clear Stream Log", FontIcon.of(Feather.TRASH_2, 13))
  clearStreamMenuItem.setOnAction(_ => clearStreamLog())

  val moreRespBtn = new MenuButton("", FontIcon.of(Feather.MORE_HORIZONTAL, 13))
  moreRespBtn.getItems.addAll(pinMenuItem, diffMenuItem, new SeparatorMenuItem(), clearStreamMenuItem)
  moreRespBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "no-caret", "interactive-hand")
  moreRespBtn.setMinWidth(Region.USE_PREF_SIZE)
  moreRespBtn.setTooltip(new Tooltip("Pin, Diff & Stream Actions"))

  val cleanActionsBox = new HBox(6, copyIconBtn, popOutIconBtn, moreRespBtn)
  cleanActionsBox.setAlignment(Pos.CENTER_RIGHT)

  val topHeaderRow = new HBox(8, statusBadge, latencyLabel, sizeLabel, headerSpacer, cleanActionsBox)
  topHeaderRow.setAlignment(Pos.CENTER_LEFT)

  // Advanced Mode (Expanded Inline Buttons)
  val clearStreamBtn = new Button("Clear Stream", FontIcon.of(Feather.TRASH_2, 13))
  clearStreamBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  clearStreamBtn.setMinWidth(Region.USE_PREF_SIZE)
  clearStreamBtn.setOnAction(_ => clearStreamLog())

  val copyButton = new Button("Copy Body", FontIcon.of(Feather.COPY, 13))
  copyButton.getStyleClass.addAll("small", "subtle", "interactive-hand")
  copyButton.setMinWidth(Region.USE_PREF_SIZE)
  copyButton.setOnAction(_ => copyCurrentBody())

  val pinBaselineBtn = new Button("Pin", FontIcon.of(Feather.BOOKMARK, 13))
  pinBaselineBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  pinBaselineBtn.setMinWidth(Region.USE_PREF_SIZE)
  pinBaselineBtn.setTooltip(new Tooltip("Pin current response as baseline for Response Diff"))
  pinBaselineBtn.setOnAction(_ => pinCurrentResponse())

  val diffBtn = new Button("Diff", FontIcon.of(Feather.COLUMNS, 13))
  diffBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  diffBtn.setMinWidth(Region.USE_PREF_SIZE)
  diffBtn.setTooltip(new Tooltip("Open Side-by-Side Response Diff Viewer"))
  diffBtn.setOnAction(_ => ResponseDiffDialog.show(appState))

  val popOutBtn = new Button("", FontIcon.of(Feather.EXTERNAL_LINK, 13))
  popOutBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  popOutBtn.setMinWidth(Region.USE_PREF_SIZE)
  popOutBtn.setTooltip(new Tooltip("Pop out Response Pane into floating window"))
  popOutBtn.setOnAction(_ => onPopOutRequested())

  val actionsGroup = new FlowPane(6, 6)
  actionsGroup.setAlignment(Pos.CENTER_LEFT)
  actionsGroup.getChildren.addAll(clearStreamBtn, copyButton, pinBaselineBtn, diffBtn, popOutBtn)

  val headerBar = new VBox(8, topHeaderRow, actionsGroup)
  headerBar.getStyleClass.addAll("elevated-card", "pane-header-bar")

  // Error Summary Banner (prevents long gRPC error descriptions from overflowing headerBar)
  private val errorTitleLbl = new Label("")
  errorTitleLbl.getStyleClass.add("response-error-title")
  private val errorSpacer = new Region()
  HBox.setHgrow(errorSpacer, Priority.ALWAYS)
  private val copyErrorBtn = new Button("", FontIcon.of(Feather.COPY, 11))
  copyErrorBtn.getStyleClass.addAll("row-delete-btn", "interactive-hand")
  copyErrorBtn.setTooltip(new Tooltip("Copy Error Details"))
  private val closeErrorBtn = new Button("", FontIcon.of(Feather.X, 11))
  closeErrorBtn.getStyleClass.addAll("row-delete-btn", "interactive-hand")
  closeErrorBtn.setTooltip(new Tooltip("Dismiss Error Banner"))
  private val errorHeaderRow = new HBox(6, FontIcon.of(Feather.ALERT_CIRCLE, 13), errorTitleLbl, errorSpacer, copyErrorBtn, closeErrorBtn)
  errorHeaderRow.setAlignment(Pos.CENTER_LEFT)
  private val errorMsgLbl = new Label("")
  errorMsgLbl.getStyleClass.add("response-error-msg")
  errorMsgLbl.setWrapText(true)
  copyErrorBtn.setOnAction(_ => copyToClipboard(s"${errorTitleLbl.getText}: ${errorMsgLbl.getText}"))
  closeErrorBtn.setOnAction { _ =>
    errorBanner.setVisible(false)
    errorBanner.setManaged(false)
  }

  val errorBanner = new VBox(4, errorHeaderRow, errorMsgLbl)
  errorBanner.getStyleClass.add("response-error-banner")
  errorBanner.setVisible(false)
  errorBanner.setManaged(false)

  // Tab Pane
  val tabPane = new TabPane()
  VBox.setVgrow(tabPane, Priority.ALWAYS)

  // Tab 1: Body
  val bodyCodeArea = new JsonCodeArea(editable = false)
  private val emptyBodyIcon = FontIcon.of(Feather.INBOX, 22)
  emptyBodyIcon.getStyleClass.add("empty-state-icon")
  private val emptyBodyTitle = new Label("No Response Yet")
  emptyBodyTitle.getStyleClass.add("empty-state-title")
  private val emptyBodyDesc = new Label("Run a gRPC request (Ctrl+Enter) to inspect the JSON response, headers, and trailers.")
  emptyBodyDesc.getStyleClass.add("form-subtext")
  emptyBodyDesc.setWrapText(true)
  emptyBodyDesc.setTextAlignment(javafx.scene.text.TextAlignment.CENTER)
  emptyBodyDesc.setMaxWidth(340)
  private val emptyBodyBox = new VBox(8, emptyBodyIcon, emptyBodyTitle, emptyBodyDesc)
  emptyBodyBox.setAlignment(Pos.CENTER)
  emptyBodyBox.setMouseTransparent(true)

  val bodyContainer = new StackPane(bodyCodeArea, emptyBodyBox)
  bodyCodeArea.codeArea.textProperty().addListener { (_, _, txt) =>
    val empty = txt == null || txt.isEmpty
    emptyBodyBox.setVisible(empty)
  }
  val bodyTab = new Tab("Response Body", bodyContainer)
  bodyTab.setClosable(false)
  TabDragSupport.initDraggableTab(bodyTab, "resp-body", "Response Body")

  // Tab 2: Headers & Trailers
  case class ResponseHeaderRow(kind: String, key: String, value: String)

  val headersTable = new TableView[ResponseHeaderRow]()
  headersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
  headersTable.setPlaceholder(new Label("No response headers or trailers yet."))

  val colKind = new TableColumn[ResponseHeaderRow, String]("Kind")
  colKind.setCellValueFactory(d => new SimpleStringProperty(d.getValue.kind))
  colKind.setMinWidth(78)
  colKind.setPrefWidth(84)
  colKind.setMaxWidth(98)
  colKind.setCellFactory { _ =>
    new TableCell[ResponseHeaderRow, String]() {
      private val pill = new Label()
      override def updateItem(item: String, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        setText(null)
        if (empty || item == null) {
          setGraphic(null)
        } else {
          pill.setText(item)
          pill.getStyleClass.removeAll("badge-field-num", "badge-default", "tag-pill")
          if (item == "TRAILER") {
            pill.getStyleClass.add("tag-pill")
          } else {
            pill.getStyleClass.addAll("badge-field-num", "badge-default")
          }
          setGraphic(pill)
        }
      }
    }
  }

  val colKey = new TableColumn[ResponseHeaderRow, String]("Header Key")
  colKey.setCellValueFactory(d => new SimpleStringProperty(d.getValue.key))
  colKey.getStyleClass.add("mono-table-cell")
  colKey.setPrefWidth(190)

  val colVal = new TableColumn[ResponseHeaderRow, String]("Value")
  colVal.setCellValueFactory(d => new SimpleStringProperty(d.getValue.value))
  colVal.getStyleClass.add("mono-table-cell")
  colVal.setPrefWidth(230)

  headersTable.getColumns.addAll(colKind, colKey, colVal)
  setupHeadersTableCopyActions()
  val headersTab = new Tab("Headers & Trailers", headersTable)
  headersTab.setClosable(false)
  TabDragSupport.initDraggableTab(headersTab, "resp-headers", "Headers & Trailers")

  // Tab 3: Master-Detail Stream Timeline
  val autoFollowCheck = new CheckBox("Auto-Follow")
  autoFollowCheck.setSelected(true)
  autoFollowCheck.getStyleClass.add("interactive-hand")

  val streamClearInlineBtn = new Button("Clear", FontIcon.of(Feather.TRASH_2, 11))
  streamClearInlineBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  streamClearInlineBtn.setOnAction(_ => clearStreamLog())

  val streamHintLbl = new Label("Click any chunk row to inspect its payload in Response Body.")
  streamHintLbl.getStyleClass.add("form-subtext")
  val streamBarSpacer = new Region()
  HBox.setHgrow(streamBarSpacer, Priority.ALWAYS)
  val streamToolbar = new HBox(10, streamHintLbl, streamBarSpacer, autoFollowCheck, streamClearInlineBtn)
  streamToolbar.setAlignment(Pos.CENTER_LEFT)
  streamToolbar.setPadding(new Insets(0, 0, 4, 0))

  val streamTable = new TableView[StreamChunk]()
  streamTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
  streamTable.setPlaceholder(new Label("No stream chunks yet."))
  VBox.setVgrow(streamTable, Priority.ALWAYS)

  val colIdx = new TableColumn[StreamChunk, java.lang.Long]("#")
  colIdx.setCellValueFactory(d => new SimpleLongProperty(d.getValue.index).asObject())
  colIdx.getStyleClass.add("mono-table-cell")
  colIdx.setPrefWidth(42)

  val colDir = new TableColumn[StreamChunk, String]("Dir")
  colDir.setCellValueFactory { d =>
    val text = d.getValue.direction match {
      case StreamDirection.Sent     => "↑ SENT"
      case StreamDirection.Received => "↓ RECV"
    }
    new SimpleStringProperty(text)
  }
  colDir.setPrefWidth(78)
  colDir.setCellFactory { _ =>
    new TableCell[StreamChunk, String]() {
      private val pill = new Label()
      pill.getStyleClass.add("rpc-pill")
      override def updateItem(item: String, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        setText(null)
        if (empty || item == null) {
          setGraphic(null)
        } else {
          pill.setText(item)
          pill.getStyleClass.removeAll("rpc-pill-client", "rpc-pill-server")
          pill.getStyleClass.add(if (item.contains("SENT")) "rpc-pill-client" else "rpc-pill-server")
          setGraphic(pill)
        }
      }
    }
  }

  val colTime = new TableColumn[StreamChunk, String]("Time (Δ)")
  colTime.setCellValueFactory { d =>
    val chunk = d.getValue
    val items = streamTable.getItems
    val idx = items.indexOf(chunk)
    val deltaStr = if (idx > 0) {
      val prev = items.get(idx - 1)
      s" (+${Math.max(0L, chunk.timestampMs - prev.timestampMs)}ms)"
    } else ""
    val formatted = timeFormatter.format(Instant.ofEpochMilli(chunk.timestampMs))
    new SimpleStringProperty(s"$formatted$deltaStr")
  }
  colTime.getStyleClass.add("mono-table-cell")
  colTime.setPrefWidth(155)

  val colPayload = new TableColumn[StreamChunk, String]("Chunk Payload")
  colPayload.setCellValueFactory(d => new SimpleStringProperty(d.getValue.payloadJson.replaceAll("\\s+", " ").trim))
  colPayload.getStyleClass.add("mono-table-cell")
  colPayload.setPrefWidth(220)

  streamTable.getColumns.addAll(colIdx, colDir, colTime, colPayload)

  streamTable.getSelectionModel.selectedItemProperty().addListener { (_, _, chunk) =>
    if (chunk != null) {
      bodyCodeArea.setText(chunk.payloadJson)
      updateBodySize(chunk.payloadJson)
    }
  }

  val streamBox = new VBox(4, streamToolbar, streamTable)
  val streamTab = new Tab("Stream Log (0)", streamBox)
  streamTab.setClosable(false)
  TabDragSupport.initDraggableTab(streamTab, "resp-stream", "Stream Log (0)")

  tabPane.getTabs.addAll(bodyTab, headersTab, streamTab)
  getChildren.addAll(headerBar, errorBanner, tabPane)

  private def applyAdvancedMode(advanced: Boolean): Unit = {
    cleanActionsBox.setVisible(!advanced)
    cleanActionsBox.setManaged(!advanced)
    actionsGroup.setVisible(advanced)
    actionsGroup.setManaged(advanced)
    popOutIconBtn.setVisible(popOutVisible && !advanced)
    popOutIconBtn.setManaged(popOutVisible && !advanced)
    popOutBtn.setVisible(popOutVisible && advanced)
    popOutBtn.setManaged(popOutVisible && advanced)
  }

  applyAdvancedMode(appState.showAdvancedActions.get)
  appState.showAdvancedActions.addListener { (_, _, advanced) =>
    Platform.runLater(() => applyAdvancedMode(advanced))
  }

  appState.activeCall.addListener { (_, _, newCallOpt) =>
    Platform.runLater { () =>
      newCallOpt match {
        case Some(call) => displayCall(call)
        case None       => clear()
      }
    }
  }

  def setPopOutButtonVisible(visible: Boolean): Unit = {
    popOutVisible = visible
    applyAdvancedMode(appState.showAdvancedActions.get)
  }

  def captureIntoSession(session: WorkbenchTabSession): Unit = {
    session.lastCall = Option(appState.activeCall.get).flatten
    session.streamChunks = streamTable.getItems.asScala.toList
  }

  def restoreFromSession(session: WorkbenchTabSession): Unit = {
    appState.activeCall.set(session.lastCall)
    session.lastCall match {
      case Some(call) => displayCall(call)
      case None       => clear()
    }
    streamTable.getItems.setAll(session.streamChunks: _*)
    TabDragSupport.setTabTitle(streamTab, s"Stream Log (${session.streamChunks.size})")
  }

  def displayCall(call: RpcCall): Unit = {
    call.status match {
      case RpcCallStatus.Completed(code, desc, latency) =>
        statusBadge.setText(s"gRPC: $code $desc")
        statusBadge.getStyleClass.removeAll("status-badge-error")
        statusBadge.getStyleClass.add("status-badge-ok")
        latencyLabel.setText(s"${latency}ms")
        errorBanner.setVisible(false); errorBanner.setManaged(false)

      case RpcCallStatus.Failed(code, desc, _) =>
        statusBadge.setText(if (code >= 0) s"ERR: $code" else "ERR: FAILED")
        statusBadge.getStyleClass.removeAll("status-badge-ok")
        statusBadge.getStyleClass.add("status-badge-error")
        latencyLabel.setText(call.latencyMs.map(l => s"${l}ms").getOrElse(""))
        errorTitleLbl.setText(if (code >= 0) s"gRPC Status $code" else "Invocation Error")
        errorMsgLbl.setText(Option(desc).filter(_.trim.nonEmpty).getOrElse("Unknown RPC failure"))
        errorBanner.setVisible(true); errorBanner.setManaged(true)

      case RpcCallStatus.InFlight =>
        statusBadge.setText("In Flight...")
        statusBadge.getStyleClass.removeAll("status-badge-ok", "status-badge-error")
        latencyLabel.setText("")
        errorBanner.setVisible(false); errorBanner.setManaged(false)

      case RpcCallStatus.Streaming =>
        statusBadge.setText("Streaming Active...")
        statusBadge.getStyleClass.removeAll("status-badge-error")
        statusBadge.getStyleClass.add("status-badge-ok")
        latencyLabel.setText("")
        errorBanner.setVisible(false); errorBanner.setManaged(false)

      case _ => ()
    }

    call.responseBodyJson.foreach { body =>
      bodyCodeArea.setText(body)
      updateBodySize(body)
    }
    if (call.responseBodyJson.isEmpty && call.status == RpcCallStatus.InFlight) {
      bodyCodeArea.clear()
      sizeLabel.setText("")
    }

    val allHeaders = (
      call.responseHeaders.map(h => ResponseHeaderRow("HEADER", h._1, h._2)) ++
      call.responseTrailers.map(t => ResponseHeaderRow("TRAILER", t._1, t._2))
    ).toList
    headersTable.setItems(FXCollections.observableArrayList(allHeaders: _*))

    if (call.streamMessages.nonEmpty) {
      streamTable.setItems(FXCollections.observableArrayList(call.streamMessages: _*))
      TabDragSupport.setTabTitle(streamTab, s"Stream Log (${call.streamMessages.size})")
    }
  }

  def appendStreamChunk(chunk: StreamChunk): Unit = {
    Platform.runLater { () =>
      val existingCount = streamTable.getItems.size()
      val numbered = if (chunk.index <= existingCount) chunk.copy(index = existingCount + 1L) else chunk
      streamTable.getItems.add(numbered)
      TabDragSupport.setTabTitle(streamTab, s"Stream Log (${streamTable.getItems.size()})")
      if (autoFollowCheck.isSelected) {
        streamTable.scrollTo(streamTable.getItems.size() - 1)
        if (numbered.direction == StreamDirection.Received || bodyCodeArea.getText.isEmpty) {
          bodyCodeArea.setText(numbered.payloadJson)
          updateBodySize(numbered.payloadJson)
        }
      }
    }
  }

  private def updateBodySize(text: String): Unit = {
    val bytes = Option(text).map(_.getBytes(StandardCharsets.UTF_8).length).getOrElse(0)
    val formatted =
      if (bytes <= 0) ""
      else if (bytes < 1024) s"$bytes B"
      else f"${bytes / 1024.0}%.1f KB"
    sizeLabel.setText(formatted)
  }

  private def clear(): Unit = {
    statusBadge.setText("Ready")
    statusBadge.getStyleClass.removeAll("status-badge-ok", "status-badge-error")
    errorBanner.setVisible(false); errorBanner.setManaged(false)
    latencyLabel.setText("")
    sizeLabel.setText("")
    bodyCodeArea.clear()
    headersTable.getItems.clear()
    streamTable.getItems.clear()
    TabDragSupport.setTabTitle(streamTab, "Stream Log (0)")
  }

  private def setupHeadersTableCopyActions(): Unit = {
    val copyPairItem = new MenuItem("Copy Header (key: value)", FontIcon.of(Feather.COPY, 12))
    copyPairItem.setOnAction { _ =>
      Option(headersTable.getSelectionModel.getSelectedItem).foreach { row =>
        copyToClipboard(s"${row.key}: ${row.value}")
        appState.statusText.set(s"Copied '${row.key}' header.")
      }
    }
    val copyKeyItem = new MenuItem("Copy Key", FontIcon.of(Feather.KEY, 12))
    copyKeyItem.setOnAction { _ =>
      Option(headersTable.getSelectionModel.getSelectedItem).foreach { row =>
        copyToClipboard(row.key)
        appState.statusText.set(s"Copied key '${row.key}'.")
      }
    }
    val copyValItem = new MenuItem("Copy Value", FontIcon.of(Feather.CLIPBOARD, 12))
    copyValItem.setOnAction { _ =>
      Option(headersTable.getSelectionModel.getSelectedItem).foreach { row =>
        copyToClipboard(row.value)
        appState.statusText.set(s"Copied value for '${row.key}'.")
      }
    }
    headersTable.setContextMenu(new ContextMenu(copyPairItem, copyKeyItem, copyValItem))
    headersTable.setRowFactory { _ =>
      val row = new TableRow[ResponseHeaderRow]()
      row.setOnMouseClicked { ev =>
        if (ev.getClickCount == 2 && !row.isEmpty) {
          val item = row.getItem
          copyToClipboard(s"${item.key}: ${item.value}")
          appState.statusText.set(s"Copied '${item.key}: ${item.value}'.")
        }
      }
      row
    }
  }

  private def copyToClipboard(text: String): Unit = {
    val clipboard = Clipboard.getSystemClipboard
    val content = new ClipboardContent()
    content.putString(text)
    clipboard.setContent(content)
  }
}
