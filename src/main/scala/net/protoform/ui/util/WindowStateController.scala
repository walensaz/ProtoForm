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

class WindowStateController(
  stage: Stage,
  initialWidth: Double,
  initialHeight: Double,
  initialX: Option[Double],
  initialY: Option[Double],
  onBoundsChanged: WindowBounds => Unit = _ => ()
) {
  private val maximizedProp: BooleanProperty = new SimpleBooleanProperty(false)
  private var applyingBounds: Boolean = false
  private val persistDebounce = new PauseTransition(Duration.millis(400))
  persistDebounce.setOnFinished(_ => onBoundsChanged(currentSnapshot))

  private var restoreWidth: Double = Math.max(1000.0, initialWidth)
  private var restoreHeight: Double = Math.max(640.0, initialHeight)
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

    // Intercept native OS maximize (e.g. Win+Up) on undecorated stage so it respects taskbar/dock visualBounds.
    stage.maximizedProperty().addListener { (_, _, nativeMax) =>
      if (nativeMax) {
        javafx.application.Platform.runLater { () =>
          stage.setMaximized(false)
          setMaximized(true)
        }
      }
    }
  }

  def toggleMaximize(): Unit = {
    setMaximized(!isMaximized)
  }

  def setMaximized(maximize: Boolean): Unit = {
    if (maximize == isMaximized) return

    applyingBounds = true
    try {
      if (maximize) {
        captureCurrentRestoreBounds()
        val vb = resolveActiveScreenVisualBounds()
        maximizedProp.set(true)
        stage.setX(vb.getMinX)
        stage.setY(vb.getMinY)
        stage.setWidth(vb.getWidth)
        stage.setHeight(vb.getHeight)
      } else {
        maximizedProp.set(false)
        val clamped = clampToVisibleScreen(restoreX, restoreY, restoreWidth, restoreHeight)
        stage.setWidth(clamped._3)
        stage.setHeight(clamped._4)
        clamped._1.foreach(stage.setX)
        clamped._2.foreach(stage.setY)
      }
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
      width = Math.max(1000.0, restoreWidth),
      height = Math.max(640.0, restoreHeight),
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
    if (!w.isNaN && w >= 1000.0) restoreWidth = w
    if (!h.isNaN && h >= 640.0) restoreHeight = h
  }

  private def applyInitialBounds(): Unit = {
    applyingBounds = true
    try {
      val (cx, cy, cw, ch) = clampToVisibleScreen(restoreX, restoreY, restoreWidth, restoreHeight)
      restoreX = cx
      restoreY = cy
      restoreWidth = cw
      restoreHeight = ch
      stage.setWidth(cw)
      stage.setHeight(ch)
      cx.foreach(stage.setX)
      cy.foreach(stage.setY)
    } finally {
      applyingBounds = false
    }
    persistDebounce.playFromStart()
  }

  private def resolveActiveScreenVisualBounds(): Rectangle2D = {
    val x = if (!stage.getX.isNaN) stage.getX else 0.0
    val y = if (!stage.getY.isNaN) stage.getY else 0.0
    val w = if (!stage.getWidth.isNaN && stage.getWidth > 0) stage.getWidth else restoreWidth
    val h = if (!stage.getHeight.isNaN && stage.getHeight > 0) stage.getHeight else restoreHeight

    val screens = Screen.getScreensForRectangle(x, y, Math.max(1.0, w), Math.max(1.0, h)).asScala
    val chosen = screens.headOption.getOrElse(Screen.getPrimary)
    chosen.getVisualBounds
  }

  private def clampToVisibleScreen(
    xOpt: Option[Double],
    yOpt: Option[Double],
    w: Double,
    h: Double
  ): (Option[Double], Option[Double], Double, Double) = {
    val allScreens = Screen.getScreens.asScala.toList
    val primaryVb = Screen.getPrimary.getVisualBounds

    (xOpt, yOpt) match {
      case (Some(x), Some(y)) if !x.isNaN && !y.isNaN =>
        val matchingScreen = allScreens.find(_.getVisualBounds.intersects(x, y, Math.max(200.0, w), Math.max(200.0, h)))
        val vb = matchingScreen.map(_.getVisualBounds).getOrElse(primaryVb)
        val clampedW = Math.min(Math.max(1000.0, w), vb.getWidth)
        val clampedH = Math.min(Math.max(640.0, h), vb.getHeight)
        val clampedX = Math.max(vb.getMinX, Math.min(x, vb.getMaxX - Math.min(clampedW, 200.0)))
        val clampedY = Math.max(vb.getMinY, Math.min(y, vb.getMaxY - Math.min(clampedH, 120.0)))
        (Some(clampedX), Some(clampedY), clampedW, clampedH)
      case _ =>
        val clampedW = Math.min(Math.max(1000.0, w), primaryVb.getWidth)
        val clampedH = Math.min(Math.max(640.0, h), primaryVb.getHeight)
        (None, None, clampedW, clampedH)
    }
  }
}
