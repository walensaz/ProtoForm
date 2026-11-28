package net.protoform.ui.util

import javafx.event.{EventHandler, EventTarget}
import javafx.scene.{Cursor, Node, Scene}
import javafx.scene.control.{ButtonBase, ComboBoxBase, MenuBar, ScrollBar, Slider, TextInputControl}
import javafx.scene.input.{MouseButton, MouseEvent}
import javafx.stage.{Screen, Stage}

import scala.jdk.CollectionConverters._

final case class StageBounds(
  x: Double,
  y: Double,
  width: Double,
  height: Double
)

/**
 * WindowResizeHelper enables 8-direction resizing for undecorated JavaFX stages
 * and provides shared header-drag helpers so macOS trackpad clicks and screen
 * bounds behave cleanly.
 */
object WindowResizeHelper {

  val isMacOS: Boolean = System.getProperty("os.name", "").toLowerCase.contains("mac")
  val isWindows: Boolean = System.getProperty("os.name", "").toLowerCase.contains("win")

  val EdgeMargin: Double = 8.0
  val TopEdgeMargin: Double = 5.0
  val CornerMargin: Double = 14.0
  val DragThresholdPx: Double = 4.0

  private val FallbackMinWidth: Double = 400.0
  private val FallbackMinHeight: Double = 300.0

  def resolveResizeCursor(sceneX: Double, sceneY: Double, sceneWidth: Double, sceneHeight: Double): Cursor = {
    if (sceneWidth <= 0.0 || sceneHeight <= 0.0) return Cursor.DEFAULT

    val inTop = sceneY >= 0.0 && sceneY <= TopEdgeMargin
    val inBottom = sceneY >= sceneHeight - EdgeMargin && sceneY <= sceneHeight
    val inLeft = sceneX >= 0.0 && sceneX <= EdgeMargin
    val inRight = sceneX >= sceneWidth - EdgeMargin && sceneX <= sceneWidth

    val nearTopCorner = sceneY >= 0.0 && sceneY <= TopEdgeMargin + 4.0
    val nearBottomCorner = sceneY >= sceneHeight - CornerMargin && sceneY <= sceneHeight
    val nearLeftCorner = sceneX >= 0.0 && sceneX <= CornerMargin
    val nearRightCorner = sceneX >= sceneWidth - CornerMargin && sceneX <= sceneWidth

    if ((inTop && nearLeftCorner) || (inLeft && nearTopCorner)) Cursor.NW_RESIZE
    else if ((inTop && nearRightCorner) || (inRight && nearTopCorner)) Cursor.NE_RESIZE
    else if ((inBottom && nearLeftCorner) || (inLeft && nearBottomCorner)) Cursor.SW_RESIZE
    else if ((inBottom && nearRightCorner) || (inRight && nearBottomCorner)) Cursor.SE_RESIZE
    else if (inTop) Cursor.N_RESIZE
    else if (inBottom) Cursor.S_RESIZE
    else if (inLeft) Cursor.W_RESIZE
    else if (inRight) Cursor.E_RESIZE
    else Cursor.DEFAULT
  }

  def computeResizedBounds(
    cursor: Cursor,
    start: StageBounds,
    deltaX: Double,
    deltaY: Double,
    minWidth: Double,
    minHeight: Double,
    minAllowedY: Double = Double.NegativeInfinity
  ): StageBounds = {
    val anchorRight = start.x + start.width
    val anchorBottom = start.y + start.height

    val (nextX, nextW) =
      if (cursor == Cursor.E_RESIZE || cursor == Cursor.NE_RESIZE || cursor == Cursor.SE_RESIZE) {
        (start.x, Math.max(minWidth, start.width + deltaX))
      } else if (cursor == Cursor.W_RESIZE || cursor == Cursor.NW_RESIZE || cursor == Cursor.SW_RESIZE) {
        val x = Math.min(anchorRight - minWidth, start.x + deltaX)
        (x, anchorRight - x)
      } else {
        (start.x, start.width)
      }

    val (nextY, nextH) =
      if (cursor == Cursor.S_RESIZE || cursor == Cursor.SE_RESIZE || cursor == Cursor.SW_RESIZE) {
        (start.y, Math.max(minHeight, start.height + deltaY))
      } else if (cursor == Cursor.N_RESIZE || cursor == Cursor.NW_RESIZE || cursor == Cursor.NE_RESIZE) {
        val clampedY = Math.min(anchorBottom - minHeight, start.y + deltaY)
        val y = Math.max(minAllowedY, clampedY)
        (y, anchorBottom - y)
      } else {
        (start.y, start.height)
      }

    StageBounds(nextX, nextY, nextW, nextH)
  }

  def isInteractiveTarget(target: EventTarget, stopAt: Node): Boolean = {
    var cur: Node = target match {
      case n: Node => n
      case _       => null
    }
    while (cur != null && cur != stopAt) {
      cur match {
        case _: ButtonBase | _: ComboBoxBase[_] | _: TextInputControl | _: ScrollBar | _: Slider | _: MenuBar =>
          return true
        case n if n.getStyleClass.contains("no-window-drag") =>
          return true
        case _ =>
          cur = cur.getParent
      }
    }
    false
  }

  def resolveActiveScreenMinY(stage: Stage): Double = {
    val x = if (!stage.getX.isNaN) stage.getX else 0.0
    val y = if (!stage.getY.isNaN) stage.getY else 0.0
    val w = if (!stage.getWidth.isNaN && stage.getWidth > 0) stage.getWidth else 200.0
    val h = if (!stage.getHeight.isNaN && stage.getHeight > 0) stage.getHeight else 200.0
    val screens = Screen.getScreensForRectangle(x, y, w, h).asScala
    screens.headOption.getOrElse(Screen.getPrimary).getVisualBounds.getMinY
  }

  def installHeaderDrag(
    header: Node,
    stage: Stage,
    isMaximized: () => Boolean = () => false,
    onRestoreForDrag: (Double, Double) => (Double, Double) = (_, _) => (0.0, 0.0),
    onDoubleClick: Option[() => Unit] = None
  ): Unit = {
    var pressScreenX = 0.0
    var pressScreenY = 0.0
    var dragOffsetX = 0.0
    var dragOffsetY = 0.0
    var minAllowedY = Double.NegativeInfinity
    var dragEligible = false
    var dragStarted = false

    header.setOnMousePressed { event =>
      if (event.getButton == MouseButton.PRIMARY) {
        val scene = header.getScene
        val onResizeEdge = scene != null &&
          resolveResizeCursor(event.getSceneX, event.getSceneY, scene.getWidth, scene.getHeight) != Cursor.DEFAULT
        dragEligible = !onResizeEdge && !isInteractiveTarget(event.getTarget, header)
        dragStarted = false
        if (dragEligible) {
          pressScreenX = event.getScreenX
          pressScreenY = event.getScreenY
          dragOffsetX = event.getScreenX - stage.getX
          dragOffsetY = event.getScreenY - stage.getY
          minAllowedY = resolveActiveScreenMinY(stage)
        }
      } else {
        dragEligible = false
        dragStarted = false
      }
    }

    header.setOnMouseDragged { event =>
      if (dragEligible && event.isPrimaryButtonDown) {
        if (!dragStarted) {
          val dist = Math.hypot(event.getScreenX - pressScreenX, event.getScreenY - pressScreenY)
          if (dist >= DragThresholdPx) {
            dragStarted = true
            if (stage.isMaximized || isMaximized()) {
              val (ox, oy) = onRestoreForDrag(event.getScreenX, event.getScreenY)
              dragOffsetX = ox
              dragOffsetY = oy
            }
          }
        }
        if (dragStarted) {
          stage.setX(event.getScreenX - dragOffsetX)
          stage.setY(Math.max(minAllowedY, event.getScreenY - dragOffsetY))
        }
      }
    }

    header.setOnMouseReleased { _ =>
      dragEligible = false
      if (dragStarted) {
        javafx.application.Platform.runLater(() => dragStarted = false)
      }
    }

    onDoubleClick.foreach { toggle =>
      header.setOnMouseClicked { event =>
        val allowDoubleClick = !dragStarted && event.isStillSincePress
        dragStarted = false
        if (event.getButton == MouseButton.PRIMARY &&
          event.getClickCount == 2 &&
          allowDoubleClick &&
          !isInteractiveTarget(event.getTarget, header)
        ) {
          toggle()
          event.consume()
        }
      }
    }
  }

  def install(
    stage: Stage,
    isMaximizedCheck: () => Boolean = () => false,
    onBeginResizeFromMaximized: () => Unit = () => ()
  ): Unit = {
    val listener = new ResizeListener(stage, isMaximizedCheck, onBeginResizeFromMaximized)
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
    scene.addEventFilter(MouseEvent.MOUSE_RELEASED, listener)
    scene.addEventFilter(MouseEvent.MOUSE_EXITED, listener)
  }

  private class ResizeListener(
    stage: Stage,
    isMaximizedCheck: () => Boolean,
    onBeginResizeFromMaximized: () => Unit
  ) extends EventHandler[MouseEvent] {
    private var activeResizeCursor: Option[Cursor] = None
    private var startScreenX: Double = 0.0
    private var startScreenY: Double = 0.0
    private var startBounds: StageBounds = StageBounds(0, 0, 0, 0)
    private var minAllowedY: Double = Double.NegativeInfinity

    private def updateSceneCursor(scene: Scene, cursor: Cursor): Unit = {
      if (scene.getCursor != cursor) {
        scene.setCursor(cursor)
      }
    }

    override def handle(event: MouseEvent): Unit = {
      val scene = stage.getScene
      if (scene == null) return

      val eventType = event.getEventType
      if (stage.isMaximized) {
        activeResizeCursor = None
        if (eventType == MouseEvent.MOUSE_MOVED || eventType == MouseEvent.MOUSE_EXITED) {
          updateSceneCursor(scene, Cursor.DEFAULT)
        }
        return
      }

      if (eventType == MouseEvent.MOUSE_MOVED) {
        if (activeResizeCursor.isEmpty) {
          val hover = resolveResizeCursor(event.getSceneX, event.getSceneY, scene.getWidth, scene.getHeight)
          updateSceneCursor(scene, hover)
        }
      } else if (eventType == MouseEvent.MOUSE_EXITED) {
        if (activeResizeCursor.isEmpty) {
          updateSceneCursor(scene, Cursor.DEFAULT)
        }
      } else if (eventType == MouseEvent.MOUSE_PRESSED) {
        if (event.getButton == MouseButton.PRIMARY) {
          val hit = resolveResizeCursor(event.getSceneX, event.getSceneY, scene.getWidth, scene.getHeight)
          if (hit != Cursor.DEFAULT) {
            if (isMaximizedCheck()) {
              onBeginResizeFromMaximized()
            }
            startScreenX = event.getScreenX
            startScreenY = event.getScreenY
            startBounds = StageBounds(stage.getX, stage.getY, stage.getWidth, stage.getHeight)
            minAllowedY = resolveActiveScreenMinY(stage)
            activeResizeCursor = Some(hit)
            updateSceneCursor(scene, hit)
            event.consume()
          }
        }
      } else if (eventType == MouseEvent.MOUSE_DRAGGED) {
        activeResizeCursor.foreach { cursor =>
          val minWidth = Math.max(stage.getMinWidth, FallbackMinWidth)
          val minHeight = Math.max(stage.getMinHeight, FallbackMinHeight)
          val next = computeResizedBounds(
            cursor = cursor,
            start = startBounds,
            deltaX = event.getScreenX - startScreenX,
            deltaY = event.getScreenY - startScreenY,
            minWidth = minWidth,
            minHeight = minHeight,
            minAllowedY = minAllowedY
          )
          applyBounds(next)
          event.consume()
        }
      } else if (eventType == MouseEvent.MOUSE_RELEASED) {
        if (activeResizeCursor.isDefined) {
          activeResizeCursor = None
          val hover = resolveResizeCursor(event.getSceneX, event.getSceneY, scene.getWidth, scene.getHeight)
          updateSceneCursor(scene, hover)
          event.consume()
        }
      }
    }

    private def applyBounds(next: StageBounds): Unit = {
      if (Math.abs(stage.getWidth - next.width) >= 0.5 && next.width < stage.getWidth) {
        stage.setWidth(next.width)
      }
      if (Math.abs(stage.getX - next.x) >= 0.5) {
        stage.setX(next.x)
      }
      if (Math.abs(stage.getWidth - next.width) >= 0.5) {
        stage.setWidth(next.width)
      }

      if (Math.abs(stage.getHeight - next.height) >= 0.5 && next.height < stage.getHeight) {
        stage.setHeight(next.height)
      }
      if (Math.abs(stage.getY - next.y) >= 0.5) {
        stage.setY(next.y)
      }
      if (Math.abs(stage.getHeight - next.height) >= 0.5) {
        stage.setHeight(next.height)
      }
    }
  }
}
