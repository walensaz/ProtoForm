package net.protoform.ui.views

import atlantafx.base.controls.ToggleSwitch
import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.application.WorkspaceService
import net.protoform.core.workspace.AppSettings
import net.protoform.infrastructure.storage.LocalJsonWorkspaceRepository
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.awt.Desktop
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object SettingsDialog {

  private val AllCategoriesLabel = "All Settings"

  private case class SettingRowEntry(
    category: String,
    title: String,
    keywords: String,
    rowNode: HBox
  )

  private case class CategoryGroup(
    title: String,
    sectionBox: VBox,
    rows: ListBuffer[SettingRowEntry]
  )

  def show(
    currentSettings: AppSettings,
    workspaceService: WorkspaceService,
    workspaceRepo: LocalJsonWorkspaceRepository,
    onResetLayout: () => Unit,
    onStatusMessage: String => Unit
  ): Option[AppSettings] = {
    var result: Option[AppSettings] = None

    val searchField = new TextField()
    searchField.setPromptText("Search all settings (e.g. defaults, tls, deadline, gzip, theme, layout)...")
    searchField.getStyleClass.add("settings-search-box")

    val categories = ListBuffer.empty[CategoryGroup]

    def addCategory(title: String): CategoryGroup = {
      val box = new VBox(8)
      box.getStyleClass.add("elevated-card")
      val lbl = new Label(title)
      lbl.getStyleClass.add("section-title")
      box.getChildren.add(lbl)
      val grp = CategoryGroup(title, box, ListBuffer.empty)
      categories += grp
      grp
    }

    def addRow(grp: CategoryGroup, title: String, subtitle: String, keywords: String, control: Node): Unit = {
      val row = new HBox(16)
      row.setAlignment(Pos.CENTER_LEFT)
      row.getStyleClass.add("settings-row")

      val textCol = new VBox(3)
      textCol.setMinWidth(180)
      val titleLbl = new Label(title)
      titleLbl.getStyleClass.add("form-label")
      titleLbl.setWrapText(true)
      val subLbl = new Label(subtitle)
      subLbl.getStyleClass.add("form-subtext")
      subLbl.setWrapText(true)
      textCol.getChildren.addAll(titleLbl, subLbl)
      HBox.setHgrow(textCol, Priority.ALWAYS)

      row.getChildren.addAll(textCol, control)
      grp.sectionBox.getChildren.add(row)
      grp.rows += SettingRowEntry(grp.title, s"$title $subtitle", keywords, row)
    }

    // --- 1. General & Serialization ---
    val genGroup = addCategory("General & Serialization")

    val showDefaultsToggle = new ToggleSwitch()
    showDefaultsToggle.setSelected(currentSettings.showDefaultValues)
    addRow(genGroup, "Show Default Values", "Render and serialize Protobuf default/zero fields (0, false, \"\").", "defaults zero omit wire", showDefaultsToggle)

    val preserveNamesToggle = new ToggleSwitch()
    preserveNamesToggle.setSelected(currentSettings.preserveProtoFieldNames)
    addRow(genGroup, "Preserve Proto Field Names", "Use snake_case proto names instead of lowerCamelCase JSON keys.", "snake_case camelCase field names", preserveNamesToggle)

    val autoFormatToggle = new ToggleSwitch()
    autoFormatToggle.setSelected(currentSettings.autoFormatJson)
    addRow(genGroup, "Auto-Format Request JSON", "Pretty-print JSON payloads when loading methods or templates.", "json format pretty", autoFormatToggle)

    val autoSaveToggle = new ToggleSwitch()
    autoSaveToggle.setSelected(currentSettings.autoSavePayloads)
    addRow(genGroup, "Auto-Save Method Payloads", "Persist per-method request JSON across switches and restarts.", "persist cache payloads", autoSaveToggle)

    val autoSelectMethodToggle = new ToggleSwitch()
    autoSelectMethodToggle.setSelected(currentSettings.autoSelectLastMethod)
    addRow(genGroup, "Restore Last Selected Method", "Automatically re-select your previous RPC method after connecting.", "restore history startup", autoSelectMethodToggle)

    // --- 2. Appearance & Modular Layout ---
    val uiGroup = addCategory("Appearance & Layout")

    val themeCombo = new ComboBox[String]()
    themeCombo.getItems.addAll(net.protoform.ui.theme.ThemeManager.themes: _*)
    themeCombo.setValue(currentSettings.theme)
    addRow(uiGroup, "Color Theme", "Switch between Carbon, Paper, Primer, Nord, and Cupertino themes.", "theme dark light carbon paper", themeCombo)

    val orientationCombo = new ComboBox[String]()
    orientationCombo.getItems.addAll("Side-by-Side (Horizontal)", "Stacked (Vertical)")
    orientationCombo.setValue(if (currentSettings.requestResponseVertical) "Stacked (Vertical)" else "Side-by-Side (Horizontal)")
    addRow(uiGroup, "Request / Response Split", "Arrange Request and Response panes horizontally or stacked vertically.", "layout split vertical horizontal modular", orientationCombo)

    val showNavToggle = new ToggleSwitch()
    showNavToggle.setSelected(currentSettings.showNavPane)
    addRow(uiGroup, "Show Services & Templates Sidebar", "Display the left navigation sidebar.", "sidebar nav pane", showNavToggle)

    val showRespToggle = new ToggleSwitch()
    showRespToggle.setSelected(currentSettings.showResponsePane)
    addRow(uiGroup, "Show Response Pane", "Display the right response viewer pane.", "response pane", showRespToggle)

    val advancedActionsToggle = new ToggleSwitch()
    advancedActionsToggle.setSelected(currentSettings.showAdvancedActions)
    addRow(uiGroup, "Show Advanced Toolbar Actions", "Pin secondary actions (Fill Defaults, Save Template, grpcurl, Diff, Pin, Health, .desc/.proto) inline instead of inside overflow menus.", "advanced compact minimal toolbar buttons overflow", advancedActionsToggle)

    val resetLayoutBtn = new Button("Reset Layout", FontIcon.of(Feather.ROTATE_CCW, 12))
    resetLayoutBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    resetLayoutBtn.setMinWidth(Region.USE_PREF_SIZE)
    resetLayoutBtn.setOnAction { _ =>
      showNavToggle.setSelected(true)
      showRespToggle.setSelected(true)
      orientationCombo.setValue("Side-by-Side (Horizontal)")
      onResetLayout()
    }
    addRow(uiGroup, "Reset Window & Split Ratios", "Restore default 3-pane divider ratios and window layout.", "reset layout dividers", resetLayoutBtn)

    // --- 3. gRPC Transport, Deadlines & TLS ---
    val netGroup = addCategory("gRPC & TLS")

    val deadlineField = new TextField(currentSettings.requestDeadlineSeconds.toString)
    deadlineField.setPrefWidth(90)
    addRow(netGroup, "Request Deadline (seconds)", "Per-call gRPC deadline in seconds (0 = no timeout).", "deadline timeout calloptions", deadlineField)

    val reflectionTimeoutField = new TextField(currentSettings.reflectionTimeoutSeconds.toString)
    reflectionTimeoutField.setPrefWidth(90)
    addRow(netGroup, "Server Reflection Timeout (seconds)", "Timeout for gRPC Server Reflection discovery queries.", "reflection timeout discovery", reflectionTimeoutField)

    val maxInboundField = new TextField(currentSettings.maxInboundMessageMb.toString)
    maxInboundField.setPrefWidth(90)
    addRow(netGroup, "Max Inbound Message Size (MB)", "Maximum response payload size accepted by Netty channel (1-512 MB).", "message size mb buffer", maxInboundField)

    val gzipToggle = new ToggleSwitch()
    gzipToggle.setSelected(currentSettings.useGzipCompression)
    addRow(netGroup, "gzip Compression", "Compress outbound gRPC request payloads using gzip.", "gzip compression", gzipToggle)

    val insecureTlsToggle = new ToggleSwitch()
    insecureTlsToggle.setSelected(currentSettings.insecureSkipTlsVerify)
    addRow(netGroup, "Insecure TLS (Skip Verification)", "Dev/QA only: trust self-signed or mismatched TLS certificates.", "tls ssl insecure cert skip verify", insecureTlsToggle)

    val caPathField = new TextField(currentSettings.customCaCertPath.getOrElse(""))
    caPathField.setPromptText("Optional PEM root CA cert...")
    caPathField.setPrefWidth(190)
    val browseCaBtn = new Button("Browse…", FontIcon.of(Feather.FILE, 12))
    browseCaBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    browseCaBtn.setMinWidth(Region.USE_PREF_SIZE)
    val caBox = new HBox(6, caPathField, browseCaBtn)
    caBox.setAlignment(Pos.CENTER_RIGHT)
    addRow(netGroup, "Custom CA Certificate", "Trust a custom root CA PEM file when connecting over TLS.", "tls ca cert pem", caBox)

    val clientCertField = new TextField(currentSettings.clientCertPath.getOrElse(""))
    clientCertField.setPromptText("Optional mTLS client cert (.pem/.crt)...")
    clientCertField.setPrefWidth(190)
    val browseClientCertBtn = new Button("Browse…", FontIcon.of(Feather.FILE, 12))
    browseClientCertBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    browseClientCertBtn.setMinWidth(Region.USE_PREF_SIZE)
    val clientCertBox = new HBox(6, clientCertField, browseClientCertBtn)
    clientCertBox.setAlignment(Pos.CENTER_RIGHT)
    addRow(netGroup, "Client Certificate (mTLS)", "Client certificate chain PEM file for mutual TLS authentication.", "mtls tls client cert pem", clientCertBox)

    val clientKeyField = new TextField(currentSettings.clientKeyPath.getOrElse(""))
    clientKeyField.setPromptText("Optional PKCS#8 private key (.pem/.key)...")
    clientKeyField.setPrefWidth(190)
    val browseClientKeyBtn = new Button("Browse…", FontIcon.of(Feather.KEY, 12))
    browseClientKeyBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    browseClientKeyBtn.setMinWidth(Region.USE_PREF_SIZE)
    val clientKeyBox = new HBox(6, clientKeyField, browseClientKeyBtn)
    clientKeyBox.setAlignment(Pos.CENTER_RIGHT)
    addRow(netGroup, "Client Private Key (mTLS)", "Unencrypted PKCS#8 PEM private key matching the client certificate.", "mtls tls client key pkcs8 pem", clientKeyBox)

    val protocPathField = new TextField(currentSettings.protocPath.getOrElse(""))
    protocPathField.setPromptText("Auto-detect 'protoc' on PATH...")
    protocPathField.setPrefWidth(190)
    val browseProtocBtn = new Button("Browse…", FontIcon.of(Feather.TERMINAL, 12))
    browseProtocBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    browseProtocBtn.setMinWidth(Region.USE_PREF_SIZE)
    val protocBox = new HBox(6, protocPathField, browseProtocBtn)
    protocBox.setAlignment(Pos.CENTER_RIGHT)
    addRow(netGroup, "protoc Executable Path", "Optional path to protoc binary used when loading raw .proto files.", "protoc proto schema compiler path", protocBox)

    // --- 4. Workspace & Shortcuts ---
    val wsGroup = addCategory("Workspace & Shortcuts")

    val openFolderBtn = new Button("Open Folder", FontIcon.of(Feather.FOLDER, 12))
    openFolderBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    openFolderBtn.setMinWidth(Region.USE_PREF_SIZE)
    openFolderBtn.setOnAction { _ =>
      try {
        val dir = workspaceRepo.configDir.toFile
        if (!dir.exists()) dir.mkdirs()
        if (Desktop.isDesktopSupported) Desktop.getDesktop.open(dir)
      } catch {
        case NonFatal(e) => onStatusMessage(s"Could not open folder: ${e.getMessage}")
      }
    }
    addRow(wsGroup, "Workspace Location", workspaceRepo.configFile.toString, "workspace path folder file", openFolderBtn)

    val exportWsBtn = new Button("Export…", FontIcon.of(Feather.UPLOAD, 12))
    exportWsBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    val importWsBtn = new Button("Import…", FontIcon.of(Feather.DOWNLOAD, 12))
    importWsBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    val backupBox = new HBox(6, exportWsBtn, importWsBtn)
    backupBox.setAlignment(Pos.CENTER_RIGHT)
    addRow(wsGroup, "Backup & Restore Workspace", "Export or import settings, templates, auth profiles, and history.", "export import backup restore", backupBox)

    val clearPayloadsBtn = new Button("Clear Cache", FontIcon.of(Feather.TRASH_2, 12))
    clearPayloadsBtn.getStyleClass.addAll("small", "danger", "interactive-hand")
    clearPayloadsBtn.setMinWidth(Region.USE_PREF_SIZE)
    clearPayloadsBtn.setOnAction { _ =>
      if (CustomDialogStage.showConfirm(
        title = "Clear Payload Cache",
        header = "Clear all cached method payloads?",
        message = "Remembered request JSON across previously visited methods will be removed.",
        confirmLabel = "Clear Cache",
        destructive = true
      )) {
        workspaceService.clearMethodPayloads()
        onStatusMessage("Cleared cached per-method request payloads.")
      }
    }
    addRow(wsGroup, "Method Payload Cache", "Remove remembered request JSON for previously visited methods.", "clear cache payloads", clearPayloadsBtn)

    val shortcutsCard = buildShortcutsCard()
    wsGroup.sectionBox.getChildren.add(shortcutsCard)
    wsGroup.rows += SettingRowEntry(wsGroup.title, "Keyboard Shortcuts Ctrl+Enter Ctrl+K Ctrl+,", "shortcuts hotkeys keyboard", shortcutsCard)

    // --- Left Sidebar Category List ---
    val navItems = AllCategoriesLabel :: categories.map(_.title).toList
    val sidebarList = new ListView[String]()
    sidebarList.getItems.addAll(navItems.asJava)
    sidebarList.getSelectionModel.selectFirst()
    sidebarList.getStyleClass.add("settings-nav-list")
    VBox.setVgrow(sidebarList, Priority.ALWAYS)

    val sidebarPane = new VBox(8, new Label("Categories") { getStyleClass.add("form-subtext") }, sidebarList)
    sidebarPane.getStyleClass.add("settings-sidebar")
    sidebarPane.setPrefWidth(205)
    sidebarPane.setMinWidth(185)
    sidebarPane.setMaxWidth(230)

    val sectionsContainer = new VBox(12)
    sectionsContainer.setPadding(new Insets(2, 6, 10, 2))
    sectionsContainer.getChildren.addAll(categories.map(_.sectionBox).toList: _*)

    val emptySearchLbl = new Label("No matching settings found.")
    emptySearchLbl.getStyleClass.add("form-subtext")
    emptySearchLbl.setVisible(false)
    emptySearchLbl.setManaged(false)

    val scroll = new ScrollPane(new VBox(10, sectionsContainer, emptySearchLbl))
    scroll.setFitToWidth(true)
    VBox.setVgrow(scroll, Priority.ALWAYS)

    val applyFilters = () => {
      val q = Option(searchField.getText).map(_.trim.toLowerCase).getOrElse("")
      val selectedCat = Option(sidebarList.getSelectionModel.getSelectedItem).getOrElse(AllCategoriesLabel)
      var anyVisible = false

      categories.foreach { grp =>
        val categoryMatchesSidebar = q.nonEmpty || selectedCat == AllCategoriesLabel || grp.title == selectedCat
        var grpMatches = false
        grp.rows.foreach { entry =>
          val textMatch = q.isEmpty ||
            entry.title.toLowerCase.contains(q) ||
            entry.keywords.toLowerCase.contains(q) ||
            entry.category.toLowerCase.contains(q)
          val showRow = categoryMatchesSidebar && textMatch
          entry.rowNode.setVisible(showRow)
          entry.rowNode.setManaged(showRow)
          if (showRow) grpMatches = true
        }
        grp.sectionBox.setVisible(grpMatches)
        grp.sectionBox.setManaged(grpMatches)
        if (grpMatches) anyVisible = true
      }
      emptySearchLbl.setVisible(!anyVisible)
      emptySearchLbl.setManaged(!anyVisible)
    }

    searchField.textProperty().addListener((_, _, _) => applyFilters())
    sidebarList.getSelectionModel.selectedItemProperty().addListener((_, _, _) => applyFilters())

    val rightCol = new VBox(12, searchField, scroll)
    rightCol.setPadding(new Insets(12, 14, 12, 14))
    HBox.setHgrow(rightCol, Priority.ALWAYS)

    val bodySplit = new HBox(sidebarPane, rightCol)
    VBox.setVgrow(bodySplit, Priority.ALWAYS)

    // --- Context-Aware Footer ---
    val dirtyStateLbl = new Label("No unsaved changes")
    dirtyStateLbl.getStyleClass.add("form-subtext")

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val saveBtn = new Button("Save Settings", FontIcon.of(Feather.CHECK, 13))
    saveBtn.getStyleClass.addAll("accent", "interactive-hand")
    saveBtn.setDefaultButton(true)
    saveBtn.setDisable(true)

    val footerSpacer = new Region()
    HBox.setHgrow(footerSpacer, Priority.ALWAYS)
    val footer = new HBox(10, dirtyStateLbl, footerSpacer, cancelBtn, saveBtn)
    footer.setAlignment(Pos.CENTER_LEFT)
    footer.setPadding(new Insets(10, 14, 10, 14))
    footer.getStyleClass.add("dialog-footer-bar")

    def buildCandidateSettings(): AppSettings = {
      val deadline = deadlineField.getText.trim.toIntOption.map(Math.max(0, _)).getOrElse(currentSettings.requestDeadlineSeconds)
      val reflTimeout = reflectionTimeoutField.getText.trim.toIntOption.map(v => Math.max(1, v)).getOrElse(currentSettings.reflectionTimeoutSeconds)
      val maxMb = maxInboundField.getText.trim.toIntOption.map(v => Math.max(1, Math.min(512, v))).getOrElse(currentSettings.maxInboundMessageMb)
      val caOpt = Option(caPathField.getText).map(_.trim).filter(_.nonEmpty)
      val clientCertOpt = Option(clientCertField.getText).map(_.trim).filter(_.nonEmpty)
      val clientKeyOpt = Option(clientKeyField.getText).map(_.trim).filter(_.nonEmpty)
      val protocOpt = Option(protocPathField.getText).map(_.trim).filter(_.nonEmpty)

      currentSettings.copy(
        theme = Option(themeCombo.getValue).getOrElse(currentSettings.theme),
        showDefaultValues = showDefaultsToggle.isSelected,
        preserveProtoFieldNames = preserveNamesToggle.isSelected,
        autoFormatJson = autoFormatToggle.isSelected,
        autoSavePayloads = autoSaveToggle.isSelected,
        autoSelectLastMethod = autoSelectMethodToggle.isSelected,
        requestResponseVertical = orientationCombo.getValue == "Stacked (Vertical)",
        showNavPane = showNavToggle.isSelected,
        showResponsePane = showRespToggle.isSelected,
        showAdvancedActions = advancedActionsToggle.isSelected,
        requestDeadlineSeconds = deadline,
        reflectionTimeoutSeconds = reflTimeout,
        maxInboundMessageMb = maxMb,
        useGzipCompression = gzipToggle.isSelected,
        insecureSkipTlsVerify = insecureTlsToggle.isSelected,
        customCaCertPath = caOpt,
        clientCertPath = clientCertOpt,
        clientKeyPath = clientKeyOpt,
        protocPath = protocOpt
      )
    }

    def refreshDirtyState(): Unit = {
      val dirty = buildCandidateSettings() != currentSettings
      saveBtn.setDisable(!dirty)
      dirtyStateLbl.setText(if (dirty) "Unsaved changes" else "No unsaved changes")
      dirtyStateLbl.getStyleClass.removeAll("form-subtext", "status-text-connecting")
      dirtyStateLbl.getStyleClass.add(if (dirty) "status-text-connecting" else "form-subtext")
    }

    List(
      showDefaultsToggle.selectedProperty(),
      preserveNamesToggle.selectedProperty(),
      autoFormatToggle.selectedProperty(),
      autoSaveToggle.selectedProperty(),
      autoSelectMethodToggle.selectedProperty(),
      showNavToggle.selectedProperty(),
      showRespToggle.selectedProperty(),
      advancedActionsToggle.selectedProperty(),
      gzipToggle.selectedProperty(),
      insecureTlsToggle.selectedProperty()
    ).foreach(_.addListener((_, _, _) => refreshDirtyState()))

    themeCombo.valueProperty().addListener((_, _, _) => refreshDirtyState())
    orientationCombo.valueProperty().addListener((_, _, _) => refreshDirtyState())
    List(deadlineField, reflectionTimeoutField, maxInboundField, caPathField, clientCertField, clientKeyField, protocPathField).foreach { tf =>
      tf.textProperty().addListener((_, _, _) => refreshDirtyState())
    }

    val stage = CustomDialogStage.createStage(
      title = "Settings",
      subtitle = Some("Configure serialization, layout, gRPC transport, TLS, and workspace persistence"),
      iconCode = Feather.SLIDERS,
      content = bodySplit,
      footer = Some(footer),
      prefWidth = 900,
      prefHeight = 650,
      minWidth = 680,
      minHeight = 480,
      resizable = true
    )

    def bindFileBrowse(btn: Button, title: String, targetField: TextField): Unit = {
      btn.setOnAction { _ =>
        val chooser = new FileChooser()
        chooser.setTitle(title)
        Option(chooser.showOpenDialog(stage)).foreach { file =>
          targetField.setText(file.getAbsolutePath)
        }
      }
    }

    bindFileBrowse(browseCaBtn, "Select Custom CA Certificate (.pem / .crt)", caPathField)
    bindFileBrowse(browseClientCertBtn, "Select mTLS Client Certificate (.pem / .crt)", clientCertField)
    bindFileBrowse(browseClientKeyBtn, "Select mTLS Client Private Key (.pem / .key)", clientKeyField)
    bindFileBrowse(browseProtocBtn, "Select protoc Executable", protocPathField)

    exportWsBtn.setOnAction { _ =>
      val chooser = new FileChooser()
      chooser.setTitle("Export ProtoForm Workspace")
      chooser.setInitialFileName("protoform-workspace.json")
      Option(chooser.showSaveDialog(stage)).foreach { file =>
        val json = workspaceRepo.exportWorkspaceJson(workspaceService.getData)
        Files.write(file.toPath, json.getBytes(StandardCharsets.UTF_8))
        onStatusMessage(s"Exported workspace to ${file.getName}")
      }
    }

    importWsBtn.setOnAction { _ =>
      val chooser = new FileChooser()
      chooser.setTitle("Import ProtoForm Workspace")
      Option(chooser.showOpenDialog(stage)).foreach { file =>
        val content = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
        workspaceRepo.importWorkspaceJson(content) match {
          case Right(imported) =>
            workspaceService.replaceWorkspace(imported)
            onStatusMessage(s"Imported workspace from ${file.getName}.")
          case Left(err) =>
            onStatusMessage(s"Workspace import failed: ${err.message}")
        }
      }
    }

    cancelBtn.setOnAction(_ => stage.close())
    saveBtn.setOnAction { _ =>
      result = Some(buildCandidateSettings())
      stage.close()
    }

    stage.showAndWait()
    result
  }

  private def buildShortcutsCard(): HBox = {
    val titleLbl = new Label("Keyboard Shortcuts")
    titleLbl.getStyleClass.add("form-label")

    val rows = new VBox(
      7,
      shortcutRow("Ctrl + Enter", "Send unary request, start stream, or dispatch client stream chunk"),
      shortcutRow("Ctrl + K", "Open Quick Jump palette (methods, QA templates, history, and Suite Runner)"),
      shortcutRow("Ctrl + B", "Toggle left Services / Templates / History drawer"),
      shortcutRow("Ctrl + 1 / 2 / 3", "Open Services, QA Templates, or Call History in the left drawer"),
      shortcutRow("Ctrl + L", "Focus target endpoint address bar"),
      shortcutRow("Ctrl + ,", "Open searchable Settings dialog")
    )

    val col = new VBox(8, titleLbl, rows)
    HBox.setHgrow(col, Priority.ALWAYS)
    val wrapper = new HBox(col)
    wrapper.getStyleClass.add("settings-row")
    wrapper
  }

  private def shortcutRow(keys: String, description: String): HBox = {
    val keyLbl = new Label(keys)
    keyLbl.getStyleClass.add("keycap-pill")
    keyLbl.setMinWidth(105)
    keyLbl.setAlignment(Pos.CENTER)

    val descLbl = new Label(description)
    descLbl.getStyleClass.add("form-subtext")
    descLbl.setWrapText(true)
    HBox.setHgrow(descLbl, Priority.ALWAYS)

    val row = new HBox(12, keyLbl, descLbl)
    row.setAlignment(Pos.CENTER_LEFT)
    row
  }
}
