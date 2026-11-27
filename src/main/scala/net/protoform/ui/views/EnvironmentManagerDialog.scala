package net.protoform.ui.views

import atlantafx.base.controls.ToggleSwitch
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.application.WorkspaceService
import net.protoform.core.auth.AuthProfile
import net.protoform.core.workspace.EnvironmentConfig
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

object EnvironmentManagerDialog {

  def show(
    workspaceService: WorkspaceService,
    authProfiles: List[AuthProfile],
    initialSelectedId: Option[String]
  ): Option[Option[String]] = {
    var selectedResult: Option[Option[String]] = None
    val workingEnvs = ListBuffer.from(workspaceService.getEnvironments)
    var currentActiveId: Option[String] = initialSelectedId.filter(id => workingEnvs.exists(_.id == id))

    val envListView = new ListView[EnvironmentConfig]()
    envListView.setPrefWidth(210)
    envListView.setCellFactory { _ =>
      new ListCell[EnvironmentConfig]() {
        override def updateItem(item: EnvironmentConfig, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) {
            setText(null)
          } else {
            setText(s"${item.name}  (${item.endpoint})")
          }
        }
      }
    }
    VBox.setVgrow(envListView, Priority.ALWAYS)

    val addEnvBtn = new Button("New", FontIcon.of(Feather.PLUS, 12))
    addEnvBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
    val presetsBtn = new Button("Presets", FontIcon.of(Feather.LAYERS, 12))
    presetsBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    presetsBtn.setTooltip(new Tooltip("Add local / staging / qa / prod starter environments"))
    val delEnvBtn = new Button("", FontIcon.of(Feather.TRASH_2, 12))
    delEnvBtn.getStyleClass.addAll("small", "danger", "interactive-hand")

    val leftToolbar = new HBox(6, addEnvBtn, presetsBtn, delEnvBtn)
    val leftPane = new VBox(8, new Label("Environments") { getStyleClass.add("form-label") }, envListView, leftToolbar)
    leftPane.setPadding(new Insets(12))
    leftPane.getStyleClass.add("settings-sidebar")

    // Right Editor Fields
    val nameField = new TextField()
    nameField.setPromptText("e.g. staging, qa, prod")
    val endpointField = new TextField()
    endpointField.setPromptText("e.g. api.staging.internal:443 or {{host}}:50051")
    val tlsToggle = new ToggleSwitch()
    val insecureToggle = new ToggleSwitch()

    val caField = new TextField()
    caField.setPromptText("Optional CA PEM...")
    val caBrowseBtn = new Button("Browse…", FontIcon.of(Feather.FILE, 11))
    caBrowseBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    HBox.setHgrow(caField, Priority.ALWAYS)
    val caRow = new HBox(6, caField, caBrowseBtn)

    val certField = new TextField()
    certField.setPromptText("Optional mTLS client cert...")
    val certBrowseBtn = new Button("Browse…", FontIcon.of(Feather.FILE, 11))
    certBrowseBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    HBox.setHgrow(certField, Priority.ALWAYS)
    val certRow = new HBox(6, certField, certBrowseBtn)

    val keyField = new TextField()
    keyField.setPromptText("Optional mTLS PKCS#8 private key...")
    val keyBrowseBtn = new Button("Browse…", FontIcon.of(Feather.KEY, 11))
    keyBrowseBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    HBox.setHgrow(keyField, Priority.ALWAYS)
    val keyRow = new HBox(6, keyField, keyBrowseBtn)

    val authCombo = new ComboBox[String]()
    authCombo.setMaxWidth(Double.MaxValue)
    val noProfileLabel = "[Keep Current Auth]"
    authCombo.getItems.add(noProfileLabel)
    authCombo.getItems.addAll(authProfiles.map(_.name).asJava)
    authCombo.setValue(noProfileLabel)

    val grid = new GridPane()
    grid.setHgap(12)
    grid.setVgap(8)
    val c1 = new ColumnConstraints(); c1.setMinWidth(130)
    val c2 = new ColumnConstraints(); c2.setHgrow(Priority.ALWAYS)
    grid.getColumnConstraints.addAll(c1, c2)

    grid.addRow(0, new Label("Name:"), nameField)
    grid.addRow(1, new Label("Endpoint:"), endpointField)
    grid.addRow(2, new Label("Use TLS:"), tlsToggle)
    grid.addRow(3, new Label("Insecure TLS:"), insecureToggle)
    grid.addRow(4, new Label("CA Certificate:"), caRow)
    grid.addRow(5, new Label("Client Cert (mTLS):"), certRow)
    grid.addRow(6, new Label("Client Key (mTLS):"), keyRow)
    grid.addRow(7, new Label("Auth Profile:"), authCombo)

    // Variables Editor
    val varsContainer = new VBox(6)
    val addVarBtn = new Button("Add Variable", FontIcon.of(Feather.PLUS, 12))
    addVarBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    val varRows = ListBuffer.empty[(TextField, TextField, HBox)]

    val varsCard = new VBox(
      8,
      new Label("Variables ({{key}} interpolation across endpoint, headers, auth & JSON)") { getStyleClass.add("form-label") },
      varsContainer,
      addVarBtn
    )
    varsCard.getStyleClass.add("elevated-card")

    val editorBox = new VBox(12, grid, varsCard)
    editorBox.setPadding(new Insets(12))
    val editorScroll = new ScrollPane(editorBox)
    editorScroll.setFitToWidth(true)
    HBox.setHgrow(editorScroll, Priority.ALWAYS)

    val emptyHint = new Label("Select or create an environment on the left.")
    emptyHint.getStyleClass.add("form-subtext")
    val rightStack = new StackPane(emptyHint, editorScroll)
    HBox.setHgrow(rightStack, Priority.ALWAYS)

    var suppressingUi = false
    var editingEnvId: Option[String] = None

    def addVarRow(key: String, value: String): Unit = {
      val kField = new TextField(key)
      kField.setPromptText("var_name")
      kField.setPrefWidth(150)
      val vField = new TextField(value)
      vField.setPromptText("value")
      HBox.setHgrow(vField, Priority.ALWAYS)
      val rmBtn = new Button("", FontIcon.of(Feather.X, 11))
      rmBtn.getStyleClass.addAll("small", "danger", "interactive-hand")
      val row = new HBox(6, kField, new Label("="), vField, rmBtn)
      row.setAlignment(Pos.CENTER_LEFT)
      val entry = (kField, vField, row)
      varRows += entry
      varsContainer.getChildren.add(row)
      rmBtn.setOnAction { _ =>
        varRows -= entry
        varsContainer.getChildren.remove(row)
        commitCurrentEdits()
      }
      kField.textProperty().addListener((_, _, _) => if (!suppressingUi) commitCurrentEdits())
      vField.textProperty().addListener((_, _, _) => if (!suppressingUi) commitCurrentEdits())
    }

    def commitCurrentEdits(): Unit = {
      editingEnvId.foreach { id =>
        val idx = workingEnvs.indexWhere(_.id == id)
        if (idx >= 0) {
          val profId = Option(authCombo.getValue)
            .filter(_ != noProfileLabel)
            .flatMap(name => authProfiles.find(_.name == name).map(_.id))
          val varsMap = varRows.flatMap { case (k, v, _) =>
            val key = Option(k.getText).map(_.trim).getOrElse("")
            if (key.nonEmpty) Some(key -> Option(v.getText).getOrElse("")) else None
          }.toMap
          val updated = workingEnvs(idx).copy(
            name = Option(nameField.getText).map(_.trim).filter(_.nonEmpty).getOrElse("unnamed"),
            endpoint = Option(endpointField.getText).map(_.trim).getOrElse("localhost:50051"),
            useTls = tlsToggle.isSelected,
            insecureSkipTlsVerify = insecureToggle.isSelected,
            customCaCertPath = Option(caField.getText).map(_.trim).filter(_.nonEmpty),
            clientCertPath = Option(certField.getText).map(_.trim).filter(_.nonEmpty),
            clientKeyPath = Option(keyField.getText).map(_.trim).filter(_.nonEmpty),
            authProfileId = profId,
            variables = varsMap
          )
          workingEnvs.update(idx, updated)
          envListView.refresh()
        }
      }
    }

    def loadIntoEditor(envOpt: Option[EnvironmentConfig]): Unit = {
      suppressingUi = true
      try {
        envOpt match {
          case Some(env) =>
            editingEnvId = Some(env.id)
            editorScroll.setVisible(true)
            emptyHint.setVisible(false)
            delEnvBtn.setDisable(false)
            nameField.setText(env.name)
            endpointField.setText(env.endpoint)
            tlsToggle.setSelected(env.useTls)
            insecureToggle.setSelected(env.insecureSkipTlsVerify)
            caField.setText(env.customCaCertPath.getOrElse(""))
            certField.setText(env.clientCertPath.getOrElse(""))
            keyField.setText(env.clientKeyPath.getOrElse(""))
            val profName = env.authProfileId.flatMap(id => authProfiles.find(_.id == id).map(_.name)).getOrElse(noProfileLabel)
            authCombo.setValue(profName)
            varRows.clear()
            varsContainer.getChildren.clear()
            env.variables.toList.sortBy(_._1).foreach { case (k, v) => addVarRow(k, v) }
          case None =>
            editingEnvId = None
            editorScroll.setVisible(false)
            emptyHint.setVisible(true)
            delEnvBtn.setDisable(true)
        }
      } finally {
        suppressingUi = false
      }
    }

    def refreshList(selectId: Option[String]): Unit = {
      envListView.getItems.setAll(workingEnvs.asJava)
      val toSelect = selectId.flatMap(id => workingEnvs.find(_.id == id)).orElse(workingEnvs.headOption)
      toSelect match {
        case Some(env) => envListView.getSelectionModel.select(env)
        case None      => loadIntoEditor(None)
      }
    }

    envListView.getSelectionModel.selectedItemProperty().addListener { (_, _, selected) =>
      loadIntoEditor(Option(selected))
    }

    List(nameField, endpointField, caField, certField, keyField).foreach { tf =>
      tf.textProperty().addListener((_, _, _) => if (!suppressingUi) commitCurrentEdits())
    }
    tlsToggle.selectedProperty().addListener((_, _, _) => if (!suppressingUi) commitCurrentEdits())
    insecureToggle.selectedProperty().addListener((_, _, _) => if (!suppressingUi) commitCurrentEdits())
    authCombo.valueProperty().addListener((_, _, _) => if (!suppressingUi) commitCurrentEdits())
    addVarBtn.setOnAction(_ => addVarRow("", ""))

    addEnvBtn.setOnAction { _ =>
      val newEnv = EnvironmentConfig(
        id = UUID.randomUUID().toString,
        name = s"env-${workingEnvs.size + 1}",
        endpoint = "localhost:50051",
        variables = Map("host" -> "localhost")
      )
      workingEnvs += newEnv
      currentActiveId = Some(newEnv.id)
      refreshList(Some(newEnv.id))
    }

    presetsBtn.setOnAction { _ =>
      val starters = List(
        ("local", "localhost:50051", false),
        ("staging", "staging.api.internal:443", true),
        ("qa", "qa.api.internal:443", true),
        ("prod", "api.example.com:443", true)
      )
      val existingNames = workingEnvs.map(_.name.toLowerCase).toSet
      var lastAdded: Option[String] = None
      starters.foreach { case (n, ep, tls) =>
        if (!existingNames.contains(n)) {
          val env = EnvironmentConfig(id = UUID.randomUUID().toString, name = n, endpoint = ep, useTls = tls)
          workingEnvs += env
          lastAdded = Some(env.id)
        }
      }
      refreshList(lastAdded.orElse(editingEnvId))
    }

    delEnvBtn.setOnAction { _ =>
      Option(envListView.getSelectionModel.getSelectedItem).foreach { sel =>
        if (CustomDialogStage.showConfirm(
          title = "Delete Environment",
          header = s"Delete environment '${sel.name}'?",
          message = "This environment configuration and its variables will be removed.",
          confirmLabel = "Delete",
          destructive = true
        )) {
          workingEnvs -= sel
          if (currentActiveId.contains(sel.id)) currentActiveId = None
          refreshList(workingEnvs.headOption.map(_.id))
        }
      }
    }

    val body = new HBox(leftPane, rightStack)
    VBox.setVgrow(body, Priority.ALWAYS)

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val saveBtn = new Button("Save & Apply", FontIcon.of(Feather.CHECK, 12))
    saveBtn.getStyleClass.addAll("accent", "interactive-hand")
    saveBtn.setDefaultButton(true)

    val stage = CustomDialogStage.createStage(
      title = "Manage Environments",
      subtitle = Some("Configure named environments (local, staging, qa, prod) with TLS, auth profiles, and {{var}} variables"),
      iconCode = Feather.GLOBE,
      content = body,
      footer = Some(CustomDialogStage.buildFooterBar(cancelBtn, saveBtn)),
      prefWidth = 860,
      prefHeight = 580,
      minWidth = 640,
      minHeight = 440,
      resizable = true
    )

    def bindBrowse(btn: Button, title: String, tf: TextField): Unit = {
      btn.setOnAction { _ =>
        val chooser = new FileChooser()
        chooser.setTitle(title)
        Option(chooser.showOpenDialog(stage)).foreach(f => tf.setText(f.getAbsolutePath))
      }
    }
    bindBrowse(caBrowseBtn, "Select CA Certificate", caField)
    bindBrowse(certBrowseBtn, "Select Client Certificate", certField)
    bindBrowse(keyBrowseBtn, "Select Client Private Key", keyField)

    refreshList(currentActiveId)

    cancelBtn.setOnAction(_ => stage.close())
    saveBtn.setOnAction { _ =>
      commitCurrentEdits()
      val existingIds = workspaceService.getEnvironments.map(_.id).toSet
      val nextIds = workingEnvs.map(_.id).toSet
      existingIds.diff(nextIds).foreach(workspaceService.deleteEnvironment)
      workingEnvs.foreach(workspaceService.saveEnvironment)
      val chosenActive = editingEnvId.orElse(currentActiveId).filter(nextIds.contains)
      selectedResult = Some(chosenActive)
      stage.close()
    }

    stage.showAndWait()
    selectedResult
  }
}
