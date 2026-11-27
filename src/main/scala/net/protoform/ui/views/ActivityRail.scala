package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control._
import javafx.scene.layout._
import net.protoform.core.workspace.ServiceTemplate
import net.protoform.ui.state.{AppState, ConnectionStatus}
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

/** Slim 40px left activity rail toggling Services, Templates, History, and Workspaces. */
class ActivityRail(
  appState: AppState,
  onSectionClicked: String => Unit,
  onRunSuiteRequested: () => Unit
) extends VBox(6) {
  setAlignment(Pos.TOP_CENTER)
  getStyleClass.add("activity-rail")

  private val servicesIcon = FontIcon.of(Feather.LAYERS, 15)
  private val servicesBtn = new Button("", servicesIcon)
  servicesBtn.getStyleClass.addAll("rail-btn", "interactive-hand")
  servicesBtn.setTooltip(new Tooltip("Services (Ctrl+1)"))
  servicesBtn.setOnAction(_ => onSectionClicked("services"))

  private val connectedDot = new Region()
  connectedDot.getStyleClass.add("rail-status-dot")
  connectedDot.setVisible(false)
  connectedDot.setMouseTransparent(true)
  StackPane.setAlignment(connectedDot, Pos.TOP_RIGHT)

  private val servicesSlot = new StackPane(servicesBtn, connectedDot)
  servicesSlot.setAlignment(Pos.CENTER)

  private val templatesIcon = FontIcon.of(Feather.BOOKMARK, 15)
  private val templatesBtn = new Button("", templatesIcon)
  templatesBtn.getStyleClass.addAll("rail-btn", "interactive-hand")
  templatesBtn.setTooltip(new Tooltip("QA Templates (Ctrl+2)"))
  templatesBtn.setOnAction(_ => onSectionClicked("templates"))

  private val tplCountBadge = new Label("")
  tplCountBadge.getStyleClass.add("rail-count-badge")
  tplCountBadge.setMouseTransparent(true)
  tplCountBadge.setVisible(false)
  StackPane.setAlignment(tplCountBadge, Pos.TOP_RIGHT)

  private val templatesSlot = new StackPane(templatesBtn, tplCountBadge)
  templatesSlot.setAlignment(Pos.CENTER)

  private val historyBtn = new Button("", FontIcon.of(Feather.CLOCK, 15))
  historyBtn.getStyleClass.addAll("rail-btn", "interactive-hand")
  historyBtn.setTooltip(new Tooltip("Call History (Ctrl+3)"))
  historyBtn.setOnAction(_ => onSectionClicked("history"))

  private val workspacesBtn = new Button("", FontIcon.of(Feather.BRIEFCASE, 15))
  workspacesBtn.getStyleClass.addAll("rail-btn", "interactive-hand")
  workspacesBtn.setTooltip(new Tooltip("Workspaces (Ctrl+4)"))
  workspacesBtn.setOnAction(_ => onSectionClicked("workspaces"))

  private val spacer = new Region()
  VBox.setVgrow(spacer, Priority.ALWAYS)

  private val suiteBtn = new Button("", FontIcon.of(Feather.CHECK_SQUARE, 15))
  suiteBtn.getStyleClass.addAll("rail-btn", "interactive-hand")
  suiteBtn.setTooltip(new Tooltip("Open QA Batch Suite Runner"))
  suiteBtn.setOnAction(_ => onRunSuiteRequested())

  getChildren.addAll(servicesSlot, templatesSlot, historyBtn, workspacesBtn, spacer, suiteBtn)

  private val sectionButtons = Map(
    "services"   -> servicesBtn,
    "templates"  -> templatesBtn,
    "history"    -> historyBtn,
    "workspaces" -> workspacesBtn
  )

  def setRailState(drawerOpen: Boolean, activeSection: String): Unit = {
    sectionButtons.foreach { case (id, btn) =>
      btn.getStyleClass.removeAll("rail-btn-active")
      if (drawerOpen && id == activeSection) {
        btn.getStyleClass.add("rail-btn-active")
      }
    }
  }

  private def refreshTemplateCount(): Unit = {
    val count = appState.templates.size()
    tplCountBadge.setVisible(count > 0)
    tplCountBadge.setText(if (count > 99) "99+" else count.toString)
  }

  refreshTemplateCount()
  appState.templates.addListener { (_: javafx.collections.ListChangeListener.Change[_ <: ServiceTemplate]) =>
    Platform.runLater(() => refreshTemplateCount())
  }

  appState.connectionStatus.addListener { (_, _, status) =>
    Platform.runLater { () =>
      val isConn = status match {
        case ConnectionStatus.Connected(_, count) if count > 0 => true
        case _                                                  => false
      }
      connectedDot.setVisible(isConn)
    }
  }
}
