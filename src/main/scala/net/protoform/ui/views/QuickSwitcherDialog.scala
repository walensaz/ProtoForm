package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.input.KeyCode
import javafx.scene.layout._
import net.protoform.core.schema.MethodSchema
import net.protoform.core.workspace.{CallHistoryEntry, ServiceTemplate, WorkspaceProject}
import net.protoform.ui.util.CustomDialogStage
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.jdk.CollectionConverters._

object QuickSwitcherDialog {

  sealed trait QuickTarget {
    def title: String
    def subtitle: String
    def badge: String
  }

  final case class MethodTarget(method: MethodSchema) extends QuickTarget {
    override def title: String = method.name
    override def subtitle: String = method.serviceFullName
    override def badge: String = method.pattern.toString
  }

  final case class TemplateTarget(template: ServiceTemplate) extends QuickTarget {
    override def title: String = template.name
    override def subtitle: String = s"Template · ${template.fullMethodName}"
    override def badge: String = "TEMPLATE"
  }

  final case class HistoryTarget(entry: CallHistoryEntry) extends QuickTarget {
    override def title: String = entry.fullMethodName
    override def subtitle: String = s"History · ${entry.endpoint} · ${entry.latencyMs}ms"
    override def badge: String = if (entry.statusCode == 0) "OK" else s"ERR ${entry.statusCode}"
  }

  final case class WorkspaceTarget(project: WorkspaceProject, isActive: Boolean) extends QuickTarget {
    override def title: String = project.name
    override def subtitle: String = s"Workspace · ${project.templates.size} templates · ${project.environments.size} envs"
    override def badge: String = if (isActive) "ACTIVE WS" else "WORKSPACE"
  }

  final case class ActionTarget(id: String, title: String, subtitle: String, badge: String) extends QuickTarget

  def show(
    methods: List[MethodSchema],
    templates: List[ServiceTemplate],
    history: List[CallHistoryEntry],
    projects: List[WorkspaceProject] = Nil,
    activeProjectId: String = ""
  ): Option[QuickTarget] = {
    var result: Option[QuickTarget] = None

    val qaAction = ActionTarget(
      id = "suite-runner",
      title = "Open QA Suite Runner",
      subtitle = "Action · Batch-execute tagged QA templates and export reports",
      badge = "SUITE"
    )

    val newWorkspaceAction = ActionTarget(
      id = "new-workspace",
      title = "Create New Workspace…",
      subtitle = "Action · Create an isolated workspace for templates, environments & tabs",
      badge = "WORKSPACE"
    )

    val allItems: List[QuickTarget] =
      methods.map(MethodTarget.apply) ++
      templates.map(TemplateTarget.apply) ++
      history.map(HistoryTarget.apply) ++
      projects.map(p => WorkspaceTarget(p, p.id == activeProjectId)) ++
      List(newWorkspaceAction, qaAction)

    val searchField = new TextField()
    searchField.setPromptText("Jump to RPC method, template, history, or workspace...")

    val listView = new ListView[QuickTarget]()
    listView.setPlaceholder(new Label("No matching methods, templates, or history entries."))
    listView.getItems.setAll(allItems.asJava)
    if (allItems.nonEmpty) listView.getSelectionModel.selectFirst()
    VBox.setVgrow(listView, Priority.ALWAYS)

    listView.setCellFactory { _ =>
      new ListCell[QuickTarget]() {
        private val titleLbl = new Label()
        titleLbl.getStyleClass.add("form-label")
        private val subLbl = new Label()
        subLbl.getStyleClass.add("form-subtext")
        private val textCol = new VBox(2, titleLbl, subLbl)
        HBox.setHgrow(textCol, Priority.ALWAYS)
        private val badgeLbl = new Label()
        badgeLbl.getStyleClass.add("rpc-pill")
        badgeLbl.setMinWidth(Region.USE_PREF_SIZE)
        private val row = new HBox(10, textCol, badgeLbl)
        row.setAlignment(Pos.CENTER_LEFT)

        override def updateItem(item: QuickTarget, empty: Boolean): Unit = {
          super.updateItem(item, empty)
          if (empty || item == null) {
            setText(null)
            setGraphic(null)
          } else {
            titleLbl.setText(item.title)
            subLbl.setText(item.subtitle)
            badgeLbl.setText(item.badge)
            badgeLbl.getStyleClass.removeAll("rpc-pill-unary", "rpc-pill-server", "rpc-pill-client", "rpc-pill-bidi")
            item match {
              case _: TemplateTarget  => badgeLbl.getStyleClass.add("rpc-pill-client")
              case _: HistoryTarget   => badgeLbl.getStyleClass.add("rpc-pill-server")
              case _: WorkspaceTarget => badgeLbl.getStyleClass.add("rpc-pill-bidi")
              case _: ActionTarget    => badgeLbl.getStyleClass.add("rpc-pill-bidi")
              case _                  => badgeLbl.getStyleClass.add("rpc-pill-unary")
            }
            setGraphic(row)
          }
        }
      }
    }

    searchField.textProperty().addListener { (_, _, raw) =>
      val q = Option(raw).map(_.trim.toLowerCase).getOrElse("")
      val filtered = if (q.isEmpty) allItems else {
        allItems.filter(i => i.title.toLowerCase.contains(q) || i.subtitle.toLowerCase.contains(q) || i.badge.toLowerCase.contains(q))
      }
      listView.getItems.setAll(filtered.asJava)
      if (filtered.nonEmpty) listView.getSelectionModel.selectFirst()
    }

    val header = new HBox(8, FontIcon.of(Feather.SEARCH, 14), searchField)
    header.setAlignment(Pos.CENTER_LEFT)
    HBox.setHgrow(searchField, Priority.ALWAYS)

    val box = new VBox(10, header, listView)
    box.setPadding(new Insets(12))

    val cancelBtn = new Button("Cancel")
    cancelBtn.getStyleClass.addAll("subtle", "interactive-hand")
    val jumpBtn = new Button("Jump")
    jumpBtn.getStyleClass.addAll("accent", "interactive-hand")
    jumpBtn.setDefaultButton(true)

    val stage = CustomDialogStage.createStage(
      title = "Quick Jump",
      iconCode = Feather.COMMAND,
      content = box,
      footer = Some(CustomDialogStage.buildFooterBar(cancelBtn, jumpBtn)),
      prefWidth = 620,
      prefHeight = 460,
      minWidth = 440,
      minHeight = 320,
      resizable = true
    )

    val confirm = () => {
      result = Option(listView.getSelectionModel.getSelectedItem)
      stage.close()
    }

    jumpBtn.setOnAction(_ => confirm())
    cancelBtn.setOnAction(_ => stage.close())
    searchField.setOnAction(_ => confirm())
    searchField.setOnKeyPressed { ev =>
      if (ev.getCode == KeyCode.DOWN) {
        listView.getSelectionModel.selectNext()
        ev.consume()
      } else if (ev.getCode == KeyCode.UP) {
        listView.getSelectionModel.selectPrevious()
        ev.consume()
      }
    }
    listView.setOnMouseClicked { ev =>
      if (ev.getClickCount == 2 && listView.getSelectionModel.getSelectedItem != null) {
        confirm()
      }
    }

    Platform.runLater(() => searchField.requestFocus())
    stage.showAndWait()
    result
  }
}
