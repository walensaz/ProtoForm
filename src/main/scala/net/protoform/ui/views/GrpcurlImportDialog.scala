package net.protoform.ui.views

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import net.protoform.core.call.{GrpcurlParser, ParsedGrpcurlCommand}
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

object GrpcurlImportDialog {

  def show(): Option[ParsedGrpcurlCommand] = {
    var result: Option[ParsedGrpcurlCommand] = None

    val inputArea = new TextArea()
    inputArea.setPromptText(
      """Paste a grpcurl command, e.g.:
        |grpcurl -plaintext -H "Authorization: Bearer token" -d '{"id": "42"}' localhost:50051 order.OrderService/GetOrder""".stripMargin
    )
    inputArea.setWrapText(true)
    inputArea.getStyleClass.add("mono-text")
    VBox.setVgrow(inputArea, Priority.ALWAYS)

    val errorLbl = new Label()
    errorLbl.getStyleClass.add("status-text-error")
    errorLbl.setWrapText(true)
    errorLbl.setVisible(false)
    errorLbl.setManaged(false)

    val content = new VBox(10, new Label("grpcurl Command") { getStyleClass.add("form-label") }, inputArea, errorLbl)
    content.setPadding(new Insets(14))

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val importBtn = new Button("Import Command", FontIcon.of(Feather.DOWNLOAD, 12))
    importBtn.getStyleClass.addAll("accent", "interactive-hand")
    importBtn.setDefaultButton(true)

    val stage = CustomDialogStage.createStage(
      title = "Import grpcurl Command",
      subtitle = Some("Populate endpoint, TLS/mTLS flags, headers, method, and payload from a grpcurl snippet"),
      iconCode = Feather.TERMINAL,
      content = content,
      footer = Some(CustomDialogStage.buildFooterBar(cancelBtn, importBtn)),
      prefWidth = 640,
      prefHeight = 360,
      minWidth = 480,
      minHeight = 280,
      resizable = true
    )

    cancelBtn.setOnAction(_ => stage.close())
    importBtn.setOnAction { _ =>
      GrpcurlParser.parse(inputArea.getText) match {
        case Right(parsed) =>
          result = Some(parsed)
          stage.close()
        case Left(err) =>
          errorLbl.setText(err)
          errorLbl.setVisible(true)
          errorLbl.setManaged(true)
      }
    }

    stage.showAndWait()
    result
  }
}
