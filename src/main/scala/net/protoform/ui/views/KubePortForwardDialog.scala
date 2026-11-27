package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import net.protoform.core.model.{ActivePortForward, KubeTargetCandidate, KubeTargetSpec}
import net.protoform.ports.infra.KubePortForwardPort
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

object KubePortForwardDialog {

  def show(kubePort: KubePortForwardPort): Option[String] = {
    var selectedEndpoint: Option[String] = None

    val contextCombo = new ComboBox[String]()
    contextCombo.setPrefWidth(220)
    val refreshCtxBtn = new Button("", FontIcon.of(Feather.REFRESH_CW, 12))
    refreshCtxBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")

    val nsCombo = new ComboBox[String]()
    nsCombo.setPrefWidth(180)
    nsCombo.setEditable(true)
    nsCombo.setValue("default")

    val topRow = new HBox(8, new Label("Context:"), contextCombo, refreshCtxBtn, new Label("Namespace:"), nsCombo)
    topRow.setAlignment(Pos.CENTER_LEFT)

    val targetsList = new ListView[KubeTargetCandidate]()
    targetsList.setPlaceholder(new Label("Click Refresh or select a namespace to discover Services & Pods."))
    targetsList.setCellFactory { _ =>
      new ListCell[KubeTargetCandidate]() {
        override def updateItem(item: KubeTargetCandidate, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) setText(null)
          else setText(s"[${item.resourceType.displayLabel}] ${item.name}  (ports: ${item.ports.mkString(", ")})")
        }
      }
    }
    VBox.setVgrow(targetsList, Priority.ALWAYS)

    val remotePortField = new TextField("50051")
    remotePortField.setPrefWidth(80)
    val localPortField = new TextField("0")
    localPortField.setPrefWidth(80)
    localPortField.setPromptText("0 = auto")
    val startBtn = new Button("Start Port-Forward & Use", FontIcon.of(Feather.PLAY, 12))
    startBtn.getStyleClass.addAll("accent", "interactive-hand")

    val portRow = new HBox(8, new Label("Remote Port:"), remotePortField, new Label("Local Port (0=auto):"), localPortField, startBtn)
    portRow.setAlignment(Pos.CENTER_LEFT)

    val activeList = new ListView[ActivePortForward]()
    activeList.setPrefHeight(110)
    activeList.setPlaceholder(new Label("No active kubectl port-forward tunnels."))
    activeList.setCellFactory { _ =>
      new ListCell[ActivePortForward]() {
        private var currentItem: Option[ActivePortForward] = None
        private val lbl = new Label() { getStyleClass.add("mono-text") }
        private val sp = new Region()
        HBox.setHgrow(sp, Priority.ALWAYS)
        private val useBtn = new Button("Use", FontIcon.of(Feather.CHECK, 11)) { getStyleClass.addAll("small", "accent", "interactive-hand") }
        private val stopBtn = new Button("", FontIcon.of(Feather.SQUARE, 11)) { getStyleClass.addAll("small", "danger", "icon-only-btn", "interactive-hand") }
        private val row = new HBox(6, lbl, sp, useBtn, stopBtn) { setAlignment(Pos.CENTER_LEFT) }

        useBtn.setOnAction(_ => currentItem.foreach { pf => selectedEndpoint = Some(pf.endpointLabel) })
        stopBtn.setOnAction { _ =>
          currentItem.foreach { pf =>
            kubePort.stopForward(pf.id)
            activeList.getItems.setAll(kubePort.activeForwards.asJava)
          }
        }

        override def updateItem(item: ActivePortForward, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          setText(null)
          if (empty || item == null) { currentItem = None; setGraphic(null) }
          else {
            currentItem = Some(item)
            lbl.setText(s"${item.endpointLabel} -> ${item.spec.namespace}/${item.spec.resourceType.cliPrefix}/${item.spec.resourceName}:${item.spec.remotePort}")
            setGraphic(row)
          }
        }
      }
    }

    val statusLbl = new Label("Uses local kubectl and ~/.kube/config")
    statusLbl.getStyleClass.add("form-subtext")

    val content = new VBox(10, topRow, targetsList, portRow, new Label("Active Tunnels") { getStyleClass.add("section-title") }, activeList, statusLbl)
    content.setPadding(new Insets(14))

    val closeBtn = new Button("Close")
    closeBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val stage = CustomDialogStage.createStage(
      title = "Kubernetes Port-Forward",
      subtitle = Some("Tunnel a remote gRPC Service or Pod to 127.0.0.1 via kubectl"),
      iconCode = Feather.SHARE_2,
      content = content,
      footer = Some(CustomDialogStage.buildFooterBar(closeBtn)),
      prefWidth = 680,
      prefHeight = 520,
      minWidth = 520,
      minHeight = 400,
      resizable = true
    )

    def loadTargets(): Unit = {
      val ctx = Option(contextCombo.getValue).map(_.trim).filter(_.nonEmpty)
      val ns = Option(nsCombo.getValue).map(_.trim).filter(_.nonEmpty).getOrElse("default")
      statusLbl.setText(s"Querying services & pods in '$ns'...")
      Future(kubePort.listTargets(ctx, ns)).foreach { res =>
        Platform.runLater { () =>
          res match {
            case Right(list) =>
              targetsList.getItems.setAll(list.asJava)
              statusLbl.setText(s"Found ${list.size} targets with ports in '$ns'.")
            case Left(err) =>
              statusLbl.setText(err.message)
          }
        }
      }
    }

    def loadContexts(): Unit = {
      statusLbl.setText("Loading kubeconfig contexts...")
      Future(kubePort.listContexts()).foreach { res =>
        Platform.runLater { () =>
          res match {
            case Right(ctxs) =>
              contextCombo.getItems.setAll(ctxs.asJava)
              if (ctxs.nonEmpty && Option(contextCombo.getValue).forall(_.isEmpty)) contextCombo.setValue(ctxs.head)
              val ctx = Option(contextCombo.getValue)
              Future(kubePort.listNamespaces(ctx)).foreach { nsRes =>
                Platform.runLater { () =>
                  nsRes.foreach(nss => nsCombo.getItems.setAll(nss.asJava))
                  loadTargets()
                }
              }
            case Left(err) =>
              statusLbl.setText(err.message)
          }
        }
      }
    }

    targetsList.getSelectionModel.selectedItemProperty().addListener { (_, _, cand) =>
      if (cand != null && cand.ports.nonEmpty) {
        remotePortField.setText(cand.ports.head.toString)
      }
    }

    refreshCtxBtn.setOnAction(_ => loadContexts())
    nsCombo.setOnAction(_ => loadTargets())
    contextCombo.setOnAction(_ => loadTargets())

    startBtn.setOnAction { _ =>
      Option(targetsList.getSelectionModel.getSelectedItem) match {
        case Some(cand) =>
          val rPort = scala.util.Try(remotePortField.getText.trim.toInt).getOrElse(cand.ports.headOption.getOrElse(50051))
          val lPort = scala.util.Try(localPortField.getText.trim.toInt).getOrElse(0)
          val ctx = Option(contextCombo.getValue).map(_.trim).filter(_.nonEmpty)
          val spec = KubeTargetSpec(ctx, cand.namespace, cand.resourceType, cand.name, rPort, lPort)
          startBtn.setDisable(true)
          statusLbl.setText(s"Starting port-forward to ${cand.name}:$rPort...")
          Future(kubePort.startForward(spec)).foreach { res =>
            Platform.runLater { () =>
              startBtn.setDisable(false)
              res match {
                case Right(active) =>
                  activeList.getItems.setAll(kubePort.activeForwards.asJava)
                  selectedEndpoint = Some(active.endpointLabel)
                  stage.close()
                case Left(err) =>
                  statusLbl.setText(err.message)
                  CustomDialogStage.showErrorAlert("Port-Forward Failed", err.message)
              }
            }
          }
        case None =>
          statusLbl.setText("Select a Service or Pod first.")
      }
    }

    closeBtn.setOnAction(_ => stage.close())
    activeList.getItems.setAll(kubePort.activeForwards.asJava)
    loadContexts()
    stage.showAndWait()
    selectedEndpoint
  }
}
