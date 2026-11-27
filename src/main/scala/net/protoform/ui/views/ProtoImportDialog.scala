package net.protoform.ui.views

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.{DirectoryChooser, FileChooser}
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters._

final case class ProtoImportRequest(
  protoFiles: List[Path],
  includeDirs: List[Path]
)

object ProtoImportDialog {

  def show(initialIncludeDirs: List[String], protocExecutable: Option[String]): Option[ProtoImportRequest] = {
    var result: Option[ProtoImportRequest] = None

    val filesList = new ListView[String]()
    filesList.setPrefHeight(130)
    filesList.setPlaceholder(new Label("No .proto files selected yet."))

    val addFilesBtn = new Button("Add .proto File(s)…", FontIcon.of(Feather.FILE_PLUS, 12))
    addFilesBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")

    val removeFileBtn = new Button("Remove", FontIcon.of(Feather.TRASH_2, 12))
    removeFileBtn.getStyleClass.addAll("small", "danger", "interactive-hand")
    removeFileBtn.setDisable(true)

    val filesHeader = new HBox(8, new Label("Entry .proto Files") { getStyleClass.add("form-label") }, new Region(), addFilesBtn, removeFileBtn)
    HBox.setHgrow(filesHeader.getChildren.get(1), Priority.ALWAYS)
    filesHeader.setAlignment(Pos.CENTER_LEFT)

    val includeList = new ListView[String]()
    includeList.setPrefHeight(120)
    includeList.getItems.addAll(initialIncludeDirs.distinct: _*)
    includeList.setPlaceholder(new Label("Optional -I include root folders (parent dirs are included automatically)."))

    val addIncludeBtn = new Button("Add Include Folder…", FontIcon.of(Feather.FOLDER_PLUS, 12))
    addIncludeBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")

    val removeIncludeBtn = new Button("Remove", FontIcon.of(Feather.TRASH_2, 12))
    removeIncludeBtn.getStyleClass.addAll("small", "danger", "interactive-hand")
    removeIncludeBtn.setDisable(true)

    val includesHeader = new HBox(8, new Label("Import Root Directories (-I)") { getStyleClass.add("form-label") }, new Region(), addIncludeBtn, removeIncludeBtn)
    HBox.setHgrow(includesHeader.getChildren.get(1), Priority.ALWAYS)
    includesHeader.setAlignment(Pos.CENTER_LEFT)

    val protocHint = new Label(
      s"Compiler: ${protocExecutable.map(_.trim).filter(_.nonEmpty).getOrElse("protoc (auto-detected on PATH)")}"
    )
    protocHint.getStyleClass.add("info-strip")
    protocHint.setWrapText(true)

    val content = new VBox(10, filesHeader, filesList, includesHeader, includeList, protocHint)
    content.setPadding(new Insets(14))

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val loadBtn = new Button("Compile & Load", FontIcon.of(Feather.PLAY, 12))
    loadBtn.getStyleClass.addAll("accent", "interactive-hand")
    loadBtn.setDefaultButton(true)
    loadBtn.setDisable(true)

    val stage = CustomDialogStage.createStage(
      title = "Load Raw .proto Source Files",
      subtitle = Some("Compile .proto definitions via protoc into in-memory gRPC schemas"),
      iconCode = Feather.CODE,
      content = content,
      footer = Some(CustomDialogStage.buildFooterBar(cancelBtn, loadBtn)),
      prefWidth = 620,
      prefHeight = 460,
      minWidth = 480,
      minHeight = 360,
      resizable = true
    )

    filesList.getSelectionModel.selectedItemProperty().addListener((_, _, sel) => removeFileBtn.setDisable(sel == null))
    includeList.getSelectionModel.selectedItemProperty().addListener((_, _, sel) => removeIncludeBtn.setDisable(sel == null))

    addFilesBtn.setOnAction { _ =>
      val chooser = new FileChooser()
      chooser.setTitle("Select .proto Files")
      chooser.getExtensionFilters.add(new FileChooser.ExtensionFilter("Protocol Buffer Files (*.proto)", "*.proto"))
      Option(chooser.showOpenMultipleDialog(stage)).foreach { selected =>
        selected.asScala.foreach { f =>
          val p = f.getAbsolutePath
          if (!filesList.getItems.contains(p)) filesList.getItems.add(p)
        }
        loadBtn.setDisable(filesList.getItems.isEmpty)
      }
    }

    removeFileBtn.setOnAction { _ =>
      val idx = filesList.getSelectionModel.getSelectedIndex
      if (idx >= 0) filesList.getItems.remove(idx)
      loadBtn.setDisable(filesList.getItems.isEmpty)
    }

    addIncludeBtn.setOnAction { _ =>
      val chooser = new DirectoryChooser()
      chooser.setTitle("Select Protobuf Include Root (-I)")
      Option(chooser.showDialog(stage)).foreach { dir =>
        val p = dir.getAbsolutePath
        if (!includeList.getItems.contains(p)) includeList.getItems.add(p)
      }
    }

    removeIncludeBtn.setOnAction { _ =>
      val idx = includeList.getSelectionModel.getSelectedIndex
      if (idx >= 0) includeList.getItems.remove(idx)
    }

    cancelBtn.setOnAction(_ => stage.close())
    loadBtn.setOnAction { _ =>
      val files = filesList.getItems.asScala.toList.map(Paths.get(_))
      val includes = includeList.getItems.asScala.toList.map(Paths.get(_))
      result = Some(ProtoImportRequest(files, includes))
      stage.close()
    }

    stage.showAndWait()
    result
  }
}
