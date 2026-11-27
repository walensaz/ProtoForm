package net.protoform.ui.views

import javafx.beans.property.SimpleStringProperty
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout._
import net.protoform.core.call.{DiffKind, DiffLine, JsonDiff, JsonFieldDelta, RpcCallStatus}
import net.protoform.core.workspace.CallHistoryEntry
import net.protoform.ui.state.AppState
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters._

object ResponseDiffDialog {
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

  final case class DiffSource(
    id: String,
    label: String,
    bodyJson: String,
    latencyMs: Option[Long]
  ) {
    override def toString: String = label
  }

  def show(appState: AppState, preselectedHistoryLeft: Option[CallHistoryEntry] = None): Unit = {
    val sources = buildSources(appState)
    if (sources.isEmpty) {
      CustomDialogStage.showErrorAlert("No Responses Available", "Execute at least one RPC call or pin a baseline before diffing responses.")
      return
    }

    val leftCombo = new ComboBox[DiffSource]()
    leftCombo.getItems.addAll(sources: _*)
    leftCombo.setMaxWidth(Double.MaxValue)
    HBox.setHgrow(leftCombo, Priority.ALWAYS)

    val rightCombo = new ComboBox[DiffSource]()
    rightCombo.getItems.addAll(sources: _*)
    rightCombo.setMaxWidth(Double.MaxValue)
    HBox.setHgrow(rightCombo, Priority.ALWAYS)

    val swapBtn = new Button("", FontIcon.of(Feather.REPEAT, 12))
    swapBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
    swapBtn.setTooltip(new Tooltip("Swap Left & Right"))

    val addedPill = new Label("+0")
    addedPill.getStyleClass.addAll("status-badge", "status-badge-ok")
    val removedPill = new Label("-0")
    removedPill.getStyleClass.addAll("status-badge", "status-badge-error")
    val changedPill = new Label("~0")
    changedPill.getStyleClass.add("status-badge")
    val latencyDeltaPill = new Label("")
    latencyDeltaPill.getStyleClass.add("latency-pill")

    val selectorRow = new HBox(
      8,
      new Label("Baseline:") { getStyleClass.add("form-label") },
      leftCombo,
      swapBtn,
      new Label("Compare:") { getStyleClass.add("form-label") },
      rightCombo
    )
    selectorRow.setAlignment(Pos.CENTER_LEFT)

    val summaryRow = new HBox(8, addedPill, removedPill, changedPill, latencyDeltaPill)
    summaryRow.setAlignment(Pos.CENTER_LEFT)

    // Tab 1: Side-by-Side Line Diff
    val lineListView = new ListView[DiffLine]()
    lineListView.getStyleClass.add("diff-list-view")
    lineListView.setCellFactory { _ =>
      new ListCell[DiffLine]() {
        private val leftNum = new Label()
        leftNum.getStyleClass.addAll("diff-line-num", "mono-text")
        private val leftCode = new Label()
        leftCode.getStyleClass.addAll("diff-line-code", "mono-text")
        HBox.setHgrow(leftCode, Priority.ALWAYS)
        private val leftPane = new HBox(6, leftNum, leftCode)
        leftPane.setAlignment(Pos.CENTER_LEFT)
        leftPane.setMinWidth(0)
        HBox.setHgrow(leftPane, Priority.ALWAYS)

        private val rightNum = new Label()
        rightNum.getStyleClass.addAll("diff-line-num", "mono-text")
        private val rightCode = new Label()
        rightCode.getStyleClass.addAll("diff-line-code", "mono-text")
        HBox.setHgrow(rightCode, Priority.ALWAYS)
        private val rightPane = new HBox(6, rightNum, rightCode)
        rightPane.setAlignment(Pos.CENTER_LEFT)
        rightPane.setMinWidth(0)
        HBox.setHgrow(rightPane, Priority.ALWAYS)

        private val divider = new Region()
        divider.getStyleClass.add("diff-center-divider")

        private val rowBox = new HBox(leftPane, divider, rightPane)
        rowBox.setAlignment(Pos.CENTER_LEFT)

        private val stateClasses = List("diff-row-added", "diff-row-removed", "diff-row-changed", "diff-row-same")

        override def updateItem(item: DiffLine, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) {
            setText(null)
            setGraphic(null)
          } else {
            leftNum.setText(item.leftLineNum.map(n => f"$n%3d").getOrElse("   "))
            rightNum.setText(item.rightLineNum.map(n => f"$n%3d").getOrElse("   "))
            leftCode.setText(item.leftText)
            rightCode.setText(item.rightText)

            leftPane.getStyleClass.removeAll(stateClasses: _*)
            rightPane.getStyleClass.removeAll(stateClasses: _*)
            item.kind match {
              case DiffKind.Same =>
                leftPane.getStyleClass.add("diff-row-same")
                rightPane.getStyleClass.add("diff-row-same")
              case DiffKind.Added =>
                leftPane.getStyleClass.add("diff-row-same")
                rightPane.getStyleClass.add("diff-row-added")
              case DiffKind.Removed =>
                leftPane.getStyleClass.add("diff-row-removed")
                rightPane.getStyleClass.add("diff-row-same")
              case DiffKind.Changed =>
                leftPane.getStyleClass.add("diff-row-removed")
                rightPane.getStyleClass.add("diff-row-added")
            }
            setGraphic(rowBox)
          }
        }
      }
    }

    // Tab 2: Structural JSON Field Deltas
    val deltaTable = new TableView[JsonFieldDelta]()
    deltaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
    deltaTable.setPlaceholder(new Label("No structural JSON differences detected."))

    val colPath = new TableColumn[JsonFieldDelta, String]("JSON Path")
    colPath.setCellValueFactory(d => new SimpleStringProperty(d.getValue.path))
    colPath.setPrefWidth(220)

    val colKind = new TableColumn[JsonFieldDelta, String]("Change")
    colKind.setCellValueFactory(d => new SimpleStringProperty(d.getValue.kind.label))
    colKind.setPrefWidth(90)

    val colLeftVal = new TableColumn[JsonFieldDelta, String]("Baseline Value")
    colLeftVal.setCellValueFactory(d => new SimpleStringProperty(d.getValue.leftValue))
    colLeftVal.setPrefWidth(230)

    val colRightVal = new TableColumn[JsonFieldDelta, String]("Compare Value")
    colRightVal.setCellValueFactory(d => new SimpleStringProperty(d.getValue.rightValue))
    colRightVal.setPrefWidth(230)

    deltaTable.getColumns.addAll(colPath, colKind, colLeftVal, colRightVal)

    val diffTabs = new TabPane()
    VBox.setVgrow(diffTabs, Priority.ALWAYS)
    val linesTab = new Tab("Side-by-Side Diff", lineListView)
    linesTab.setClosable(false)
    val deltasTab = new Tab("JSON Field Deltas (0)", deltaTable)
    deltasTab.setClosable(false)
    diffTabs.getTabs.addAll(linesTab, deltasTab)

    def recomputeDiff(): Unit = {
      val l = Option(leftCombo.getValue)
      val r = Option(rightCombo.getValue)
      (l, r) match {
        case (Some(leftSrc), Some(rightSrc)) =>
          val lines = JsonDiff.diffLines(leftSrc.bodyJson, rightSrc.bodyJson)
          lineListView.getItems.setAll(lines: _*)
          addedPill.setText(s"+${lines.count(_.kind == DiffKind.Added)} added")
          removedPill.setText(s"-${lines.count(_.kind == DiffKind.Removed)} removed")
          changedPill.setText(s"~${lines.count(_.kind == DiffKind.Changed)} changed")

          (leftSrc.latencyMs, rightSrc.latencyMs) match {
            case (Some(ll), Some(rl)) =>
              val delta = rl - ll
              val sign = if (delta > 0) "+" else ""
              latencyDeltaPill.setText(s"Latency: ${ll}ms → ${rl}ms ($sign${delta}ms)")
            case _ =>
              latencyDeltaPill.setText("")
          }

          JsonDiff.diffJsonStructure(leftSrc.bodyJson, rightSrc.bodyJson) match {
            case Right(deltas) =>
              deltaTable.getItems.setAll(deltas: _*)
              deltasTab.setText(s"JSON Field Deltas (${deltas.size})")
            case Left(_) =>
              deltaTable.getItems.clear()
              deltasTab.setText("JSON Field Deltas (N/A)")
          }
        case _ => ()
      }
    }

    // Choose smart defaults
    val initialLeft = preselectedHistoryLeft
      .flatMap(h => sources.find(_.id == s"hist:${h.id}"))
      .orElse(sources.find(_.id == "pinned"))
      .orElse(sources.drop(1).headOption)
      .getOrElse(sources.head)
    val initialRight = sources.find(_.id == "current").getOrElse(sources.head)

    leftCombo.setValue(initialLeft)
    rightCombo.setValue(initialRight)

    leftCombo.valueProperty().addListener((_, _, _) => recomputeDiff())
    rightCombo.valueProperty().addListener((_, _, _) => recomputeDiff())
    swapBtn.setOnAction { _ =>
      val l = leftCombo.getValue
      val r = rightCombo.getValue
      leftCombo.setValue(r)
      rightCombo.setValue(l)
    }
    recomputeDiff()

    val body = new VBox(10, selectorRow, summaryRow, diffTabs)
    body.setPadding(new Insets(12))

    val closeBtn = new Button("Close")
    closeBtn.getStyleClass.addAll("subtle", "interactive-hand")

    val stage = CustomDialogStage.createStage(
      title = "Response Diff Viewer",
      subtitle = Some("Compare pinned baselines, live responses, and call history runs side-by-side"),
      iconCode = Feather.COLUMNS,
      content = body,
      footer = Some(CustomDialogStage.buildFooterBar(closeBtn)),
      prefWidth = 980,
      prefHeight = 680,
      minWidth = 720,
      minHeight = 480,
      resizable = true
    )
    closeBtn.setOnAction(_ => stage.close())
    stage.showAndWait()
  }

  private def buildSources(appState: AppState): List[DiffSource] = {
    val currentOpt = Option(appState.activeCall.get).flatten.flatMap { call =>
      call.responseBodyJson.filter(_.nonEmpty).map { body =>
        val lat = call.latencyMs.orElse {
          call.status match {
            case RpcCallStatus.Completed(_, _, l) => Some(l)
            case _                                => None
          }
        }
        DiffSource("current", s"Current: ${call.method.fullMethodName}${lat.map(l => s" (${l}ms)").getOrElse("")}", body, lat)
      }
    }

    val pinnedOpt = Option(appState.pinnedBaseline.get).flatten.map { p =>
      DiffSource("pinned", s"Pinned Baseline: ${p.label}${p.latencyMs.map(l => s" (${l}ms)").getOrElse("")}", p.bodyJson, p.latencyMs)
    }

    val historySources = appState.callHistory.asScala.toList.map { h =>
      val statusTag = if (h.statusCode == 0) "OK" else s"ERR ${h.statusCode}"
      val ts = timeFormatter.format(Instant.ofEpochMilli(h.executedAt))
      DiffSource(
        id = s"hist:${h.id}",
        label = s"[$statusTag] ${h.methodName} · ${h.latencyMs}ms · $ts",
        bodyJson = h.effectiveResponseBody,
        latencyMs = Some(h.latencyMs)
      )
    }

    (currentOpt.toList ++ pinnedOpt.toList ++ historySources)
  }
}
