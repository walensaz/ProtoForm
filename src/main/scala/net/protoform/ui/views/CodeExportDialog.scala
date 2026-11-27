package net.protoform.ui.views

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.core.auth.MetadataHeader
import net.protoform.core.call._
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.{MethodSchema, ServiceSchema}
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.control.NonFatal

object CodeExportDialog {

  def show(
    target: EndpointTarget,
    method: MethodSchema,
    service: Option[ServiceSchema],
    resolvedPayload: String,
    resolvedHeaders: List[MetadataHeader],
    callOptions: RpcCallOptions,
    streamPayloads: List[String] = Nil,
    onCopied: String => Unit = _ => ()
  ): Unit = {
    val modeGroup = new ToggleGroup()
    val pyStubsBtn = new ToggleButton("Python (grpcio Stubs)")
    pyStubsBtn.setToggleGroup(modeGroup)
    pyStubsBtn.setSelected(true)
    pyStubsBtn.getStyleClass.addAll("small", "interactive-hand")

    val pyReflBtn = new ToggleButton("Python (Reflection)")
    pyReflBtn.setToggleGroup(modeGroup)
    pyReflBtn.getStyleClass.addAll("small", "interactive-hand")

    val grpcurlBtn = new ToggleButton("grpcurl CLI")
    grpcurlBtn.setToggleGroup(modeGroup)
    grpcurlBtn.getStyleClass.addAll("small", "interactive-hand")

    val modeBar = new HBox(6, pyStubsBtn, pyReflBtn, grpcurlBtn)
    modeBar.setAlignment(Pos.CENTER_LEFT)

    val (_, _, inferredPkgOpt) = PythonSnippetGenerator.inferStubModules(service.flatMap(_.protoFile), method.serviceFullName)
    val prefixField = new TextField(inferredPkgOpt.getOrElse(""))
    prefixField.setPromptText("Optional Python package (e.g. app.generated)")
    prefixField.setPrefWidth(230)

    val prefixLabel = new Label("Package:")
    prefixLabel.getStyleClass.add("form-subtext")
    val prefixBox = new HBox(6, prefixLabel, prefixField)
    prefixBox.setAlignment(Pos.CENTER_LEFT)

    val commentsCheck = new CheckBox("Include comments")
    commentsCheck.setSelected(true)
    commentsCheck.getStyleClass.add("interactive-hand")

    val optionsRow = new FlowPane(12, 6, prefixBox, commentsCheck)
    optionsRow.setAlignment(Pos.CENTER_LEFT)

    val hintLabel = new Label("")
    hintLabel.getStyleClass.add("form-subtext")
    hintLabel.setWrapText(true)

    val codeArea = new TextArea()
    codeArea.setEditable(false)
    codeArea.setWrapText(false)
    codeArea.getStyleClass.add("mono-text")
    VBox.setVgrow(codeArea, Priority.ALWAYS)

    def refreshPreview(): Unit = {
      val isStubs = pyStubsBtn.isSelected
      val isRefl = pyReflBtn.isSelected
      prefixBox.setVisible(isStubs)
      prefixBox.setManaged(isStubs)
      commentsCheck.setVisible(isStubs || isRefl)
      commentsCheck.setManaged(isStubs || isRefl)

      if (isStubs) {
        hintLabel.setText("Requires grpcio & protobuf plus generated *_pb2.py / *_pb2_grpc.py stubs (python -m grpc_tools.protoc ...).")
        codeArea.setText(PythonSnippetGenerator.generate(
          target = target,
          method = method,
          service = service,
          requestJson = resolvedPayload,
          headers = resolvedHeaders,
          callOptions = callOptions,
          config = PythonExportConfig(
            mode = PythonExportMode.Stubs,
            modulePrefix = prefixField.getText,
            includeComments = commentsCheck.isSelected,
            streamPayloads = streamPayloads
          )
        ))
      } else if (isRefl) {
        hintLabel.setText("Zero generated stubs required — resolves descriptors dynamically via Server Reflection (pip install grpcio grpcio-reflection).")
        codeArea.setText(PythonSnippetGenerator.generate(
          target = target,
          method = method,
          service = service,
          requestJson = resolvedPayload,
          headers = resolvedHeaders,
          callOptions = callOptions,
          config = PythonExportConfig(
            mode = PythonExportMode.Reflection,
            includeComments = commentsCheck.isSelected,
            streamPayloads = streamPayloads
          )
        ))
      } else {
        hintLabel.setText("Equivalent single-line grpcurl CLI invocation.")
        codeArea.setText(GrpcurlFormatter.format(target, method, resolvedPayload, resolvedHeaders))
      }
    }

    modeGroup.selectedToggleProperty().addListener { (_, _, newVal) =>
      if (newVal == null) pyStubsBtn.setSelected(true)
      refreshPreview()
    }
    prefixField.textProperty().addListener((_, _, _) => refreshPreview())
    commentsCheck.selectedProperty().addListener((_, _, _) => refreshPreview())

    refreshPreview()

    val saveBtn = new Button("Save File…", FontIcon.of(Feather.SAVE, 13))
    saveBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val copyBtn = new Button("Copy to Clipboard", FontIcon.of(Feather.COPY, 13))
    copyBtn.getStyleClass.addAll("accent", "interactive-hand")
    copyBtn.setDefaultButton(true)

    val closeBtn = new Button("Close")
    closeBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val footerSpacer = new Region()
    HBox.setHgrow(footerSpacer, Priority.ALWAYS)
    val footer = new HBox(8, saveBtn, footerSpacer, closeBtn, copyBtn)
    footer.setAlignment(Pos.CENTER_RIGHT)
    footer.setPadding(new Insets(10, 16, 14, 16))
    footer.getStyleClass.add("dialog-footer-bar")

    val content = new VBox(10, modeBar, optionsRow, hintLabel, codeArea)
    content.setPadding(new Insets(14, 16, 8, 16))

    val stage = CustomDialogStage.createStage(
      title = s"Export Code — ${method.name}",
      iconCode = Feather.CODE,
      content = content,
      subtitle = Some(s"${method.fullMethodName} (${method.pattern.label})"),
      footer = Some(footer),
      prefWidth = 760,
      prefHeight = 580,
      minWidth = 540,
      minHeight = 420
    )

    copyBtn.setOnAction { _ =>
      val contentClip = new ClipboardContent()
      contentClip.putString(codeArea.getText)
      Clipboard.getSystemClipboard.setContent(contentClip)
      val kind = if (grpcurlBtn.isSelected) "grpcurl command" else "Python snippet"
      onCopied(s"Copied $kind for ${method.name}.")
      stage.close()
    }

    saveBtn.setOnAction { _ =>
      val chooser = new FileChooser()
      val isCli = grpcurlBtn.isSelected
      chooser.setTitle(if (isCli) "Save grpcurl Command" else "Save Python Example")
      val defaultBase = method.name.replaceAll("[^A-Za-z0-9_.-]", "_")
      chooser.setInitialFileName(if (isCli) s"$defaultBase.sh" else s"${defaultBase}_client.py")
      Option(chooser.showSaveDialog(stage)).foreach { file =>
        try {
          Files.write(file.toPath, codeArea.getText.getBytes(StandardCharsets.UTF_8))
          onCopied(s"Saved ${file.getName}.")
        } catch {
          case NonFatal(e) =>
            CustomDialogStage.showErrorAlert("Save Failed", Option(e.getMessage).getOrElse("Could not write file."))
        }
      }
    }

    closeBtn.setOnAction(_ => stage.close())
    stage.showAndWait()
  }
}
