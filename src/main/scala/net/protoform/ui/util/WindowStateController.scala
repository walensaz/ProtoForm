package net.protoform.ui.util

import javafx.animation.PauseTransition
import javafx.beans.property.{BooleanProperty, ReadOnlyBooleanProperty, SimpleBooleanProperty}
import javafx.geometry.Rectangle2D
import javafx.stage.{Screen, Stage}
import javafx.util.Duration

import scala.jdk.CollectionConverters._

final case class WindowBounds(
  x: Option[Double],
  y: Option[Double],
  width: Double,
  height: Double,
  isMaximized: Boolean
)

object WindowStateController {
  val MinWindowWidth: Double = 820.0
  val MinWindowHeight: Double = 560.0

  def clampBoundsToScreens(
    xOpt: Option[Double],
    yOpt: Option[Double],
    w: Double,
    h: Double,
    screens: List[Rectangle2D],
    primaryVb: Rectangle2D
  ): (Option[Double], Option[Double], Double, Double) = {
    (xOpt, yOpt) match {
      case (Some(x), Some(y)) if !x.isNaN && !y.isNaN =>
        val vb = screens.find(_.intersects(x, y, Math.max(200.0, w), Math.max(200.0, h))).getOrElse(primaryVb)
        val clampedW = Math.min(Math.max(MinWindowWidth, w), vb.getWidth)
        val clampedH = Math.min(Math.max(MinWindowHeight, h), vb.getHeight)
        val clampedX = Math.max(vb.getMinX, Math.min(x, vb.getMaxX - Math.min(clampedW, 200.0)))
        val clampedY = Math.max(vb.getMinY, Math.min(y, vb.getMaxY - Math.min(clampedH, 120.0)))
        (Some(clampedX), Some(clampedY), clampedW, clampedH)
      case _ =>
        val clampedW = Math.min(Math.max(MinWindowWidth, w), primaryVb.getWidth)
        val clampedH = Math.min(Math.max(MinWindowHeight, h), primaryVb.getHeight)
        (None, None, clampedW, clampedH)
    }
  }
}

class WindowStateController(
  stage: Stage,
  initialWidth: Double,
  initialHeight: Double,
  initialX: Option[Double],
  initialY: Option[Double],
  onBoundsChanged: WindowBounds => Unit = _ => ()
) {
  import WindowStateController._

  private val maximizedProp: BooleanProperty = new SimpleBooleanProperty(false)
  private var applyingBounds: Boolean = false
  private val persistDebounce = new PauseTransition(Duration.millis(400))
  persistDebounce.setOnFinished(_ => onBoundsChanged(currentSnapshot))

  private var restoreWidth: Double = Math.max(MinWindowWidth, initialWidth)
  private var restoreHeight: Double = Math.max(MinWindowHeight, initialHeight)
  private var restoreX: Option[Double] = initialX
  private var restoreY: Option[Double] = initialY

  def isMaximized: Boolean = maximizedProp.get()
  def isMaximizedProperty: ReadOnlyBooleanProperty = maximizedProp

  private def scheduleBoundsPersist(): Unit = {
    if (!applyingBounds) {
      persistDebounce.playFromStart()
    }
  }

  def install(): Unit = {
    applyInitialBounds()

    stage.xProperty().addListener { (_, _, newX) =>
      if (!applyingBounds && !isMaximized && !stage.isIconified && !newX.doubleValue().isNaN) {
        restoreX = Some(newX.doubleValue())
        scheduleBoundsPersist()
      }
    }
    stage.yProperty().addListener { (_, _, newY) =>
      if (!applyingBounds && !isMaximized && !stage.isIconified && !newY.doubleValue().isNaN) {
        restoreY = Some(newY.doubleValue())
        scheduleBoundsPersist()
      }
    }
    stage.widthProperty().addListener { (_, _, newW) =>
      val w = newW.doubleValue()
      if (!applyingBounds && !isMaximized && !stage.isIconified && !w.isNaN && w >= stage.getMinWidth) {
        restoreWidth = w
        scheduleBoundsPersist()
      }
    }
    stage.heightProperty().addListener { (_, _, newH) =>
      val h = newH.doubleValue()
      if (!applyingBounds && !isMaximized && !stage.isIconified && !h.isNaN && h >= stage.getMinHeight) {
        restoreHeight = h
        scheduleBoundsPersist()
      }
    }

    stage.maximizedProperty().addListener { (_, _, nativeMax) =>
      if (!applyingBounds) {
        maximizedProp.set(nativeMax)
        persistDebounce.playFromStart()
      }
    }
  }

  def setMaximized(maximize: Boolean): Unit = {
    if (maximize == isMaximized) return

    applyingBounds = true
    try {
      if (maximize) captureCurrentRestoreBounds()
      maximizedProp.set(maximize)
      stage.setMaximized(maximize)
    } finally {
      applyingBounds = false
    }
    persistDebounce.playFromStart()
  }

  def currentSnapshot: WindowBounds = {
    if (!isMaximized && !stage.isIconified) {
      captureCurrentRestoreBounds()
    }
    WindowBounds(
      x = restoreX,
      y = restoreY,
      width = Math.max(MinWindowWidth, restoreWidth),
      height = Math.max(MinWindowHeight, restoreHeight),
      isMaximized = isMaximized
    )
  }

  private def captureCurrentRestoreBounds(): Unit = {
    val x = stage.getX
    val y = stage.getY
    val w = stage.getWidth
    val h = stage.getHeight
    if (!x.isNaN) restoreX = Some(x)
    if (!y.isNaN) restoreY = Some(y)
    if (!w.isNaN && w >= MinWindowWidth) restoreWidth = w
    if (!h.isNaN && h >= MinWindowHeight) restoreHeight = h
  }

  private def applyInitialBounds(): Unit = {
    applyingBounds = true
    try {
      val allScreens = Screen.getScreens.asScala.toList.map(_.getVisualBounds)
      val primaryVb = Screen.getPrimary.getVisualBounds
      val (cx, cy, cw, ch) = clampBoundsToScreens(restoreX, restoreY, restoreWidth, restoreHeight, allScreens, primaryVb)
      restoreX = cx
      restoreY = cy
      restoreWidth = cw
      restoreHeight = ch
      stage.setWidth(cw)
      stage.setHeight(ch)
    } finally {
      applyingBounds = false
    }
    persistDebounce.playFromStart()
  }

  // Defer X/Y positioning until after stage.show() so macOS Retina outputScale matches the target screen.
  def applySavedPositionAfterShow(): Unit = {
    applyingBounds = true
    try {
      restoreX.foreach(stage.setX)
      restoreY.foreach(stage.setY)
    } finally {
      applyingBounds = false
    }
  }
}
