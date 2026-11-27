package net.protoform.ui.util

import javafx.scene.Node
import javafx.scene.control.{Button, Tooltip}
import javafx.stage.Stage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

class DockingManager(
  onRedock: () => Unit
) {
  private var floatingStage: Option[Stage] = None

  def isFloating: Boolean = floatingStage.exists(_.isShowing)

  def popOut(title: String, content: Node, width: Double = 680, height: Double = 740): Unit = {
    if (isFloating) {
      floatingStage.foreach(_.toFront())
      return
    }

    val redockBtn = new Button("Re-dock", FontIcon.of(Feather.MINIMIZE_2, 12))
    redockBtn.getStyleClass.addAll("small", "accent", "interactive-hand")
    redockBtn.setTooltip(new Tooltip("Re-dock pane into main window"))

    val stage = CustomDialogStage.createStage(
      title = title,
      iconCode = Feather.EXTERNAL_LINK,
      content = content,
      prefWidth = width,
      prefHeight = height,
      minWidth = 360,
      minHeight = 320,
      resizable = true,
      modal = false,
      headerExtraRight = Some(redockBtn),
      onCloseRequested = Some(_ => closeAndRedock())
    )

    redockBtn.setOnAction(_ => closeAndRedock())
    stage.setOnHidden { _ =>
      if (floatingStage.contains(stage)) {
        floatingStage = None
        onRedock()
      }
    }

    floatingStage = Some(stage)
    stage.show()
  }

  def closeAndRedock(): Unit = {
    val stageOpt = floatingStage
    floatingStage = None
    stageOpt.foreach(_.close())
    onRedock()
  }
}
