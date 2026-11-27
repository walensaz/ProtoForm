package net.protoform.ui.util

import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control._
import javafx.scene.input.{KeyCode, KeyEvent}
import javafx.scene.layout._
import javafx.stage.{Modality, Screen, Stage, StageStyle}
import net.protoform.ui.theme.ThemeManager
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.jdk.CollectionConverters._

object CustomDialogStage {

  def createStage(
    title: String,
    iconCode: Feather,
    content: Node,
    subtitle: Option[String] = None,
    footer: Option[Node] = None,
    prefWidth: Double = 560,
    prefHeight: Double = 400,
    minWidth: Double = 400,
    minHeight: Double = 260,
    resizable: Boolean = true,
    modal: Boolean = true,
    headerExtraRight: Option[Node] = None,
    onCloseRequested: Option[Stage => Unit] = None
  ): Stage = {
    val stage = new Stage()
    stage.initStyle(StageStyle.UNDECORATED)
    ThemeManager.applyStageIcons(stage)
    if (modal) {
      stage.initModality(Modality.WINDOW_MODAL)
    }
    ThemeManager.getPrimaryWindow.foreach(stage.initOwner)
    stage.setTitle(title)

    val icon = FontIcon.of(iconCode, 14)
    icon.getStyleClass.add("brand-icon")
    val iconBox = new StackPane(icon)
    iconBox.getStyleClass.add("brand-mark")

    val titleLbl = new Label(title)
    titleLbl.getStyleClass.add("brand-title")

    val spacer = new Region()
    HBox.setHgrow(spacer, Priority.ALWAYS)

    val closeBtn = new Button("", FontIcon.of(Feather.X, 13))
    closeBtn.getStyleClass.addAll("window-control-btn", "window-control-btn-close", "interactive-hand")
    closeBtn.setTooltip(new Tooltip("Close (Esc)"))

    val headerBar = new HBox(10)
    headerBar.setAlignment(Pos.CENTER_LEFT)
    headerBar.setPadding(new Insets(6, 10, 6, 12))
    headerBar.getStyleClass.add("custom-title-bar")
    headerBar.getChildren.addAll(iconBox, titleLbl, spacer)
    headerExtraRight.foreach(headerBar.getChildren.add)
    headerBar.getChildren.add(closeBtn)

    WindowResizeHelper.installHeaderDrag(headerBar, stage)

    val topStack = new VBox(headerBar)
    subtitle.filter(_.trim.nonEmpty).foreach { sub =>
      val subLbl = new Label(sub)
      subLbl.getStyleClass.add("form-subtext")
      subLbl.setWrapText(true)
      val subBar = new HBox(subLbl)
      subBar.setPadding(new Insets(8, 16, 8, 16))
      subBar.getStyleClass.add("dialog-subtitle-bar")
      topStack.getChildren.add(subBar)
    }

    val root = new BorderPane()
    root.getStyleClass.add("app-root")
    root.setTop(topStack)
    root.setCenter(content)
    footer.foreach(root.setBottom)

    val vb = ThemeManager.getPrimaryWindow
      .flatMap { w =>
        Screen.getScreensForRectangle(w.getX, w.getY, Math.max(1.0, w.getWidth), Math.max(1.0, w.getHeight)).asScala.headOption
      }
      .getOrElse(Screen.getPrimary)
      .getVisualBounds

    val safeW = Math.min(Math.max(minWidth, prefWidth), vb.getWidth - 40)
    val safeH = Math.min(Math.max(minHeight, prefHeight), vb.getHeight - 40)

    val scene = new Scene(root, safeW, safeH)
    ThemeManager.registerSecondaryScene(scene)

    val closeAction: () => Unit = () => {
      onCloseRequested match {
        case Some(handler) => handler(stage)
        case None          => stage.close()
      }
    }

    closeBtn.setOnAction(_ => closeAction())
    if (modal) {
      scene.addEventFilter(KeyEvent.KEY_PRESSED, ev => {
        if (ev.getCode == KeyCode.ESCAPE) {
          ev.consume()
          closeAction()
        }
      })
    }

    stage.setOnHidden(_ => ThemeManager.unregisterSecondaryScene(scene))
    stage.setScene(scene)
    stage.setMinWidth(Math.min(minWidth, safeW))
    stage.setMinHeight(Math.min(minHeight, safeH))
    if (resizable) {
      WindowResizeHelper.install(stage)
    }

    // Center over owner window when shown
    ThemeManager.getPrimaryWindow.foreach { owner =>
      stage.setOnShown { _ =>
        val cx = owner.getX + (owner.getWidth - stage.getWidth) / 2.0
        val cy = owner.getY + (owner.getHeight - stage.getHeight) / 2.0
        stage.setX(Math.max(vb.getMinX, Math.min(cx, vb.getMaxX - stage.getWidth)))
        stage.setY(Math.max(vb.getMinY, Math.min(cy, vb.getMaxY - stage.getHeight)))
      }
    }

    stage
  }

  def buildFooterBar(buttons: Node*): HBox = {
    val spacer = new Region()
    HBox.setHgrow(spacer, Priority.ALWAYS)
    val bar = new HBox(8)
    bar.setAlignment(Pos.CENTER_RIGHT)
    bar.setPadding(new Insets(10, 14, 10, 14))
    bar.getStyleClass.add("dialog-footer-bar")
    bar.getChildren.add(spacer)
    bar.getChildren.addAll(buttons: _*)
    bar
  }

  def showErrorAlert(title: String, message: String): Unit = {
    val msgLbl = new Label(message)
    msgLbl.setWrapText(true)
    msgLbl.getStyleClass.add("form-label")
    val scroll = new ScrollPane(new VBox(msgLbl))
    scroll.setFitToWidth(true)
    scroll.setPadding(new Insets(14))

    val okBtn = new Button("OK")
    okBtn.getStyleClass.addAll("accent", "interactive-hand")
    okBtn.setDefaultButton(true)
    val footer = buildFooterBar(okBtn)

    val stage = createStage(
      title = title,
      iconCode = Feather.ALERT_CIRCLE,
      content = scroll,
      footer = Some(footer),
      prefWidth = 480,
      prefHeight = 220,
      minWidth = 360,
      minHeight = 180,
      resizable = true
    )
    okBtn.setOnAction(_ => stage.close())
    stage.showAndWait()
  }

  def showConfirm(
    title: String,
    header: String,
    message: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = false,
    iconCode: Feather = Feather.ALERT_TRIANGLE
  ): Boolean = {
    var confirmed = false
    val msgLbl = new Label(message)
    msgLbl.setWrapText(true)
    msgLbl.getStyleClass.add("form-subtext")
    val content = new VBox(8, msgLbl)
    content.setPadding(new Insets(16))

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val confirmBtn = new Button(confirmLabel)
    confirmBtn.getStyleClass.addAll(if (destructive) "danger" else "accent", "interactive-hand")
    if (destructive) cancelBtn.setDefaultButton(true) else confirmBtn.setDefaultButton(true)

    val stage = createStage(
      title = title,
      subtitle = Some(header),
      iconCode = iconCode,
      content = content,
      footer = Some(buildFooterBar(cancelBtn, confirmBtn)),
      prefWidth = 450,
      prefHeight = 220,
      minWidth = 360,
      minHeight = 190,
      resizable = false
    )

    cancelBtn.setOnAction(_ => stage.close())
    confirmBtn.setOnAction { _ =>
      confirmed = true
      stage.close()
    }
    stage.showAndWait()
    confirmed
  }

  def showTextPrompt(title: String, header: String, label: String, initialValue: String): Option[String] = {
    var result: Option[String] = None
    val input = new TextField(initialValue)
    input.setPrefWidth(340)

    val content = new VBox(8, new Label(label), input)
    content.setPadding(new Insets(16))

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val saveBtn = new Button("Save")
    saveBtn.getStyleClass.addAll("accent", "interactive-hand")
    saveBtn.setDefaultButton(true)

    val stage = createStage(
      title = title,
      subtitle = Some(header),
      iconCode = Feather.EDIT_3,
      content = content,
      footer = Some(buildFooterBar(cancelBtn, saveBtn)),
      prefWidth = 440,
      prefHeight = 230,
      minWidth = 360,
      minHeight = 200,
      resizable = false
    )

    val submit = () => {
      result = Option(input.getText).map(_.trim).filter(_.nonEmpty)
      stage.close()
    }
    saveBtn.setOnAction(_ => submit())
    input.setOnAction(_ => submit())
    cancelBtn.setOnAction(_ => stage.close())

    stage.showAndWait()
    result
  }
}
