package net.protoform.ui.views

import javafx.application.Platform
import javafx.beans.property.{SimpleLongProperty, SimpleStringProperty}
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.application.{ConnectionService, SuiteCaseResult, SuiteRunSummary, SuiteRunnerService, WorkspaceService}
import net.protoform.core.call.RpcCallOptions
import net.protoform.core.model.EndpointTarget
import net.protoform.core.schema.ServiceSchema
import net.protoform.core.workspace.ServiceTemplate
import net.protoform.ui.editor.JsonCodeArea
import net.protoform.ui.state.AppState
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._

object SuiteRunnerDialog {
  private val AllTagsOption = "All Tags"

  def show(
    appState: AppState,
    templates: List[ServiceTemplate],
    services: List[ServiceSchema],
    connectionService: ConnectionService,
    suiteRunnerService: SuiteRunnerService,
    workspaceService: WorkspaceService,
    resolveTarget: () => Either[String, EndpointTarget]
  ): Unit = {
    val tagCombo = new ComboBox[String]()
    tagCombo.getStyleClass.addAll("small", "interactive-hand")
    val allTags = templates.flatMap(_.tags).map(_.trim).filter(_.nonEmpty).distinct.sorted
    tagCombo.getItems.add(AllTagsOption)
    tagCombo.getItems.addAll(allTags: _*)
    tagCombo.setValue(AllTagsOption)

    val filterField = new TextField()
    filterField.setPromptText("Filter templates by name or method...")
    HBox.setHgrow(filterField, Priority.ALWAYS)

    val matchCountLbl = new Label("")
    matchCountLbl.getStyleClass.add("info-strip")

    val runBtn = new Button("Run Suite", FontIcon.of(Feather.PLAY, 12))
    runBtn.getStyleClass.addAll("accent", "interactive-hand")

    val stopBtn = new Button("Stop", FontIcon.of(Feather.SQUARE, 12))
    stopBtn.getStyleClass.addAll("danger", "interactive-hand")
    stopBtn.setDisable(true)

    val exportBtn = new Button("Export Report…", FontIcon.of(Feather.UPLOAD, 12))
    exportBtn.getStyleClass.addAll("subtle", "interactive-hand")
    exportBtn.setDisable(true)

    val topBar = new HBox(8, new Label("Tag:") { getStyleClass.add("form-label") }, tagCombo, filterField, matchCountLbl, runBtn, stopBtn, exportBtn)
    topBar.setAlignment(Pos.CENTER_LEFT)

    val progressBar = new ProgressBar(0.0)
    progressBar.setMaxWidth(Double.MaxValue)
    HBox.setHgrow(progressBar, Priority.ALWAYS)

    val passedPill = new Label("Passed: 0")
    passedPill.getStyleClass.addAll("status-badge", "status-badge-ok")
    val failedPill = new Label("Failed: 0")
    failedPill.getStyleClass.addAll("status-badge", "status-badge-error")
    val durationPill = new Label("0 ms")
    durationPill.getStyleClass.add("latency-pill")

    val summaryBar = new HBox(10, progressBar, passedPill, failedPill, durationPill)
    summaryBar.setAlignment(Pos.CENTER_LEFT)

    val table = new TableView[SuiteCaseResult]()
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
    table.setPlaceholder(new Label("Select a tag or filter and click Run Suite."))

    val colPass = new TableColumn[SuiteCaseResult, String]("Result")
    colPass.setCellValueFactory(d => new SimpleStringProperty(if (d.getValue.passed) "PASS" else "FAIL"))
    colPass.setPrefWidth(68)

    val colName = new TableColumn[SuiteCaseResult, String]("Template")
    colName.setCellValueFactory(d => new SimpleStringProperty(d.getValue.template.name))
    colName.setPrefWidth(180)

    val colMethod = new TableColumn[SuiteCaseResult, String]("Method")
    colMethod.setCellValueFactory(d => new SimpleStringProperty(d.getValue.template.fullMethodName))
    colMethod.setPrefWidth(220)

    val colExp = new TableColumn[SuiteCaseResult, String]("Expected")
    colExp.setCellValueFactory(d => new SimpleStringProperty(d.getValue.expectedStatus))
    colExp.setPrefWidth(115)

    val colAct = new TableColumn[SuiteCaseResult, String]("Actual")
    colAct.setCellValueFactory(d => new SimpleStringProperty(d.getValue.actualStatusName))
    colAct.setPrefWidth(125)

    val colLat = new TableColumn[SuiteCaseResult, java.lang.Long]("Latency (ms)")
    colLat.setCellValueFactory(d => new SimpleLongProperty(d.getValue.latencyMs).asObject())
    colLat.setPrefWidth(95)

    table.getColumns.addAll(colPass, colName, colMethod, colExp, colAct, colLat)

    val reqArea = new JsonCodeArea(editable = false)
    val respArea = new JsonCodeArea(editable = false)
    val reqBox = new VBox(4, new Label("Resolved Request") { getStyleClass.add("form-subtext") }, reqArea)
    val respBox = new VBox(4, new Label("Response / Error") { getStyleClass.add("form-subtext") }, respArea)
    VBox.setVgrow(reqArea, Priority.ALWAYS)
    VBox.setVgrow(respArea, Priority.ALWAYS)
    val inspectorSplit = new SplitPane(reqBox, respBox)
    inspectorSplit.setDividerPositions(0.45)

    val mainSplit = new SplitPane(table, inspectorSplit)
    mainSplit.setOrientation(javafx.geometry.Orientation.VERTICAL)
    mainSplit.setDividerPositions(0.55)
    VBox.setVgrow(mainSplit, Priority.ALWAYS)

    table.getSelectionModel.selectedItemProperty().addListener { (_, _, item) =>
      if (item != null) {
        reqArea.setText(item.requestJsonResolved)
        respArea.setText(if (item.responseBodyJson.nonEmpty) item.responseBodyJson else item.errorMessage.getOrElse("{}"))
      }
    }

    def filteredTemplates: List[ServiceTemplate] = {
      val selectedTag = Option(tagCombo.getValue).getOrElse(AllTagsOption)
      val q = Option(filterField.getText).map(_.trim.toLowerCase).getOrElse("")
      templates.filter { t =>
        val tagOk = selectedTag == AllTagsOption || t.tags.exists(_.equalsIgnoreCase(selectedTag))
        val textOk = q.isEmpty || t.name.toLowerCase.contains(q) || t.fullMethodName.toLowerCase.contains(q)
        tagOk && textOk
      }
    }

    def refreshCount(): Unit = {
      val count = filteredTemplates.size
      matchCountLbl.setText(s"$count template${if (count == 1) "" else "s"}")
      runBtn.setDisable(count == 0)
    }

    tagCombo.valueProperty().addListener((_, _, _) => refreshCount())
    filterField.textProperty().addListener((_, _, _) => refreshCount())
    refreshCount()

    var cancelFlag = new AtomicBoolean(false)
    var lastSummary: Option[SuiteRunSummary] = None

    val body = new VBox(10, topBar, summaryBar, mainSplit)
    body.setPadding(new Insets(12))

    val closeBtn = new Button("Close")
    closeBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val stage = CustomDialogStage.createStage(
      title = "QA Batch Suite Runner",
      subtitle = Some("Execute tag-filtered ServiceTemplates sequentially and verify expected gRPC status codes"),
      iconCode = Feather.CHECK_SQUARE,
      content = body,
      footer = Some(CustomDialogStage.buildFooterBar(closeBtn)),
      prefWidth = 940,
      prefHeight = 660,
      minWidth = 700,
      minHeight = 480,
      resizable = true,
      onCloseRequested = Some { s =>
        cancelFlag.set(true)
        s.close()
      }
    )

    closeBtn.setOnAction { _ =>
      cancelFlag.set(true)
      stage.close()
    }

    stopBtn.setOnAction { _ =>
      cancelFlag.set(true)
      stopBtn.setDisable(true)
    }

    runBtn.setOnAction { _ =>
      val toRun = filteredTemplates
      if (toRun.nonEmpty) {
        resolveTarget() match {
          case Left(err) =>
            CustomDialogStage.showErrorAlert("Invalid Target Endpoint", err)
          case Right(target) =>
            val settings = workspaceService.getSettings
            connectionService.connect(target, settings.maxInboundMessageMb) match {
              case Left(connErr) =>
                CustomDialogStage.showErrorAlert("Connection Failed", connErr.message)
              case Right(channel) =>
                cancelFlag = new AtomicBoolean(false)
                lastSummary = None
                table.getItems.clear()
                reqArea.clear()
                respArea.clear()
                progressBar.setProgress(0.0)
                passedPill.setText("Passed: 0")
                failedPill.setText("Failed: 0")
                durationPill.setText("Running...")
                runBtn.setDisable(true)
                stopBtn.setDisable(false)
                exportBtn.setDisable(true)

                val opts = RpcCallOptions(
                  deadlineSeconds = settings.requestDeadlineSeconds,
                  useGzipCompression = appState.useGzipCompression.get,
                  includeDefaultFields = appState.showDefaultValues.get,
                  preserveProtoFieldNames = appState.preserveProtoFieldNames.get
                )
                val total = toRun.size.toDouble

                suiteRunnerService.runSuite(
                  templates = toRun,
                  services = services,
                  channel = channel,
                  target = target,
                  variables = appState.activeEnvVariables,
                  callOptions = opts,
                  cancelFlag = cancelFlag,
                  onCaseComplete = res => Platform.runLater { () =>
                    table.getItems.add(res)
                    val doneCount = table.getItems.size()
                    progressBar.setProgress(doneCount / total)
                    passedPill.setText(s"Passed: ${table.getItems.asScala.count(_.passed)}")
                    failedPill.setText(s"Failed: ${table.getItems.asScala.count(!_.passed)}")
                  }
                ).onComplete {
                  case scala.util.Success(summary) =>
                    Platform.runLater { () =>
                      lastSummary = Some(summary)
                      progressBar.setProgress(1.0)
                      durationPill.setText(s"${summary.totalDurationMs} ms")
                      runBtn.setDisable(false)
                      stopBtn.setDisable(true)
                      exportBtn.setDisable(summary.results.isEmpty)
                      if (table.getSelectionModel.isEmpty && !table.getItems.isEmpty) {
                        table.getSelectionModel.selectFirst()
                      }
                    }
                  case scala.util.Failure(ex) =>
                    Platform.runLater { () =>
                      runBtn.setDisable(false)
                      stopBtn.setDisable(true)
                      durationPill.setText("Error")
                      CustomDialogStage.showErrorAlert("Suite Error", Option(ex.getMessage).getOrElse("Unknown error"))
                    }
                }
            }
        }
      }
    }

    exportBtn.setOnAction { _ =>
      lastSummary.foreach { summary =>
        val chooser = new FileChooser()
        chooser.setTitle("Export QA Suite Report")
        chooser.setInitialFileName("protoform-qa-report.md")
        chooser.getExtensionFilters.addAll(
          new FileChooser.ExtensionFilter("Markdown Report (*.md)", "*.md"),
          new FileChooser.ExtensionFilter("JSON Report (*.json)", "*.json")
        )
        Option(chooser.showSaveDialog(stage)).foreach { file =>
          val content =
            if (file.getName.toLowerCase.endsWith(".json")) SuiteRunnerService.exportReportJson(summary)
            else SuiteRunnerService.exportReportMarkdown(summary)
          Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
        }
      }
    }

    stage.showAndWait()
  }
}
