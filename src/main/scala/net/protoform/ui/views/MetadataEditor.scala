package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.application.WorkspaceService
import net.protoform.core.auth.{AuthPreset, AuthProfile, MetadataHeader}
import net.protoform.core.workspace.AuthSerialization
import net.protoform.ports.auth.DynamicAuthTokenPort
import net.protoform.ui.state.AppState
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

class MetadataEditor(
  appState: AppState,
  workspaceService: WorkspaceService,
  dynamicAuthPort: Option[DynamicAuthTokenPort] = None
) extends VBox(10) {
  setPadding(new Insets(0))

  // Section 0: Reusable Auth Store / Profiles
  val profileSection = new VBox(8)
  profileSection.getStyleClass.add("elevated-card")
  val profileLabel = new Label("Reusable Auth Store")
  profileLabel.getStyleClass.add("section-title")

  val profileBar = new HBox(8)
  profileBar.setAlignment(Pos.CENTER_LEFT)

  val profileCombo = new ComboBox[String]()
  profileCombo.setPromptText("Select Auth Profile...")
  profileCombo.getStyleClass.add("interactive-hand")
  HBox.setHgrow(profileCombo, Priority.ALWAYS)

  val saveProfileBtn = new Button("Save Profile", FontIcon.of(Feather.SAVE, 13))
  saveProfileBtn.getStyleClass.addAll("subtle", "interactive-hand")

  val deleteProfileBtn = new Button("Delete", FontIcon.of(Feather.TRASH_2, 13))
  deleteProfileBtn.getStyleClass.addAll("danger", "interactive-hand")
  deleteProfileBtn.setDisable(true)

  profileBar.getChildren.addAll(profileCombo, saveProfileBtn, deleteProfileBtn)
  profileSection.getChildren.addAll(profileLabel, profileBar)

  // Section 1: Auth Preset
  val authSection = new VBox(8)
  authSection.getStyleClass.add("elevated-card")
  val authLabel = new Label("Authentication Mode")
  authLabel.getStyleClass.add("section-title")

  val presetCombo = new ComboBox[String]()
  presetCombo.getItems.addAll("None", "API Key", "Bearer Token", "Basic Auth", "Google Cloud", "Signed JWT")
  presetCombo.setValue("None")
  presetCombo.getStyleClass.add("interactive-hand")

  val presetInputsContainer = new VBox(6)
  authSection.getChildren.addAll(authLabel, presetCombo, presetInputsContainer)

  // Section 2: Custom Headers Table
  val headersSection = new VBox(8)
  headersSection.getStyleClass.add("elevated-card")
  val headersLabel = new Label("Custom gRPC Metadata Headers")
  headersLabel.getStyleClass.add("section-title")

  val headersContainer = new VBox(6)
  val addHeaderBtn = new Button("Add Header", FontIcon.of(Feather.PLUS, 13))
  addHeaderBtn.getStyleClass.addAll("subtle", "interactive-hand")
  addHeaderBtn.setOnAction { _ =>
    addHeaderRow("", "", enabled = true)
  }

  headersSection.getChildren.addAll(headersLabel, headersContainer, addHeaderBtn)
  VBox.setVgrow(headersSection, Priority.ALWAYS)

  val scrollWrapper = new ScrollPane(new VBox(12, profileSection, authSection, headersSection))
  scrollWrapper.setFitToWidth(true)
  VBox.setVgrow(scrollWrapper, Priority.ALWAYS)
  getChildren.add(scrollWrapper)

  private var isUpdatingUi = false

  refreshProfiles()
  restoreSavedSessionAuth()

  appState.authProfiles.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: AuthProfile]) =>
    refreshProfiles()
  }

  private def profileDisplayLabel(p: AuthProfile): String = {
    if (p.isGlobal) s"${p.name} [Global]" else p.name
  }

  private def findProfileByDisplayLabel(label: String): Option[AuthProfile] = {
    workspaceService.getAuthProfiles.find(p => profileDisplayLabel(p) == label || p.name == label)
  }

  profileCombo.valueProperty().addListener { (_, _, newVal) =>
    if (!isUpdatingUi && newVal != null) {
      if (newVal == "[Custom / Unsaved]") {
        deleteProfileBtn.setDisable(true)
        appState.selectedAuthProfile.set(None)
        persistSessionAuth()
      } else {
        findProfileByDisplayLabel(newVal).foreach { p =>
          deleteProfileBtn.setDisable(false)
          appState.selectedAuthProfile.set(Some(p))
          applyAuthStateToUi(p.preset, p.customHeaders)
          persistSessionAuth()
        }
      }
    }
  }

  saveProfileBtn.setOnAction(_ => showSaveProfileDialog())

  deleteProfileBtn.setOnAction { _ =>
    val selectedName = profileCombo.getValue
    if (selectedName != null && selectedName != "[Custom / Unsaved]") {
      findProfileByDisplayLabel(selectedName).foreach { p =>
        if (CustomDialogStage.showConfirm(
          title = "Delete Auth Profile",
          header = s"Delete '$selectedName'?",
          message = "This saved authentication profile will be removed from your Auth Store.",
          confirmLabel = "Delete",
          destructive = true
        )) {
          workspaceService.deleteAuthProfile(p.id)
          appState.authProfiles.remove(p)
          refreshProfiles()
          profileCombo.setValue("[Custom / Unsaved]")
          deleteProfileBtn.setDisable(true)
          appState.statusText.set(s"Deleted Auth Profile '$selectedName'.")
        }
      }
    }
  }

  presetCombo.valueProperty().addListener { (_, _, newVal) =>
    if (!isUpdatingUi) {
      buildPresetInputs(newVal match {
        case "API Key"      => AuthPreset.ApiKey("x-api-key", "")
        case "Bearer Token" => AuthPreset.BearerToken("")
        case "Basic Auth"   => AuthPreset.BasicAuth("", "")
        case "Google Cloud" => AuthPreset.GoogleCloud()
        case "Signed JWT"   => AuthPreset.SignedJwt()
        case _              => AuthPreset.None
      })
      persistSessionAuth()
    }
  }

  def syncFromExternalAuth(preset: AuthPreset, headers: List[MetadataHeader]): Unit = {
    applyAuthStateToUi(preset, headers)
    isUpdatingUi = true
    try {
      profileCombo.setValue("[Custom / Unsaved]")
      deleteProfileBtn.setDisable(true)
    } finally {
      isUpdatingUi = false
    }
    persistSessionAuth()
  }

  def refreshForProjectChange(): Unit = {
    refreshProfiles()
    restoreSavedSessionAuth()
  }

  private def restoreSavedSessionAuth(): Unit = {
    val data = workspaceService.getData
    val proj = workspaceService.getActiveProject
    val lastAuthId = proj.lastAuthProfileId.orElse(data.settings.lastAuthProfileId)
    val restoredProfile = lastAuthId.flatMap(id => data.authProfiles.find(_.id == id))
    restoredProfile match {
      case Some(p) =>
        isUpdatingUi = true
        try {
          profileCombo.setValue(profileDisplayLabel(p))
          deleteProfileBtn.setDisable(false)
          appState.selectedAuthProfile.set(Some(p))
        } finally {
          isUpdatingUi = false
        }
        applyAuthStateToUi(p.preset, p.customHeaders)
      case None =>
        isUpdatingUi = true
        try {
          profileCombo.setValue("[Custom / Unsaved]")
          deleteProfileBtn.setDisable(true)
          appState.selectedAuthProfile.set(None)
        } finally {
          isUpdatingUi = false
        }
        val preset = AuthSerialization.decodePreset(data.sessionAuthPresetType, data.sessionAuthPresetData)
        applyAuthStateToUi(preset, data.sessionCustomHeaders)
    }
  }

  private def createAuthGrid(): GridPane = {
    val grid = new GridPane()
    grid.setHgap(10)
    grid.setVgap(8)
    val labelCol = new ColumnConstraints()
    labelCol.setMinWidth(96)
    val inputCol = new ColumnConstraints()
    inputCol.setHgrow(Priority.ALWAYS)
    grid.getColumnConstraints.addAll(labelCol, inputCol)
    grid
  }

  private def buildPresetInputs(preset: AuthPreset): Unit = {
    presetInputsContainer.getChildren.clear()
    preset match {
      case AuthPreset.ApiKey(h, k) =>
        val headerField = new TextField(h)
        val keyField = new TextField(k)
        val update = () => {
          appState.authPreset.set(AuthPreset.ApiKey(headerField.getText, keyField.getText))
          if (!isUpdatingUi) persistSessionAuth()
        }
        headerField.textProperty().addListener((_, _, _) => update())
        keyField.textProperty().addListener((_, _, _) => update())
        val grid = createAuthGrid()
        grid.addRow(0, new Label("Header Name:"), headerField)
        grid.addRow(1, new Label("API Key:"), keyField)
        presetInputsContainer.getChildren.add(grid)
        appState.authPreset.set(preset)

      case AuthPreset.BearerToken(t) =>
        val tokenField = new TextField(t)
        tokenField.setPromptText("Bearer Token / JWT")
        tokenField.textProperty().addListener { (_, _, tok) =>
          appState.authPreset.set(AuthPreset.BearerToken(tok))
          if (!isUpdatingUi) persistSessionAuth()
        }
        val grid = createAuthGrid()
        grid.addRow(0, new Label("Token:"), tokenField)
        presetInputsContainer.getChildren.add(grid)
        appState.authPreset.set(preset)

      case AuthPreset.BasicAuth(u, pass) =>
        val userField = new TextField(u)
        val passField = new PasswordField()
        passField.setText(pass)
        val update = () => {
          appState.authPreset.set(AuthPreset.BasicAuth(userField.getText, passField.getText))
          if (!isUpdatingUi) persistSessionAuth()
        }
        userField.textProperty().addListener((_, _, _) => update())
        passField.textProperty().addListener((_, _, _) => update())
        val grid = createAuthGrid()
        grid.addRow(0, new Label("Username:"), userField)
        grid.addRow(1, new Label("Password:"), passField)
        presetInputsContainer.getChildren.add(grid)
        appState.authPreset.set(preset)

      case g: AuthPreset.GoogleCloud =>
        val modeCombo = new ComboBox[String]()
        modeCombo.getItems.addAll("AccessToken", "IdToken")
        modeCombo.setValue(if (g.tokenMode.equalsIgnoreCase("IdToken")) "IdToken" else "AccessToken")
        modeCombo.setMaxWidth(Double.MaxValue)

        val saField = new TextField(g.serviceAccountJsonPath.getOrElse(""))
        saField.setPromptText("Optional Service Account JSON (leave blank for ADC / GCE metadata)")
        HBox.setHgrow(saField, Priority.ALWAYS)
        val saBrowse = new Button("Browse…", FontIcon.of(Feather.FILE, 11))
        saBrowse.getStyleClass.addAll("small", "subtle", "interactive-hand")
        saBrowse.setOnAction { _ =>
          val ch = new FileChooser()
          ch.setTitle("Select GCP Service Account JSON")
          Option(ch.showOpenDialog(Option(getScene).map(_.getWindow).orNull)).foreach(f => saField.setText(f.getAbsolutePath))
        }
        val saRow = new HBox(6, saField, saBrowse)

        val audField = new TextField(g.audience.getOrElse(""))
        audField.setPromptText("Target audience URL (required for ID Token / Cloud Run / IAP)")
        val scopesField = new TextField(g.scopes.mkString(", "))
        scopesField.setPromptText("Comma-separated OAuth2 scopes")
        val testStatus = new Label("")
        testStatus.getStyleClass.add("form-subtext")
        testStatus.setWrapText(true)
        val testBtn = new Button("Test Token", FontIcon.of(Feather.CHECK_CIRCLE, 11))
        testBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")

        val currentGcp = () => AuthPreset.GoogleCloud(
          tokenMode = Option(modeCombo.getValue).getOrElse("AccessToken"),
          serviceAccountJsonPath = Option(saField.getText).map(_.trim).filter(_.nonEmpty),
          audience = Option(audField.getText).map(_.trim).filter(_.nonEmpty),
          scopes = Option(scopesField.getText).getOrElse("").split(",").map(_.trim).filter(_.nonEmpty).toList
        )
        val update = () => {
          appState.authPreset.set(currentGcp())
          if (!isUpdatingUi) persistSessionAuth()
        }
        modeCombo.valueProperty().addListener((_, _, _) => update())
        saField.textProperty().addListener((_, _, _) => update())
        audField.textProperty().addListener((_, _, _) => update())
        scopesField.textProperty().addListener((_, _, _) => update())
        testBtn.setOnAction { _ =>
          testStatus.setText("Resolving Google Cloud token...")
          val p = currentGcp()
          Future(dynamicAuthPort.map(_.resolveHeaders(p)).getOrElse(Left(net.protoform.core.error.DomainError.RpcExecutionFailed("Resolver unavailable", -1)))).foreach { res =>
            Platform.runLater { () =>
              res match {
                case Right(hs) => testStatus.setText(s"Token OK (${hs.headOption.map(_.value.take(28) + "…").getOrElse("")})")
                case Left(err) => testStatus.setText(s"Error: ${err.message}")
              }
            }
          }
        }

        val grid = createAuthGrid()
        grid.addRow(0, new Label("Token Mode:"), modeCombo)
        grid.addRow(1, new Label("SA Key JSON:"), saRow)
        grid.addRow(2, new Label("Audience:"), audField)
        grid.addRow(3, new Label("Scopes:"), scopesField)
        grid.addRow(4, testBtn, testStatus)
        presetInputsContainer.getChildren.add(grid)
        appState.authPreset.set(preset)

      case j: AuthPreset.SignedJwt =>
        val algCombo = new ComboBox[String]()
        algCombo.getItems.addAll("HS256", "RS256")
        algCombo.setValue(if (j.algorithm.equalsIgnoreCase("RS256")) "RS256" else "HS256")
        algCombo.setMaxWidth(Double.MaxValue)

        val keyField = new TextField(j.keyOrSecret)
        keyField.setPromptText("HS256 shared secret OR RS256 PKCS#8 PEM / path")
        HBox.setHgrow(keyField, Priority.ALWAYS)
        val keyBrowse = new Button("PEM…", FontIcon.of(Feather.KEY, 11))
        keyBrowse.getStyleClass.addAll("small", "subtle", "interactive-hand")
        keyBrowse.setOnAction { _ =>
          val ch = new FileChooser()
          ch.setTitle("Select PKCS#8 Private Key PEM")
          Option(ch.showOpenDialog(Option(getScene).map(_.getWindow).orNull)).foreach(f => keyField.setText(f.getAbsolutePath))
        }
        val keyRow = new HBox(6, keyField, keyBrowse)

        val issField = new TextField(j.issuer)
        val subField = new TextField(j.subject)
        val audField = new TextField(j.audience)
        val ttlField = new TextField(j.ttlSeconds.toString)
        val claimsField = new TextField(j.customClaimsJson)
        claimsField.setPromptText("""{"role":"admin"}""")

        val testStatus = new Label("")
        testStatus.getStyleClass.add("form-subtext")
        testStatus.setWrapText(true)
        val testBtn = new Button("Preview JWT", FontIcon.of(Feather.CHECK_CIRCLE, 11))
        testBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")

        val currentJwt = () => AuthPreset.SignedJwt(
          algorithm = Option(algCombo.getValue).getOrElse("HS256"),
          keyOrSecret = Option(keyField.getText).getOrElse(""),
          issuer = Option(issField.getText).getOrElse(""),
          subject = Option(subField.getText).getOrElse(""),
          audience = Option(audField.getText).getOrElse(""),
          customClaimsJson = Option(claimsField.getText).filter(_.trim.nonEmpty).getOrElse("{}"),
          ttlSeconds = scala.util.Try(ttlField.getText.trim.toLong).getOrElse(3600L)
        )
        val update = () => {
          appState.authPreset.set(currentJwt())
          if (!isUpdatingUi) persistSessionAuth()
        }
        algCombo.valueProperty().addListener((_, _, _) => update())
        List(keyField, issField, subField, audField, ttlField, claimsField).foreach { tf =>
          tf.textProperty().addListener((_, _, _) => update())
        }
        testBtn.setOnAction { _ =>
          dynamicAuthPort.map(_.resolveHeaders(currentJwt())) match {
            case Some(Right(hs)) => testStatus.setText(s"Signed OK (${hs.headOption.map(_.value.take(32) + "…").getOrElse("")})")
            case Some(Left(err)) => testStatus.setText(s"Error: ${err.message}")
            case None            => testStatus.setText("JWT signer unavailable.")
          }
        }

        val grid = createAuthGrid()
        grid.addRow(0, new Label("Algorithm:"), algCombo)
        grid.addRow(1, new Label("Secret / Key:"), keyRow)
        grid.addRow(2, new Label("Issuer (iss):"), issField)
        grid.addRow(3, new Label("Subject (sub):"), subField)
        grid.addRow(4, new Label("Audience (aud):"), audField)
        grid.addRow(5, new Label("TTL (sec):"), ttlField)
        grid.addRow(6, new Label("Claims JSON:"), claimsField)
        grid.addRow(7, testBtn, testStatus)
        presetInputsContainer.getChildren.add(grid)
        appState.authPreset.set(preset)

      case _ =>
        appState.authPreset.set(AuthPreset.None)
    }
  }

  private def applyAuthStateToUi(preset: AuthPreset, headers: List[MetadataHeader]): Unit = {
    isUpdatingUi = true
    try {
      val modeLabel = preset match {
        case _: AuthPreset.ApiKey      => "API Key"
        case _: AuthPreset.BearerToken => "Bearer Token"
        case _: AuthPreset.BasicAuth   => "Basic Auth"
        case _: AuthPreset.GoogleCloud => "Google Cloud"
        case _: AuthPreset.SignedJwt   => "Signed JWT"
        case _                         => "None"
      }
      presetCombo.setValue(modeLabel)
      buildPresetInputs(preset)

      headersContainer.getChildren.clear()
      headers.foreach(h => addHeaderRow(h.key, h.value, h.enabled))
      syncCustomHeaders(persist = false)
    } finally {
      isUpdatingUi = false
    }
  }

  private def refreshProfiles(): Unit = {
    val current = profileCombo.getValue
    val all = workspaceService.getAuthProfiles
    val names = "[Custom / Unsaved]" :: all.map(profileDisplayLabel)
    profileCombo.getItems.setAll(names.asJava)
    if (current != null && names.contains(current)) {
      profileCombo.setValue(current)
    } else {
      profileCombo.setValue("[Custom / Unsaved]")
    }
  }

  private def showSaveProfileDialog(): Unit = {
    var result: Option[(String, Boolean)] = None
    val nameInput = new TextField("My Auth Profile")
    nameInput.setPrefWidth(340)
    val globalCheck = new CheckBox("Share globally across all projects")
    globalCheck.getStyleClass.add("interactive-hand")
    globalCheck.setTooltip(new Tooltip("If checked, this auth profile will be available in every workspace project"))

    val content = new VBox(10, new Label("Profile Name:"), nameInput, globalCheck)
    content.setPadding(new Insets(16))

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val saveBtn = new Button("Save Profile")
    saveBtn.getStyleClass.addAll("accent", "interactive-hand")
    saveBtn.setDefaultButton(true)

    val stage = CustomDialogStage.createStage(
      title = "Save Auth Profile",
      subtitle = Some("Save current credentials & headers to Auth Store"),
      iconCode = Feather.KEY,
      content = content,
      footer = Some(CustomDialogStage.buildFooterBar(cancelBtn, saveBtn)),
      prefWidth = 440,
      prefHeight = 250,
      minWidth = 360,
      minHeight = 220,
      resizable = false
    )
    val submit = () => {
      result = Option(nameInput.getText).map(_.trim).filter(_.nonEmpty).map(n => (n, globalCheck.isSelected))
      stage.close()
    }
    saveBtn.setOnAction(_ => submit())
    nameInput.setOnAction(_ => submit())
    cancelBtn.setOnAction(_ => stage.close())
    stage.showAndWait()

    result.foreach { case (clean, isGlobal) =>
      val profile = AuthProfile(
        id = UUID.randomUUID().toString,
        name = clean,
        preset = appState.authPreset.get,
        customHeaders = appState.customHeaders.asScala.toList,
        isGlobal = isGlobal
      )
      workspaceService.saveAuthProfile(profile)
      appState.authProfiles.setAll(workspaceService.getAuthProfiles.asJava)
      appState.selectedAuthProfile.set(Some(profile))
      refreshProfiles()
      profileCombo.setValue(profileDisplayLabel(profile))
      deleteProfileBtn.setDisable(false)
      persistSessionAuth()
      appState.statusText.set(s"Saved ${if (isGlobal) "global " else ""}Auth Profile '$clean'.")
    }
  }

  private def addHeaderRow(initialKey: String, initialValue: String, enabled: Boolean): Unit = {
    val row = new HBox(8)
    row.setAlignment(Pos.CENTER_LEFT)

    val check = new CheckBox()
    check.setSelected(enabled)

    val keyField = new TextField(initialKey)
    keyField.setPromptText("Header (e.g. x-request-id)")
    HBox.setHgrow(keyField, Priority.SOMETIMES)

    val valField = new TextField(initialValue)
    valField.setPromptText("Value")
    HBox.setHgrow(valField, Priority.ALWAYS)

    val deleteBtn = new Button("", FontIcon.of(Feather.TRASH_2, 12))
    deleteBtn.getStyleClass.addAll("row-delete-btn", "interactive-hand")
    deleteBtn.setTooltip(new Tooltip("Remove header"))

    val updateList = () => syncCustomHeaders(persist = !isUpdatingUi)
    check.selectedProperty().addListener((_, _, _) => updateList())
    keyField.textProperty().addListener((_, _, _) => updateList())
    valField.textProperty().addListener((_, _, _) => updateList())

    deleteBtn.setOnAction { _ =>
      headersContainer.getChildren.remove(row)
      updateList()
    }

    row.getChildren.addAll(check, keyField, valField, deleteBtn)
    headersContainer.getChildren.add(row)
    syncCustomHeaders(persist = !isUpdatingUi)
  }

  private def syncCustomHeaders(persist: Boolean): Unit = {
    appState.customHeaders.clear()
    headersContainer.getChildren.asScala.foreach {
      case row: HBox =>
        val check = row.getChildren.get(0).asInstanceOf[CheckBox]
        val keyField = row.getChildren.get(1).asInstanceOf[TextField]
        val valField = row.getChildren.get(2).asInstanceOf[TextField]
        val k = keyField.getText.trim
        val v = valField.getText.trim
        if (k.nonEmpty) {
          appState.customHeaders.add(MetadataHeader(k, v, check.isSelected))
        }
      case _ => ()
    }
    if (persist) persistSessionAuth()
  }

  private def persistSessionAuth(): Unit = {
    val profileId = Option(appState.selectedAuthProfile.get).flatten.map(_.id)
    workspaceService.updateSessionAuth(
      Option(appState.authPreset.get).getOrElse(AuthPreset.None),
      appState.customHeaders.asScala.toList,
      profileId
    )
  }
}
