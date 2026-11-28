package net.protoform

object Launcher {
  def main(args: Array[String]): Unit = {
    System.setProperty("javafx.enablePreview", "true")
    System.setProperty("javafx.suppressPreviewWarning", "true")
    ProtoFormApp.main(args)
  }
}
