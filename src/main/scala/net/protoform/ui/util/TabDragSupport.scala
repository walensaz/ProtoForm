package net.protoform.ui.util

import javafx.scene.control.{Label, Tab, TabPane}
import javafx.scene.input.{ClipboardContent, DataFormat, TransferMode}

import scala.jdk.CollectionConverters._

object TabDragSupport {
  private val TabDragFormat = new DataFormat("application/x-protoform-tab-id")
  private val DropTargetClass = "tab-drop-target"

  def initDraggableTab(tab: Tab, tabId: String, initialTitle: String): Unit = {
    tab.setUserData(tabId)
    val icon = tab.getGraphic
    tab.setText(null)
    val label = new Label(initialTitle, icon)
    label.getStyleClass.add("draggable-tab-label")
    tab.setGraphic(label)
  }

  def setTabTitle(tab: Tab, title: String): Unit = {
    tab.getGraphic match {
      case lbl: Label => lbl.setText(title)
      case _          => tab.setText(title)
    }
  }

  def getTabId(tab: Tab): Option[String] = {
    Option(tab.getUserData).collect { case s: String if s.nonEmpty => s }
  }

  def registerTabPane(
    groupId: String,
    tabPane: TabPane,
    allowedGroups: Set[String],
    lookupTab: String => Option[(String, TabPane, Tab)],
    onOrderChanged: () => Unit
  ): Unit = {
    def attachTabHandlers(tab: Tab): Unit = {
      Option(tab.getGraphic).foreach { headerNode =>
        headerNode.setOnDragDetected { ev =>
          getTabId(tab).foreach { id =>
            val db = headerNode.startDragAndDrop(TransferMode.MOVE)
            val content = new ClipboardContent()
            content.put(TabDragFormat, s"$groupId|$id")
            db.setContent(content)
            ev.consume()
          }
        }

        headerNode.setOnDragOver { ev =>
          if (ev.getGestureSource != headerNode && ev.getDragboard.hasContent(TabDragFormat)) {
            val payload = ev.getDragboard.getContent(TabDragFormat).asInstanceOf[String]
            val srcGroup = payload.split("\\|", 2).headOption.getOrElse("")
            if (allowedGroups.contains(srcGroup)) {
              ev.acceptTransferModes(TransferMode.MOVE)
            }
          }
          ev.consume()
        }

        headerNode.setOnDragEntered { ev =>
          if (ev.getGestureSource != headerNode && ev.getDragboard.hasContent(TabDragFormat)) {
            if (!headerNode.getStyleClass.contains(DropTargetClass)) {
              headerNode.getStyleClass.add(DropTargetClass)
            }
          }
        }

        headerNode.setOnDragExited { _ =>
          headerNode.getStyleClass.removeAll(DropTargetClass)
        }

        headerNode.setOnDragDropped { ev =>
          headerNode.getStyleClass.removeAll(DropTargetClass)
          val db = ev.getDragboard
          var completed = false
          if (db.hasContent(TabDragFormat)) {
            val payload = db.getContent(TabDragFormat).asInstanceOf[String]
            val parts = payload.split("\\|", 2)
            if (parts.length == 2 && allowedGroups.contains(parts(0))) {
              lookupTab(parts(1)).foreach { case (_, srcPane, draggedTab) =>
                if (draggedTab != tab) {
                  val targetIndex = tabPane.getTabs.indexOf(tab)
                  srcPane.getTabs.remove(draggedTab)
                  val safeIdx = Math.max(0, Math.min(targetIndex, tabPane.getTabs.size()))
                  tabPane.getTabs.add(safeIdx, draggedTab)
                  tabPane.getSelectionModel.select(draggedTab)
                  onOrderChanged()
                  completed = true
                }
              }
            }
          }
          ev.setDropCompleted(completed)
          ev.consume()
        }
      }
    }

    tabPane.getTabs.asScala.foreach(attachTabHandlers)
    tabPane.getTabs.addListener { (c: javafx.collections.ListChangeListener.Change[_ <: Tab]) =>
      while (c.next()) {
        if (c.wasAdded()) {
          c.getAddedSubList.asScala.foreach(attachTabHandlers)
        }
      }
    }

    // Also allow dropping onto the TabPane itself (e.g. when a pane has empty tab header space or 0 tabs).
    tabPane.setOnDragOver { ev =>
      if (ev.getDragboard.hasContent(TabDragFormat)) {
        val payload = ev.getDragboard.getContent(TabDragFormat).asInstanceOf[String]
        val srcGroup = payload.split("\\|", 2).headOption.getOrElse("")
        if (allowedGroups.contains(srcGroup)) {
          ev.acceptTransferModes(TransferMode.MOVE)
        }
      }
      ev.consume()
    }

    tabPane.setOnDragDropped { ev =>
      val db = ev.getDragboard
      var completed = false
      if (db.hasContent(TabDragFormat)) {
        val payload = db.getContent(TabDragFormat).asInstanceOf[String]
        val parts = payload.split("\\|", 2)
        if (parts.length == 2 && allowedGroups.contains(parts(0))) {
          lookupTab(parts(1)).foreach { case (_, srcPane, draggedTab) =>
            if (srcPane != tabPane || tabPane.getTabs.indexOf(draggedTab) != tabPane.getTabs.size() - 1) {
              srcPane.getTabs.remove(draggedTab)
              tabPane.getTabs.add(draggedTab)
              tabPane.getSelectionModel.select(draggedTab)
              onOrderChanged()
              completed = true
            }
          }
        }
      }
      ev.setDropCompleted(completed)
      ev.consume()
    }
  }
}
