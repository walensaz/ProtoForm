package net.protoform.ui.util

import javafx.geometry.Rectangle2D
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WindowStateControllerSpec extends AnyFunSuite with Matchers {

  private val primary = new Rectangle2D(0.0, 38.0, 1512.0, 900.0)
  private val secondary = new Rectangle2D(1512.0, 0.0, 2560.0, 1400.0)

  test("clampBoundsToScreens keeps valid coordinates and enforces min dimensions") {
    val (x, y, w, h) = WindowStateController.clampBoundsToScreens(
      xOpt = Some(120.0),
      yOpt = Some(80.0),
      w = 600.0,
      h = 400.0,
      screens = List(primary, secondary),
      primaryVb = primary
    )

    x shouldBe Some(120.0)
    y shouldBe Some(80.0)
    w shouldBe WindowStateController.MinWindowWidth
    h shouldBe WindowStateController.MinWindowHeight
  }

  test("clampBoundsToScreens clamps oversized dimensions and off-screen coordinates to matching monitor") {
    val (x, y, w, h) = WindowStateController.clampBoundsToScreens(
      xOpt = Some(-300.0),
      yOpt = Some(10.0),
      w = 3000.0,
      h = 1500.0,
      screens = List(primary, secondary),
      primaryVb = primary
    )

    x shouldBe Some(0.0)
    y shouldBe Some(38.0)
    w shouldBe 1512.0
    h shouldBe 900.0
  }

  test("clampBoundsToScreens preserves secondary display coordinates") {
    val (x, y, w, h) = WindowStateController.clampBoundsToScreens(
      xOpt = Some(1700.0),
      yOpt = Some(100.0),
      w = 1480.0,
      h = 920.0,
      screens = List(primary, secondary),
      primaryVb = primary
    )

    x shouldBe Some(1700.0)
    y shouldBe Some(100.0)
    w shouldBe 1480.0
    h shouldBe 920.0
  }
}
