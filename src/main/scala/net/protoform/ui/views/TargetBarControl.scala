package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.application.{ConnectionService, ReflectionService, SchemaService, WorkspaceService}
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, ServiceSchema}
import net.protoform.core.workspace.{EnvironmentConfig, VariableInterpolator}
import net.protoform.ports.infra.KubePortForwardPort
import net.protoform.ui.state.{AppState, ConnectionStatus}
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._

/** Unified 4-segment target pill embedded in CustomTitleBar: [TLS | host:port | Schema | Refresh]. */
class TargetBarControl(
  appState: AppState,
  connectionService: ConnectionService,
  reflectionService: ReflectionService,
  schemaService: SchemaService,
  workspaceService: WorkspaceService,
  kubePortForwardPort: Option[KubePortForwardPort] = None
) extends HBox(0) {
  setAlignment(Pos.CENTER_LEFT)
  getStyleClass.add("target-pill-box")

  private val tlsIcon = FontIcon.of(Feather.UNLOCK, 12)
  val tlsToggle = new ToggleButton("Plain", tlsIcon)
  tlsToggle.getStyleClass.addAll("tls-toggle-btn", "interactive-hand")
  tlsToggle.setMinWidth(Region.USE_PREF_SIZE)
  tlsToggle.setTooltip(new Tooltip("Toggle TLS / Plaintext transport"))
  private val initialProject = workspaceService.getActiveProject
  tlsToggle.setSelected(initialProject.lastUseTls.getOrElse(workspaceService.getSettings.lastUseTls))
  syncTlsVisual(tlsToggle.isSelected)
  tlsToggle.selectedProperty().addListener { (_, _, isTls) =>
    syncTlsVisual(isTls)
  }

  val endpointCombo = new ComboBox[String]()
  endpointCombo.setEditable(true)
  endpointCombo.setPromptText("host:port")
  endpointCombo.getStyleClass.add("target-endpoint-combo")
  val recent = workspaceService.getData.recentEndpoints
  endpointCombo.getItems.addAll(recent.asJava)
  endpointCombo.setValue(initialProject.lastEndpoint.getOrElse(workspaceService.getSettings.lastEndpoint))
  endpointCombo.setMinWidth(155)
  endpointCombo.setPrefWidth(215)
  endpointCombo.setMaxWidth(260)
  endpointCombo.getEditor.setOnAction(_ => triggerIntrospection())

  private val reflMenuItem = new MenuItem("Introspect via Reflection", FontIcon.of(Feather.SEARCH, 13))
  reflMenuItem.setOnAction(_ => triggerIntrospection())
  private val descMenuItem = new MenuItem("Load Descriptor Set (.desc / .binpb)…", FontIcon.of(Feather.FILE_TEXT, 13))
  descMenuItem.setOnAction(_ => triggerLoadDescriptorSet())
  private val protoMenuItem = new MenuItem("Load Raw .proto Files…", FontIcon.of(Feather.CODE, 13))
  protoMenuItem.setOnAction(_ => triggerLoadProtoFiles())
  private val healthMenuItem = new MenuItem("Run gRPC Health Check", FontIcon.of(Feather.ACTIVITY, 13))
  healthMenuItem.setOnAction(_ => triggerHealthProbe())
  private val kubeMenuItem = new MenuItem("Kubernetes Port-Forward…", FontIcon.of(Feather.SHARE_2, 13))
  kubeMenuItem.setOnAction { _ =>
    kubePortForwardPort.foreach { kube =>
      KubePortForwardDialog.show(kube).foreach { ep =>
        endpointCombo.setValue(ep)
        tlsToggle.setSelected(false)
        appState.statusText.set(s"Connected k8s port-forward tunnel at $ep")
        triggerIntrospection()
      }
    }
  }

  val schemaChipBtn = new MenuButton("Refl")
  schemaChipBtn.getItems.addAll(reflMenuItem, descMenuItem, protoMenuItem, new SeparatorMenuItem(), kubeMenuItem, healthMenuItem)
  schemaChipBtn.getStyleClass.addAll("schema-chip-btn", "interactive-hand")
  schemaChipBtn.setMinWidth(Region.USE_PREF_SIZE)
  schemaChipBtn.setTooltip(new Tooltip("Schema source (Reflection / .desc / .proto) & Health Check"))

  appState.schemaSourceLabel.addListener { (_, _, raw) =>
    Platform.runLater(() => schemaChipBtn.setText(shortenSourceLabel(raw)))
  }
  schemaChipBtn.setText(shortenSourceLabel(appState.schemaSourceLabel.get))

  val introspectBtn = new Button("", FontIcon.of(Feather.REFRESH_CW, 13))
  introspectBtn.getStyleClass.addAll("accent", "target-introspect-btn", "interactive-hand")
  introspectBtn.setMinWidth(Region.USE_PREF_SIZE)
  introspectBtn.setTooltip(new Tooltip("Introspect Target via Server Reflection"))
  introspectBtn.setOnAction(_ => triggerIntrospection())

  getChildren.addAll(tlsToggle, endpointCombo, schemaChipBtn, introspectBtn)

  private def syncTlsVisual(isTls: Boolean): Unit = {
    tlsIcon.setIconCode(if (isTls) Feather.LOCK else Feather.UNLOCK)
    tlsToggle.setText(if (isTls) "TLS" else "Plain")
    tlsToggle.getStyleClass.removeAll("tls-active")
    if (isTls) tlsToggle.getStyleClass.add("tls-active")
  }

  private def shortenSourceLabel(raw: String): String = {
    val clean = Option(raw).getOrElse("Refl").stripPrefix("Source:").trim
    if (clean.equalsIgnoreCase("Server Reflection") || clean.equalsIgnoreCase("Reflection")) "Refl"
    else if (clean.length > 12) s"${clean.take(10)}…"
    else clean
  }

  def focusEndpoint(): Unit = {
    endpointCombo.requestFocus()
    endpointCombo.getEditor.selectAll()
  }

  def currentEndpointTarget: Either[String, EndpointTarget] = {
    val raw = Option(endpointCombo.getValue).getOrElse("").trim
    val resolvedEndpoint = VariableInterpolator.interpolateString(raw, appState.activeEnvVariables)
    val useTls = tlsToggle.isSelected
    val settings = workspaceService.getSettings
    val activeEnv = Option(appState.activeEnvironment.get).flatten
    EndpointTarget.parse(resolvedEndpoint, useTls).map { base =>
      base.copy(
        tlsCertPath = activeEnv.flatMap(_.customCaCertPath).orElse(settings.customCaCertPath),
        insecureSkipTlsVerify = activeEnv.map(_.insecureSkipTlsVerify).getOrElse(settings.insecureSkipTlsVerify),
        clientCertPath = activeEnv.flatMap(_.clientCertPath).orElse(settings.clientCertPath),
        clientKeyPath = activeEnv.flatMap(_.clientKeyPath).orElse(settings.clientKeyPath)
      )
    }
  }

  def applyEnvironmentBundle(env: EnvironmentConfig): Unit = {
    endpointCombo.setValue(env.endpoint)
    tlsToggle.setSelected(env.useTls)
    currentEndpointTarget.foreach(t => appState.activeTarget.set(Some(t)))
  }

  def refreshFromActiveProject(): Unit = {
    val proj = workspaceService.getActiveProject
    val endpoints = if (proj.recentEndpoints.nonEmpty) proj.recentEndpoints else workspaceService.getData.recentEndpoints
    endpointCombo.getItems.setAll(endpoints.asJava)
    endpointCombo.setValue(proj.lastEndpoint.getOrElse(workspaceService.getSettings.lastEndpoint))
    tlsToggle.setSelected(proj.lastUseTls.getOrElse(workspaceService.getSettings.lastUseTls))
    currentEndpointTarget.foreach(t => appState.activeTarget.set(Some(t)))
  }

  def triggerIntrospection(): Unit = triggerIntrospection(() => ())

  def triggerIntrospection(onSuccess: () => Unit): Unit = {
    val raw = Option(endpointCombo.getValue).getOrElse("").trim
    val useTls = tlsToggle.isSelected
    val settings = workspaceService.getSettings

    currentEndpointTarget match {
      case Right(target) =>
        workspaceService.addEndpoint(raw)
        if (!endpointCombo.getItems.contains(raw)) endpointCombo.getItems.add(0, raw)
        workspaceService.updateActiveProjectEndpoint(raw, useTls)

        appState.activeTarget.set(Some(target))
        appState.isBusy.set(true)
        appState.connectionStatus.set(ConnectionStatus.Connecting(target.displayLabel))
        appState.statusText.set(s"Connecting to ${target.displayLabel}...")
        introspectBtn.setDisable(true)

        connectionService.connect(target, settings.maxInboundMessageMb) match {
          case Right(channel) =>
            reflectionService.introspect(target, channel, settings.reflectionTimeoutSeconds).onComplete {
              case scala.util.Success(Right(services)) =>
                Platform.runLater { () =>
                  appState.schemaSourceLabel.set("Server Reflection")
                  applyDiscoveredServices(services)
                  appState.isBusy.set(false)
                  introspectBtn.setDisable(false)
                  appState.connectionStatus.set(ConnectionStatus.Connected(target.displayLabel, services.size))
                  appState.statusText.set(s"Discovered ${services.size} services on ${target.displayLabel}")
                  onSuccess()
                }
              case scala.util.Success(Left(err)) =>
                Platform.runLater { () =>
                  appState.isBusy.set(false)
                  introspectBtn.setDisable(false)
                  appState.connectionStatus.set(ConnectionStatus.Failed(target.displayLabel, err.message))
                  appState.statusText.set(s"Reflection Error: ${err.message}")
                  CustomDialogStage.showErrorAlert("Reflection Failed", err.message)
                }
              case scala.util.Failure(ex) =>
                Platform.runLater { () =>
                  appState.isBusy.set(false)
                  introspectBtn.setDisable(false)
                  appState.connectionStatus.set(ConnectionStatus.Failed(target.displayLabel, ex.getMessage))
                  appState.statusText.set(s"Error: ${ex.getMessage}")
                  CustomDialogStage.showErrorAlert("Connection Error", ex.getMessage)
                }
            }

          case Left(err) =>
            appState.isBusy.set(false)
            introspectBtn.setDisable(false)
            appState.connectionStatus.set(ConnectionStatus.Failed(target.displayLabel, err.message))
            appState.statusText.set(s"Failed to open channel: ${err.message}")
            CustomDialogStage.showErrorAlert("Channel Error", err.message)
        }

      case Left(parseErr) =>
        CustomDialogStage.showErrorAlert("Invalid Endpoint", parseErr)
    }
  }

  def triggerLoadDescriptorSet(): Unit = {
    val chooser = new FileChooser()
    chooser.setTitle("Load Protobuf FileDescriptorSet (.desc / .binpb / .pb)")
    Option(chooser.showOpenDialog(getScene.getWindow)).foreach { file =>
      schemaService.loadDescriptorSet(file.toPath) match {
        case Right(services) =>
          currentEndpointTarget.foreach(t => appState.activeTarget.set(Some(t)))
          appState.schemaSourceLabel.set(file.getName)
          applyDiscoveredServices(services)
          val epLabel = currentEndpointTarget.map(_.displayLabel).getOrElse("Offline Schema")
          appState.connectionStatus.set(ConnectionStatus.Connected(epLabel, services.size))
          appState.statusText.set(s"Loaded ${services.size} services from ${file.getName}")
        case Left(err) =>
          CustomDialogStage.showErrorAlert("Schema Load Failed", err.message)
      }
    }
  }

  def triggerLoadProtoFiles(): Unit = {
    val settings = workspaceService.getSettings
    ProtoImportDialog.show(settings.protoIncludeDirs, settings.protocPath).foreach { req =>
      val includeStrings = req.includeDirs.map(_.toString).distinct
      workspaceService.updateSettings(workspaceService.getSettings.copy(protoIncludeDirs = includeStrings))
      schemaService.loadProtoFiles(req.protoFiles, req.includeDirs, settings.protocPath) match {
        case Right(services) =>
          currentEndpointTarget.foreach(t => appState.activeTarget.set(Some(t)))
          val labelName = req.protoFiles.headOption.map(_.getFileName.toString).getOrElse(".proto")
          val suffix = if (req.protoFiles.size > 1) s" (+${req.protoFiles.size - 1})" else ""
          appState.schemaSourceLabel.set(s"$labelName$suffix")
          applyDiscoveredServices(services)
          val epLabel = currentEndpointTarget.map(_.displayLabel).getOrElse("Offline Schema")
          appState.connectionStatus.set(ConnectionStatus.Connected(epLabel, services.size))
          appState.statusText.set(s"Loaded ${services.size} services from $labelName$suffix")
        case Left(err) =>
          CustomDialogStage.showErrorAlert("Proto Compile Failed", err.message)
      }
    }
  }

  def triggerHealthProbe(): Unit = {
    currentEndpointTarget match {
      case Right(target) =>
        val settings = workspaceService.getSettings
        connectionService.connect(target, settings.maxInboundMessageMb) match {
          case Right(channel) =>
            appState.statusText.set(s"Checking gRPC Health on ${target.displayLabel}...")
            connectionService.checkHealth(channel).onComplete {
              case scala.util.Success(Right(status)) =>
                Platform.runLater(() => appState.statusText.set(s"Health Check (${target.displayLabel}): $status"))
              case scala.util.Success(Left(errMsg)) =>
                Platform.runLater(() => appState.statusText.set(s"Health Check (${target.displayLabel}): $errMsg"))
              case scala.util.Failure(ex) =>
                Platform.runLater(() => appState.statusText.set(s"Health Check failed: ${ex.getMessage}"))
            }
          case Left(err) =>
            appState.statusText.set(s"Health Check failed: ${err.message}")
        }
      case Left(err) =>
        CustomDialogStage.showErrorAlert("Invalid Endpoint", err)
    }
  }

  def applyImportedConnection(
    endpointOpt: Option[String],
    useTls: Boolean,
    overrideAuthority: Option[String],
    protoFiles: List[String],
    importPaths: List[String],
    targetMethodOpt: Option[String],
    onMethodReady: () => Unit
  ): Unit = {
    val previousEndpoint = Option(endpointCombo.getValue).getOrElse("").trim
    endpointOpt.filter(_.nonEmpty).foreach { ep =>
      endpointCombo.setValue(ep)
      tlsToggle.setSelected(useTls)
    }

    currentEndpointTarget
      .map(t => t.copy(overrideAuthority = overrideAuthority.orElse(t.overrideAuthority)))
      .foreach(t => appState.activeTarget.set(Some(t)))

    if (protoFiles.nonEmpty) {
      val settings = workspaceService.getSettings
      val pFiles = protoFiles.map(java.nio.file.Paths.get(_))
      val incDirs = importPaths.map(java.nio.file.Paths.get(_))
      schemaService.loadProtoFiles(pFiles, incDirs, settings.protocPath) match {
        case Right(services) =>
          appState.schemaSourceLabel.set(pFiles.head.getFileName.toString)
          applyDiscoveredServices(services)
          targetMethodOpt.foreach(selectMethodInAppState)
          onMethodReady()
        case Left(err) =>
          CustomDialogStage.showErrorAlert("Proto Import Failed", err.message)
      }
      return
    }

    val methodAlreadyLoaded = targetMethodOpt.exists { fullName =>
      appState.discoveredServices.asScala.exists(_.methods.exists(_.fullMethodName == fullName))
    }
    val sameEndpoint = endpointOpt.forall(_ == previousEndpoint)

    if (methodAlreadyLoaded && sameEndpoint) {
      targetMethodOpt.foreach(selectMethodInAppState)
      onMethodReady()
    } else if (endpointOpt.exists(_.nonEmpty)) {
      triggerIntrospection(() => {
        targetMethodOpt.foreach(selectMethodInAppState)
        onMethodReady()
      })
    } else {
      targetMethodOpt.foreach(selectMethodInAppState)
      onMethodReady()
    }
  }

  private def applyDiscoveredServices(services: List[ServiceSchema]): Unit = {
    appState.discoveredServices.setAll(services.asJava)

    val settings = workspaceService.getSettings
    val projLastMethod = workspaceService.getActiveProject.lastSelectedMethod.orElse(settings.lastSelectedMethod)
    val restored: Option[MethodSchema] = if (settings.autoSelectLastMethod) {
      projLastMethod.flatMap(findMethodByName(_, services))
    } else None

    val chosen = restored.orElse(services.headOption.flatMap(_.methods.headOption))
    chosen.foreach { method =>
      appState.selectedMethod.set(Some(method))
      workspaceService.updateActiveProjectLastMethod(Some(method.fullMethodName))
    }
  }

  private def selectMethodInAppState(fullMethodName: String): Unit = {
    findMethodByName(fullMethodName, appState.discoveredServices.asScala.toList).foreach { method =>
      appState.selectedMethod.set(Some(method))
      workspaceService.updateActiveProjectLastMethod(Some(method.fullMethodName))
    }
  }

  private def findMethodByName(fullName: String, services: List[ServiceSchema]): Option[MethodSchema] = {
    services.iterator.flatMap(_.methods).find(_.fullMethodName == fullName)
  }
}
