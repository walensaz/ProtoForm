package net.protoform.ui.theme

import atlantafx.base.theme._
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Dialog
import javafx.scene.image.Image
import javafx.scene.text.Font
import javafx.stage.{Stage, Window}

import scala.collection.mutable.ListBuffer

object ThemeManager {

  val DefaultTheme: String = "ProtoForm Carbon"

  val themes: List[String] = List(
    "ProtoForm Carbon",
    "ProtoForm Paper",
    "Primer Dark",
    "Primer Light",
    "Nord Dark",
    "Cupertino Dark",
    "Cupertino Light"
  )

  def normalizeThemeName(themeName: String): String = {
    themeName match {
      case "FlexRPC Carbon" => "ProtoForm Carbon"
      case "FlexRPC Paper"  => "ProtoForm Paper"
      case other if themes.contains(other) => other
      case _ => DefaultTheme
    }
  }

  private var fontsLoaded: Boolean = false
  private var activeThemeName: String = DefaultTheme
  private var primaryScene: Option[Scene] = None
  private var primaryWindow: Option[Window] = None
  private val secondaryScenes = ListBuffer.empty[Scene]

  private val managedStylesheetPaths: List[String] = List(
    "/styles/tokens.css",
    "/styles/theme-carbon.css",
    "/styles/theme-light.css",
    "/styles/theme-paper.css",
    "/styles/custom.css"
  )

  def loadFonts(): Unit = {
    if (!fontsLoaded) {
      val fontPaths = List(
        "/fonts/IBMPlexSans-Regular.ttf",
        "/fonts/IBMPlexSans-Medium.ttf",
        "/fonts/IBMPlexSans-SemiBold.ttf",
        "/fonts/IBMPlexSans-Bold.ttf",
        "/fonts/JetBrainsMono-Regular.ttf",
        "/fonts/JetBrainsMono-Bold.ttf"
      )
      fontPaths.foreach { path =>
        val stream = getClass.getResourceAsStream(path)
        if (stream != null) {
          try {
            Font.loadFont(stream, 13.5)
          } finally {
            stream.close()
          }
        }
      }
      fontsLoaded = true
    }
  }

  private val iconPaths: List[String] = List(
    "/icons/protoform-16.png",
    "/icons/protoform-32.png",
    "/icons/protoform-48.png",
    "/icons/protoform-128.png",
    "/icons/protoform-256.png"
  )

  lazy val appIcons: List[Image] = iconPaths.flatMap { path =>
    Option(getClass.getResourceAsStream(path)).map { stream =>
      try {
        new Image(stream)
      } finally {
        stream.close()
      }
    }
  }

  def applyStageIcons(stage: Stage): Unit = {
    if (stage != null && appIcons.nonEmpty) {
      stage.getIcons.setAll(appIcons: _*)
    }
  }

  def registerScene(scene: Scene, owner: Window): Unit = {
    primaryScene = Some(scene)
    primaryWindow = Some(owner)
    syncStylesheets(scene.getStylesheets, activeThemeName)
  }

  def registerSecondaryScene(scene: Scene): Unit = {
    if (!secondaryScenes.contains(scene)) {
      secondaryScenes += scene
    }
    syncStylesheets(scene.getStylesheets, activeThemeName)
  }

  def unregisterSecondaryScene(scene: Scene): Unit = {
    secondaryScenes -= scene
  }

  def getPrimaryWindow: Option[Window] = primaryWindow

  def applyTheme(themeName: String): Unit = {
    val normalized = normalizeThemeName(themeName)
    activeThemeName = normalized

    val baseTheme: Theme = normalized match {
      case "ProtoForm Carbon" => new PrimerDark()
      case "ProtoForm Paper"  => new PrimerLight()
      case "Primer Light"     => new PrimerLight()
      case "Nord Dark"        => new NordDark()
      case "Cupertino Dark"   => new CupertinoDark()
      case "Cupertino Light"  => new CupertinoLight()
      case _                  => new PrimerDark()
    }

    Application.setUserAgentStylesheet(baseTheme.getUserAgentStylesheet)
    primaryScene.foreach(s => syncStylesheets(s.getStylesheets, normalized))
    secondaryScenes.foreach(s => syncStylesheets(s.getStylesheets, normalized))
  }

  def styleDialog(dialog: Dialog[_]): Unit = {
    primaryWindow.foreach { w =>
      if (dialog.getOwner == null) {
        dialog.initOwner(w)
      }
    }
    syncStylesheets(dialog.getDialogPane.getStylesheets, activeThemeName)
  }

  private def stylesheetsForTheme(themeName: String): List[String] = {
    val middle = themeName match {
      case "ProtoForm Carbon" => List("/styles/theme-carbon.css")
      case "ProtoForm Paper"  => List("/styles/theme-light.css", "/styles/theme-paper.css")
      case "Primer Light"     => List("/styles/theme-light.css")
      case "Cupertino Light"  => List("/styles/theme-light.css")
      case _                  => Nil
    }
    ("/styles/tokens.css" :: middle) :+ "/styles/custom.css"
  }

  private def syncStylesheets(stylesheets: javafx.collections.ObservableList[String], themeName: String): Unit = {
    val allManagedUrls = managedStylesheetPaths.flatMap(p => Option(getClass.getResource(p)).map(_.toExternalForm))
    stylesheets.removeAll(allManagedUrls: _*)

    val desiredUrls = stylesheetsForTheme(themeName).flatMap(p => Option(getClass.getResource(p)).map(_.toExternalForm))
    stylesheets.addAll(desiredUrls: _*)
  }
}
