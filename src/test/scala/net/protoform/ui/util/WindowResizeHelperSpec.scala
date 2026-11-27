package net.protoform.ui.util

import javafx.scene.Cursor
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WindowResizeHelperSpec extends AnyFunSuite with Matchers {

  private val sceneW = 1200.0
  private val sceneH = 800.0

  test("resolveResizeCursor detects all 8 edges and corners with safe top margin") {
    WindowResizeHelper.resolveResizeCursor(2.0, 2.0, sceneW, sceneH) shouldBe Cursor.NW_RESIZE
    WindowResizeHelper.resolveResizeCursor(1198.0, 2.0, sceneW, sceneH) shouldBe Cursor.NE_RESIZE
    WindowResizeHelper.resolveResizeCursor(2.0, 798.0, sceneW, sceneH) shouldBe Cursor.SW_RESIZE
    WindowResizeHelper.resolveResizeCursor(1198.0, 798.0, sceneW, sceneH) shouldBe Cursor.SE_RESIZE

    // Corner margin extends to 14px on bottom/sides without encroaching on titlebar controls
    WindowResizeHelper.resolveResizeCursor(1188.0, 795.0, sceneW, sceneH) shouldBe Cursor.SE_RESIZE
    WindowResizeHelper.resolveResizeCursor(12.0, 795.0, sceneW, sceneH) shouldBe Cursor.SW_RESIZE

    WindowResizeHelper.resolveResizeCursor(600.0, 3.0, sceneW, sceneH) shouldBe Cursor.N_RESIZE
    WindowResizeHelper.resolveResizeCursor(600.0, 795.0, sceneW, sceneH) shouldBe Cursor.S_RESIZE
    WindowResizeHelper.resolveResizeCursor(4.0, 400.0, sceneW, sceneH) shouldBe Cursor.W_RESIZE
    WindowResizeHelper.resolveResizeCursor(1196.0, 400.0, sceneW, sceneH) shouldBe Cursor.E_RESIZE

    // Inside titlebar button zone (y = 8px) and interior should remain DEFAULT
    WindowResizeHelper.resolveResizeCursor(600.0, 8.0, sceneW, sceneH) shouldBe Cursor.DEFAULT
    WindowResizeHelper.resolveResizeCursor(600.0, 400.0, sceneW, sceneH) shouldBe Cursor.DEFAULT
    WindowResizeHelper.resolveResizeCursor(0.0, 0.0, 0.0, 0.0) shouldBe Cursor.DEFAULT
  }

  test("computeResizedBounds expands and shrinks E/S edges respecting minimums") {
    val start = StageBounds(100.0, 80.0, 1200.0, 800.0)

    val expanded = WindowResizeHelper.computeResizedBounds(Cursor.SE_RESIZE, start, 150.0, 90.0, 1000.0, 640.0)
    expanded shouldBe StageBounds(100.0, 80.0, 1350.0, 890.0)

    val clamped = WindowResizeHelper.computeResizedBounds(Cursor.SE_RESIZE, start, -500.0, -500.0, 1000.0, 640.0)
    clamped shouldBe StageBounds(100.0, 80.0, 1000.0, 640.0)
  }

  test("computeResizedBounds preserves opposite anchor on W/N edges and clamps against macOS menu bar") {
    val start = StageBounds(200.0, 60.0, 1200.0, 760.0)
    val anchorRight = start.x + start.width
    val anchorBottom = start.y + start.height

    val draggedNW = WindowResizeHelper.computeResizedBounds(
      cursor = Cursor.NW_RESIZE,
      start = start,
      deltaX = -50.0,
      deltaY = -100.0,
      minWidth = 1000.0,
      minHeight = 640.0,
      minAllowedY = 25.0
    )
    draggedNW.x shouldBe 150.0
    draggedNW.x + draggedNW.width shouldBe anchorRight
    draggedNW.y shouldBe 25.0
    draggedNW.y + draggedNW.height shouldBe anchorBottom

    val shrunkNW = WindowResizeHelper.computeResizedBounds(
      cursor = Cursor.NW_RESIZE,
      start = start,
      deltaX = 400.0,
      deltaY = 300.0,
      minWidth = 1000.0,
      minHeight = 640.0,
      minAllowedY = 25.0
    )
    shrunkNW.width shouldBe 1000.0
    shrunkNW.height shouldBe 640.0
    shrunkNW.x + shrunkNW.width shouldBe anchorRight
    shrunkNW.y + shrunkNW.height shouldBe anchorBottom
  }
}
