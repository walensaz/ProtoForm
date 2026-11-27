package net.protoform.ui.views

import javafx.geometry.Pos
import javafx.scene.control._
import javafx.scene.input.MouseButton
import javafx.scene.layout._
import net.protoform.ui.state.WorkbenchTabSession
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

class WorkbenchTabBar(
  onSelectTab: String => Unit,
  onNewTab: () => Unit,
  onCloseTab: String => Unit,
  onDuplicateTab: String => Unit,
  onCloseOtherTabs: String => Unit
) extends HBox(4) {

  setAlignment(Pos.CENTER_LEFT)
  getStyleClass.add("workbench-tab-bar")

  private val tabsFlow = new HBox(4)
  tabsFlow.setAlignment(Pos.BOTTOM_LEFT)

  private val scrollTabs = new ScrollPane(tabsFlow)
  scrollTabs.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED)
  scrollTabs.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER)
  scrollTabs.setFitToHeight(true)
  scrollTabs.setPannable(true)
  HBox.setHgrow(scrollTabs, Priority.ALWAYS)

  private val newTabBtn = new Button("", FontIcon.of(Feather.PLUS, 12))
  newTabBtn.getStyleClass.addAll("small", "subtle", "icon-only-btn", "interactive-hand")
  newTabBtn.setTooltip(new Tooltip("New Workbench Tab (Ctrl+T)"))
  newTabBtn.setOnAction(_ => onNewTab())

  getChildren.addAll(scrollTabs, newTabBtn)

  def refresh(sessions: List[WorkbenchTabSession], activeId: String): Unit = {
    tabsFlow.getChildren.clear()
    sessions.foreach { session =>
      val isActive = session.id == activeId
      val chip = new HBox(6)
      chip.setAlignment(Pos.CENTER_LEFT)
      chip.getStyleClass.add("workbench-tab-chip")
      if (isActive) chip.getStyleClass.add("workbench-tab-chip-active")

      if (session.isStreaming) {
        val dot = new Region()
        dot.getStyleClass.addAll("status-dot", "status-dot-connected")
        chip.getChildren.add(dot)
      }

      val titleText = if (session.userEdited) s"• ${session.title}" else session.title
      val titleLbl = new Label(titleText)
      titleLbl.getStyleClass.add("workbench-tab-title")

      val closeBtn = new Button("", FontIcon.of(Feather.X, 10))
      closeBtn.getStyleClass.addAll("workbench-tab-close", "interactive-hand")
      closeBtn.setTooltip(new Tooltip("Close Tab (Ctrl+W)"))
      closeBtn.setOnAction { ev =>
        ev.consume()
        onCloseTab(session.id)
      }

      chip.getChildren.addAll(titleLbl, closeBtn)
      chip.setOnMouseClicked { ev =>
        if (ev.getButton == MouseButton.MIDDLE) {
          ev.consume()
          onCloseTab(session.id)
        } else if (ev.getButton == MouseButton.PRIMARY && !isActive) {
          onSelectTab(session.id)
        }
      }

      val dupItem = new MenuItem("Duplicate Tab", FontIcon.of(Feather.COPY, 12))
      dupItem.setOnAction(_ => onDuplicateTab(session.id))
      val closeItem = new MenuItem("Close Tab", FontIcon.of(Feather.X, 12))
      closeItem.setOnAction(_ => onCloseTab(session.id))
      val closeOthersItem = new MenuItem("Close Other Tabs", FontIcon.of(Feather.X_CIRCLE, 12))
      closeOthersItem.setDisable(sessions.size <= 1)
      closeOthersItem.setOnAction(_ => onCloseOtherTabs(session.id))
      val menu = new ContextMenu(dupItem, new SeparatorMenuItem(), closeItem, closeOthersItem)
      chip.setOnContextMenuRequested(ev => menu.show(chip, ev.getScreenX, ev.getScreenY))

      tabsFlow.getChildren.add(chip)
    }
  }
}
