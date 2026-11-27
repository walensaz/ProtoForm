package net.protoform.ui.views

import javafx.application.Platform
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.input.KeyCode
import javafx.scene.layout._
import javafx.stage.Popup
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.jdk.CollectionConverters._

/** Compact searchable dropdown button backed by a themed Popup ListView. */
class SearchableSelector[T](
  placeholder: String,
  itemText: T => String,
  itemSearchText: T => String,
  cellFactory: () => ListCell[T],
  onSelected: T => Unit
) extends Button(placeholder) {

  private var allItems: List[T] = Nil
  private var selectedItem: Option[T] = None
  private val popup = new Popup()
  popup.setAutoHide(true)
  popup.setHideOnEscape(true)

  private val textLabel = new Label(placeholder)
  textLabel.getStyleClass.add("selector-text-lbl")
  textLabel.setMinWidth(0)

  private val labelSpacer = new Region()
  HBox.setHgrow(labelSpacer, Priority.ALWAYS)

  private val chevron = FontIcon.of(Feather.CHEVRON_DOWN, 12)
  chevron.getStyleClass.add("selector-chevron")

  private val arrowCap = new StackPane(chevron)
  arrowCap.getStyleClass.add("selector-arrow-cap")
  arrowCap.setMinWidth(Region.USE_PREF_SIZE)

  private val buttonContent = new HBox(0, textLabel, labelSpacer, arrowCap)
  buttonContent.setAlignment(Pos.CENTER_LEFT)
  buttonContent.setMaxWidth(Double.MaxValue)
  buttonContent.setMouseTransparent(true)

  setText(null)
  setGraphic(buttonContent)
  setContentDisplay(ContentDisplay.GRAPHIC_ONLY)
  setAlignment(Pos.CENTER_LEFT)
  setMaxWidth(Double.MaxValue)
  getStyleClass.addAll("selector-btn", "interactive-hand")

  private val searchField = new TextField()
  searchField.setPromptText("Filter...")
  searchField.getStyleClass.add("small")

  private val listView = new ListView[T]()
  listView.setPrefWidth(320)
  listView.setPrefHeight(240)
  listView.setCellFactory(_ => cellFactory())
  listView.setPlaceholder(new Label("No matches"))

  private val popupBox = new VBox(6, searchField, listView)
  popupBox.setPadding(new Insets(8))
  popupBox.getStyleClass.add("selector-popup")
  popup.getContent.add(popupBox)

  setOnAction(_ => togglePopup())

  searchField.textProperty().addListener { (_, _, q) =>
    applyFilter(Option(q).getOrElse(""))
  }

  searchField.setOnKeyPressed { ev =>
    if (ev.getCode == KeyCode.DOWN) {
      listView.getSelectionModel.selectNext()
      listView.scrollTo(listView.getSelectionModel.getSelectedIndex)
      ev.consume()
    } else if (ev.getCode == KeyCode.UP) {
      listView.getSelectionModel.selectPrevious()
      listView.scrollTo(listView.getSelectionModel.getSelectedIndex)
      ev.consume()
    } else if (ev.getCode == KeyCode.ENTER) {
      commitSelection()
      ev.consume()
    } else if (ev.getCode == KeyCode.ESCAPE) {
      popup.hide()
      ev.consume()
    }
  }

  listView.setOnMouseClicked { ev =>
    if (ev.getClickCount >= 1 && listView.getSelectionModel.getSelectedItem != null) {
      commitSelection()
    }
  }

  listView.setOnKeyPressed { ev =>
    if (ev.getCode == KeyCode.ENTER) {
      commitSelection()
      ev.consume()
    } else if (ev.getCode == KeyCode.ESCAPE) {
      popup.hide()
      ev.consume()
    }
  }

  def setItems(items: List[T]): Unit = {
    allItems = items
    applyFilter(Option(searchField.getText).getOrElse(""))
    if (selectedItem.exists(items.contains)) {
      selectedItem.foreach(s => textLabel.setText(itemText(s)))
    } else {
      selectedItem = None
      textLabel.setText(placeholder)
    }
    setDisable(items.isEmpty)
  }

  def setSelected(itemOpt: Option[T]): Unit = {
    selectedItem = itemOpt
    textLabel.setText(itemOpt.map(itemText).getOrElse(placeholder))
  }

  def getSelected: Option[T] = selectedItem

  private def togglePopup(): Unit = {
    if (popup.isShowing) {
      popup.hide()
    } else if (!allItems.isEmpty && getScene != null && getScene.getWindow != null) {
      searchField.clear()
      applyFilter("")
      selectedItem.foreach { cur =>
        listView.getSelectionModel.select(cur)
        listView.scrollTo(cur)
      }
      val bounds = localToScreen(getBoundsInLocal)
      if (bounds != null) {
        val width = Math.max(260.0, bounds.getWidth)
        popupBox.setPrefWidth(width)
        listView.setPrefWidth(width - 16)
        popup.show(getScene.getWindow, bounds.getMinX, bounds.getMaxY + 3)
        Platform.runLater(() => searchField.requestFocus())
      }
    }
  }

  private def applyFilter(raw: String): Unit = {
    val q = raw.trim.toLowerCase
    val filtered = if (q.isEmpty) allItems else allItems.filter(i => itemSearchText(i).toLowerCase.contains(q))
    listView.getItems.setAll(filtered.asJava)
    if (filtered.nonEmpty && listView.getSelectionModel.getSelectedItem == null) {
      listView.getSelectionModel.selectFirst()
    }
  }

  private def commitSelection(): Unit = {
    val chosen = Option(listView.getSelectionModel.getSelectedItem)
    popup.hide()
    chosen.foreach { item =>
      setSelected(Some(item))
      onSelected(item)
    }
  }
}
