package net.protoform.ui.editor

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import net.protoform.application.FormSchemaService
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

class JsonEditorPane extends VBox(8) {
  setPadding(new Insets(0))

  val codeEditor = new JsonCodeArea(editable = true)
  codeEditor.setText("{}")
  VBox.setVgrow(codeEditor, Priority.ALWAYS)

  val toolbar = new HBox(8)
  toolbar.setAlignment(Pos.CENTER_LEFT)

  val formatButton = new Button("Format JSON", FontIcon.of(Feather.ALIGN_LEFT, 12))
  formatButton.getStyleClass.addAll("small", "subtle", "interactive-hand")
  formatButton.setOnAction(_ => formatCurrentJson())

  val clearButton = new Button("Clear", FontIcon.of(Feather.TRASH_2, 12))
  clearButton.getStyleClass.addAll("small", "subtle", "interactive-hand")
  clearButton.setOnAction { _ =>
    val trimmed = Option(codeEditor.getText).map(_.trim).getOrElse("")
    if (trimmed.isEmpty || trimmed == "{}" || CustomDialogStage.showConfirm(
      title = "Clear JSON Payload?",
      header = "Reset JSON editor to '{}'?",
      message = "This will clear the current JSON request body.",
      confirmLabel = "Clear",
      destructive = true
    )) {
      codeEditor.setText("{}")
    }
  }

  val formatErrorLabel = new Label("")
  formatErrorLabel.getStyleClass.add("status-text-error")

  toolbar.getChildren.addAll(formatButton, clearButton, formatErrorLabel)
  getChildren.addAll(toolbar, codeEditor)

  def getText: String = codeEditor.getText

  def setText(text: String): Unit = {
    formatErrorLabel.setText("")
    codeEditor.setText(text)
  }

  def formatCurrentJson(): Unit = {
    FormSchemaService.formatJson(codeEditor.getText) match {
      case Right(formatted) =>
        formatErrorLabel.setText("")
        codeEditor.setText(formatted)
      case Left(err) =>
        formatErrorLabel.setText(s"Invalid JSON: $err")
    }
  }
}
