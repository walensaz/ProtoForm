package net.protoform.ui.util

import javafx.event.EventHandler
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.input.MouseEvent
import javafx.stage.Stage

/**
 * WindowResizeHelper enables resizing for undecorated JavaFX stages.
 * It listens to mouse movements near the edges and corners of the scene
 * and resizes the stage accordingly.
 */
object WindowResizeHelper {

  private val BorderMargin = 6.0

  def install(stage: Stage, isMaximizedCheck: () => Boolean = () => false): Unit = {
    val listener = new ResizeListener(stage, isMaximizedCheck)
    val scene = stage.getScene
    if (scene != null) {
      attachListeners(scene, listener)
    } else {
      stage.sceneProperty().addListener { (_, _, newScene) =>
        if (newScene != null) {
          attachListeners(newScene, listener)
        }
      }
    }
  }

  private def attachListeners(scene: Scene, listener: ResizeListener): Unit = {
    scene.addEventFilter(MouseEvent.MOUSE_MOVED, listener)
    scene.addEventFilter(MouseEvent.MOUSE_PRESSED, listener)
    scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, listener)
    scene.addEventFilter(MouseEvent.MOUSE_EXITED, listener)
  }

  private class ResizeListener(stage: Stage, isMaximizedCheck: () => Boolean) extends EventHandler[MouseEvent] {
    private var cursorEvent: Cursor = Cursor.DEFAULT
    private var startScreenX: Double = 0.0
    private var startScreenY: Double = 0.0
    private var startStageX: Double = 0.0
    private var startStageY: Double = 0.0
    private var startStageWidth: Double = 0.0
    private var startStageHeight: Double = 0.0

    override def handle(event: MouseEvent): Unit = {
      val eventType = event.getEventType

      if (stage.isMaximized || isMaximizedCheck()) {
        if (eventType == MouseEvent.MOUSE_MOVED) {
          val scene = stage.getScene
          if (scene != null && scene.getCursor != Cursor.DEFAULT) {
            scene.setCursor(Cursor.DEFAULT)
          }
        }
        return
      }

      val scene = stage.getScene
      if (scene == null) return

      if (eventType == MouseEvent.MOUSE_MOVED) {
        val mouseX = event.getSceneX
        val mouseY = event.getSceneY
        val sceneWidth = scene.getWidth
        val sceneHeight = scene.getHeight

        val top = mouseY <= BorderMargin
        val bottom = mouseY >= sceneHeight - BorderMargin
        val left = mouseX <= BorderMargin
        val right = mouseX >= sceneWidth - BorderMargin

        cursorEvent = if (top && left) Cursor.NW_RESIZE
        else if (top && right) Cursor.NE_RESIZE
        else if (bottom && left) Cursor.SW_RESIZE
        else if (bottom && right) Cursor.SE_RESIZE
        else if (top) Cursor.N_RESIZE
        else if (bottom) Cursor.S_RESIZE
        else if (left) Cursor.W_RESIZE
        else if (right) Cursor.E_RESIZE
        else Cursor.DEFAULT

        scene.setCursor(cursorEvent)

      } else if (eventType == MouseEvent.MOUSE_EXITED) {
        if (cursorEvent == Cursor.DEFAULT) {
          scene.setCursor(Cursor.DEFAULT)
        }

      } else if (eventType == MouseEvent.MOUSE_PRESSED) {
        if (cursorEvent != Cursor.DEFAULT) {
          startScreenX = event.getScreenX
          startScreenY = event.getScreenY
          startStageX = stage.getX
          startStageY = stage.getY
          startStageWidth = stage.getWidth
          startStageHeight = stage.getHeight
          event.consume()
        }

      } else if (eventType == MouseEvent.MOUSE_DRAGGED) {
        if (cursorEvent != Cursor.DEFAULT) {
          val deltaX = event.getScreenX - startScreenX
          val deltaY = event.getScreenY - startScreenY

          val minWidth = Math.max(stage.getMinWidth, 400.0)
          val minHeight = Math.max(stage.getMinHeight, 300.0)

          // Handle horizontal resizing
          if (cursorEvent == Cursor.E_RESIZE || cursorEvent == Cursor.NE_RESIZE || cursorEvent == Cursor.SE_RESIZE) {
            val newW = Math.max(minWidth, startStageWidth + deltaX)
            stage.setWidth(newW)
          } else if (cursorEvent == Cursor.W_RESIZE || cursorEvent == Cursor.NW_RESIZE || cursorEvent == Cursor.SW_RESIZE) {
            val maxDelta = startStageWidth - minWidth
            val clampedDelta = Math.min(deltaX, maxDelta)
            stage.setX(startStageX + clampedDelta)
            stage.setWidth(startStageWidth - clampedDelta)
          }

          // Handle vertical resizing
          if (cursorEvent == Cursor.S_RESIZE || cursorEvent == Cursor.SE_RESIZE || cursorEvent == Cursor.SW_RESIZE) {
            val newH = Math.max(minHeight, startStageHeight + deltaY)
            stage.setHeight(newH)
          } else if (cursorEvent == Cursor.N_RESIZE || cursorEvent == Cursor.NW_RESIZE || cursorEvent == Cursor.NE_RESIZE) {
            val maxDelta = startStageHeight - minHeight
            val clampedDelta = Math.min(deltaY, maxDelta)
            stage.setY(startStageY + clampedDelta)
            stage.setHeight(startStageHeight - clampedDelta)
          }

          event.consume()
        }
      }
    }
  }
}
