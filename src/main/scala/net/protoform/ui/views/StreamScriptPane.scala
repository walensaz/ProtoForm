package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import javafx.stage.FileChooser
import net.protoform.core.call.StreamScriptParser
import net.protoform.core.workspace.VariableInterpolator
import net.protoform.ports.rpc.ActiveClientStream
import net.protoform.ui.state.AppState
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{Executors, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.util.control.NonFatal

class StreamScriptPane(
  appState: AppState,
  getCurrentPayload: () => String,
  ensureActiveStream: () => Option[ActiveClientStream],
  onChunkSent: String => Unit,
  onAutoComplete: () => Unit
) extends VBox(8) {
  getStyleClass.add("elevated-card")
  setPadding(new Insets(10))

  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r =>
    val t = new Thread(r, "protoform-stream-replay")
    t.setDaemon(true)
    t
  }

  private var scheduledTask: Option[ScheduledFuture[_]] = None
  private val runningFlag = new AtomicBoolean(false)

  private val scriptArea = new TextArea()
  scriptArea.setPromptText(
    """Paste JSONL (one JSON object per line) or a JSON array of objects:
      |{"text": "chunk-1", "count": 1}
      |{"text": "chunk-2", "count": 2}""".stripMargin
  )
  scriptArea.setPrefRowCount(4)
  scriptArea.getStyleClass.add("mono-text")

  private val loadFileBtn = new Button("Load .jsonl…", FontIcon.of(Feather.FILE_TEXT, 11))
  loadFileBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")

  private val appendCurrentBtn = new Button("+ Current", FontIcon.of(Feather.PLUS, 11))
  appendCurrentBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  appendCurrentBtn.setTooltip(new Tooltip("Append current request payload as a JSONL line"))

  private val delayField = new TextField("250")
  delayField.setPrefWidth(62)

  private val autoCompleteCheck = new CheckBox("Auto-complete")
  autoCompleteCheck.setSelected(true)
  autoCompleteCheck.getStyleClass.add("interactive-hand")

  private val statusLbl = new Label("Ready")
  statusLbl.getStyleClass.add("info-strip")

  private val runBtn = new Button("Run Script", FontIcon.of(Feather.FAST_FORWARD, 11))
  runBtn.getStyleClass.addAll("small", "accent", "interactive-hand")

  private val stopBtn = new Button("Stop", FontIcon.of(Feather.SQUARE, 11))
  stopBtn.getStyleClass.addAll("small", "danger", "interactive-hand")
  stopBtn.setDisable(true)

  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)

  private val toolbar = new FlowPane(8, 6)
  toolbar.setAlignment(Pos.CENTER_LEFT)
  toolbar.getChildren.addAll(
    loadFileBtn,
    appendCurrentBtn,
    new Label("Delay ms:") { getStyleClass.add("form-subtext") },
    delayField,
    autoCompleteCheck,
    runBtn,
    stopBtn,
    statusLbl
  )

  private val headerLbl = new Label("Stream Script / Replay Engine")
  headerLbl.getStyleClass.add("form-label")
  private val headerRow = new HBox(8, headerLbl, spacer)
  headerRow.setAlignment(Pos.CENTER_LEFT)

  getChildren.addAll(headerRow, scriptArea, toolbar)

  loadFileBtn.setOnAction { _ =>
    val chooser = new FileChooser()
    chooser.setTitle("Load JSONL or JSON Array Stream Script")
    Option(chooser.showOpenDialog(Option(getScene).map(_.getWindow).orNull)).foreach { file =>
      try {
        val text = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
        scriptArea.setText(text)
        statusLbl.setText(s"Loaded ${file.getName}")
      } catch {
        case NonFatal(e) =>
          CustomDialogStage.showErrorAlert("Load Failed", Option(e.getMessage).getOrElse("Could not read file"))
      }
    }
  }

  appendCurrentBtn.setOnAction { _ =>
    val compact = getCurrentPayload().replaceAll("\\s+", " ").trim
    if (compact.nonEmpty) {
      val existing = Option(scriptArea.getText).getOrElse("")
      val prefix = if (existing.trim.isEmpty) "" else if (existing.endsWith("\n")) "" else "\n"
      scriptArea.appendText(s"$prefix$compact\n")
    }
  }

  runBtn.setOnAction(_ => startReplay())
  stopBtn.setOnAction(_ => stopReplay("Stopped"))

  private def startReplay(): Unit = {
    if (runningFlag.get()) return

    StreamScriptParser.parse(scriptArea.getText) match {
      case Left(err) =>
        CustomDialogStage.showErrorAlert("Invalid Stream Script", err)
      case Right(chunks) =>
        ensureActiveStream() match {
          case None =>
            statusLbl.setText("Cannot start stream")
          case Some(stream) =>
            val delayMs = delayField.getText.trim.toLongOption.map(Math.max(10L, _)).getOrElse(250L)
            runningFlag.set(true)
            runBtn.setDisable(true)
            stopBtn.setDisable(false)
            statusLbl.setText(s"0 / ${chunks.size}")

            val index = new AtomicInteger(0)
            val task = scheduler.scheduleAtFixedRate(
              () => {
                if (runningFlag.get()) {
                  val i = index.getAndIncrement()
                  if (i < chunks.size) {
                    val resolved = VariableInterpolator.interpolateString(chunks(i), appState.activeEnvVariables)
                    stream.sendNext(resolved) match {
                      case Right(_) =>
                        Platform.runLater { () =>
                          onChunkSent(resolved)
                          statusLbl.setText(s"Sent ${i + 1} / ${chunks.size}")
                        }
                      case Left(err) =>
                        Platform.runLater { () =>
                          stopReplay(s"Error: ${err.message}")
                        }
                    }
                  } else {
                    val shouldComplete = autoCompleteCheck.isSelected
                    Platform.runLater { () =>
                      stopReplay(s"Finished (${chunks.size} sent)")
                      if (shouldComplete) onAutoComplete()
                    }
                  }
                }
              },
              0L,
              delayMs,
              TimeUnit.MILLISECONDS
            )
            scheduledTask = Some(task)
        }
    }
  }

  def stopReplay(reason: String = "Stopped"): Unit = {
    runningFlag.set(false)
    scheduledTask.foreach(_.cancel(false))
    scheduledTask = None
    runBtn.setDisable(false)
    stopBtn.setDisable(true)
    statusLbl.setText(reason)
  }

  def currentScriptChunks: List[String] = {
    val raw = Option(scriptArea.getText).map(_.trim).getOrElse("")
    if (raw.isEmpty) Nil
    else StreamScriptParser.parse(raw).getOrElse(Nil)
  }

  def shutdown(): Unit = {
    stopReplay("Stopped")
    scheduler.shutdownNow()
  }
}
