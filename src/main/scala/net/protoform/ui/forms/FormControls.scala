package net.protoform.ui.forms

import atlantafx.base.controls.ToggleSwitch
import com.google.gson.{GsonBuilder, JsonElement, JsonObject, JsonPrimitive}
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control._
import javafx.scene.layout._
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try

trait FieldInputControl {
  def getNode: Node
  def getValueJson: String
  def getValueJson(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): String = getValueJson
  def setValueFromJson(element: JsonElement): Unit
  def resetToDefault(): Unit
  def isDefaultValue: Boolean
  def onValueChanged(listener: () => Unit): Unit
}

object JsonQuoteHelper {
  private val gson = new GsonBuilder().create()
  def quote(s: String): String = gson.toJson(if (s == null) "" else s)
}

class ScalarStringControl(defaultValue: String) extends FieldInputControl {
  val textField = new TextField(defaultValue)
  textField.setPromptText(s"Default: ${JsonQuoteHelper.quote(defaultValue)}")

  private val listeners = ListBuffer.empty[() => Unit]
  textField.textProperty().addListener { (_, _, _) =>
    listeners.foreach(_.apply())
  }

  override def getNode: Node = textField
  override def getValueJson: String = JsonQuoteHelper.quote(textField.getText)
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element == null || element.isJsonNull) {
      textField.setText(defaultValue)
    } else if (element.isJsonPrimitive) {
      textField.setText(element.getAsString)
    } else {
      textField.setText(element.toString)
    }
  }
  override def resetToDefault(): Unit = textField.setText(defaultValue)
  override def isDefaultValue: Boolean = Option(textField.getText).getOrElse("") == defaultValue
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class ScalarNumericControl(defaultValue: String, isFloating: Boolean) extends FieldInputControl {
  val textField = new TextField(defaultValue)
  textField.setPromptText(s"Default: $defaultValue")

  private val listeners = ListBuffer.empty[() => Unit]
  private var suppressing = false

  textField.textProperty().addListener { (_, oldVal, newVal) =>
    if (!suppressing) {
      if (newVal != null && newVal.nonEmpty) {
        val regex = if (isFloating) "^-?[0-9]*\\.?[0-9]*(?:[eE][+-]?[0-9]*)?$" else "^-?[0-9]*$"
        if (!newVal.matches(regex)) {
          suppressing = true
          textField.setText(oldVal)
          suppressing = false
        }
      }
      listeners.foreach(_.apply())
    }
  }

  override def getNode: Node = textField
  override def getValueJson: String = {
    val t = Option(textField.getText).map(_.trim).getOrElse("")
    if (t.isEmpty || t == "-" || t == ".") defaultValue else t
  }
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element == null || element.isJsonNull) {
      textField.setText(defaultValue)
    } else if (element.isJsonPrimitive) {
      val prim = element.getAsJsonPrimitive
      textField.setText(if (prim.isNumber) prim.getAsNumber.toString else prim.getAsString)
    }
  }
  override def resetToDefault(): Unit = textField.setText(defaultValue)
  override def isDefaultValue: Boolean = {
    val t = Option(textField.getText).map(_.trim).getOrElse("")
    if (t.isEmpty || t == defaultValue) {
      true
    } else if (isFloating) {
      Try(t.toDouble).toOption.contains(0.0)
    } else {
      Try(t.toLong).toOption.contains(0L)
    }
  }
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class ScalarBoolControl(defaultValue: Boolean) extends FieldInputControl {
  val toggle = new ToggleSwitch()
  toggle.setSelected(defaultValue)
  toggle.getStyleClass.add("interactive-hand")

  val stateLabel = new Label(formatLabel(defaultValue))
  stateLabel.getStyleClass.addAll("form-subtext", "mono-text")

  val row = new HBox(10, toggle, stateLabel)
  row.setAlignment(Pos.CENTER_LEFT)

  private val listeners = ListBuffer.empty[() => Unit]

  toggle.selectedProperty().addListener { (_, _, isSelected) =>
    stateLabel.setText(formatLabel(isSelected))
    listeners.foreach(_.apply())
  }

  private def formatLabel(value: Boolean): String = {
    if (value == defaultValue) s"$value (default)" else value.toString
  }

  override def getNode: Node = row
  override def getValueJson: String = toggle.isSelected.toString
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element != null && element.isJsonPrimitive) {
      val prim = element.getAsJsonPrimitive
      val value = if (prim.isBoolean) prim.getAsBoolean else prim.getAsString.equalsIgnoreCase("true")
      toggle.setSelected(value)
    } else {
      resetToDefault()
    }
  }
  override def resetToDefault(): Unit = {
    toggle.setSelected(defaultValue)
    stateLabel.setText(formatLabel(defaultValue))
  }
  override def isDefaultValue: Boolean = toggle.isSelected == defaultValue
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class EnumControl(values: List[(String, Int)], defaultVal: String) extends FieldInputControl {
  val comboBox = new ComboBox[String]()
  comboBox.setMaxWidth(Double.MaxValue)
  private val names = values.map(_._1)
  comboBox.getItems.addAll(names.asJava)
  if (defaultVal.nonEmpty && names.contains(defaultVal)) {
    comboBox.setValue(defaultVal)
  } else if (names.nonEmpty) {
    comboBox.setValue(names.head)
  }

  private val listeners = ListBuffer.empty[() => Unit]
  comboBox.valueProperty().addListener { (_, _, _) =>
    listeners.foreach(_.apply())
  }

  override def getNode: Node = comboBox
  override def getValueJson: String = JsonQuoteHelper.quote(Option(comboBox.getValue).getOrElse(defaultVal))
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element != null && element.isJsonPrimitive) {
      val prim = element.getAsJsonPrimitive
      if (prim.isNumber) {
        val num = prim.getAsInt
        values.find(_._2 == num).map(_._1).foreach(comboBox.setValue)
      } else {
        val str = prim.getAsString
        if (names.contains(str)) {
          comboBox.setValue(str)
        }
      }
    } else {
      resetToDefault()
    }
  }
  override def resetToDefault(): Unit = comboBox.setValue(defaultVal)
  override def isDefaultValue: Boolean = Option(comboBox.getValue).forall(_ == defaultVal)
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class RepeatedFieldControl(itemFactory: () => FieldInputControl) extends FieldInputControl {
  val container = new VBox(6)
  private val items = ListBuffer[FieldInputControl]()
  private val rowsContainer = new VBox(5)
  private val listeners = ListBuffer.empty[() => Unit]

  val addButton = new Button("Add Item", FontIcon.of(Feather.PLUS, 12))
  addButton.getStyleClass.addAll("small", "subtle", "interactive-hand")
  addButton.setOnAction { _ =>
    addItem()
    listeners.foreach(_.apply())
  }

  container.getChildren.addAll(rowsContainer, addButton)

  private def refreshIndices(): Unit = {
    rowsContainer.getChildren.asScala.zipWithIndex.foreach {
      case (row: HBox, idx) if row.getChildren.size() >= 1 =>
        row.getChildren.get(0) match {
          case lbl: Label => lbl.setText(s"#${idx + 1}")
          case _          => ()
        }
      case _ => ()
    }
  }

  def addItem(): FieldInputControl = {
    val ctrl = itemFactory()
    ctrl.onValueChanged(() => listeners.foreach(_.apply()))
    items += ctrl

    val indexLbl = new Label(s"#${items.size}")
    indexLbl.getStyleClass.add("collection-item-index")
    indexLbl.setMinWidth(Region.USE_PREF_SIZE)

    val row = new HBox(6)
    row.getStyleClass.add("collection-item-row")
    row.setAlignment(if (ctrl.getNode.isInstanceOf[TitledPane]) Pos.TOP_LEFT else Pos.CENTER_LEFT)
    val deleteBtn = new Button("", FontIcon.of(Feather.TRASH_2, 12))
    deleteBtn.getStyleClass.addAll("row-delete-btn", "interactive-hand")
    deleteBtn.setTooltip(new Tooltip("Remove item"))
    deleteBtn.setOnAction { _ =>
      items -= ctrl
      rowsContainer.getChildren.remove(row)
      refreshIndices()
      listeners.foreach(_.apply())
    }

    HBox.setHgrow(ctrl.getNode, Priority.ALWAYS)
    row.getChildren.addAll(indexLbl, ctrl.getNode, deleteBtn)
    rowsContainer.getChildren.add(row)
    ctrl
  }

  override def getNode: Node = container
  override def getValueJson: String = getValueJson(includeDefaults = true, preserveProtoFieldNames = true)
  override def getValueJson(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): String = {
    val serialized = items.map(_.getValueJson(includeDefaults, preserveProtoFieldNames)).mkString(", ")
    s"[$serialized]"
  }
  override def setValueFromJson(element: JsonElement): Unit = {
    resetToDefault()
    if (element != null && element.isJsonArray) {
      element.getAsJsonArray.asScala.foreach { itemEl =>
        val ctrl = addItem()
        ctrl.setValueFromJson(itemEl)
      }
    }
  }
  override def resetToDefault(): Unit = {
    items.clear()
    rowsContainer.getChildren.clear()
  }
  override def isDefaultValue: Boolean = items.isEmpty
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class MapFieldControl(
  keyFactory: () => FieldInputControl,
  valueFactory: () => FieldInputControl
) extends FieldInputControl {
  val container = new VBox(6)
  private val entries = ListBuffer[(FieldInputControl, FieldInputControl)]()
  private val rowsContainer = new VBox(5)
  private val listeners = ListBuffer.empty[() => Unit]

  val addButton = new Button("Add Entry", FontIcon.of(Feather.PLUS, 12))
  addButton.getStyleClass.addAll("small", "subtle", "interactive-hand")
  addButton.setOnAction { _ =>
    addEntry()
    listeners.foreach(_.apply())
  }

  container.getChildren.addAll(rowsContainer, addButton)

  def addEntry(): (FieldInputControl, FieldInputControl) = {
    val keyCtrl = keyFactory()
    val valCtrl = valueFactory()
    keyCtrl.onValueChanged(() => listeners.foreach(_.apply()))
    valCtrl.onValueChanged(() => listeners.foreach(_.apply()))
    entries += (keyCtrl -> valCtrl)

    val row = new HBox(6)
    row.getStyleClass.add("collection-item-row")
    row.setAlignment(if (valCtrl.getNode.isInstanceOf[TitledPane]) Pos.TOP_LEFT else Pos.CENTER_LEFT)

    val deleteBtn = new Button("", FontIcon.of(Feather.TRASH_2, 12))
    deleteBtn.getStyleClass.addAll("row-delete-btn", "interactive-hand")
    deleteBtn.setTooltip(new Tooltip("Remove entry"))
    deleteBtn.setOnAction { _ =>
      entries -= (keyCtrl -> valCtrl)
      rowsContainer.getChildren.remove(row)
      listeners.foreach(_.apply())
    }

    HBox.setHgrow(keyCtrl.getNode, Priority.SOMETIMES)
    HBox.setHgrow(valCtrl.getNode, Priority.ALWAYS)

    val colon = new Label(":")
    colon.getStyleClass.add("field-meta-mono")
    row.getChildren.addAll(keyCtrl.getNode, colon, valCtrl.getNode, deleteBtn)
    rowsContainer.getChildren.add(row)
    (keyCtrl, valCtrl)
  }

  override def getNode: Node = container
  override def getValueJson: String = getValueJson(includeDefaults = true, preserveProtoFieldNames = true)
  override def getValueJson(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): String = {
    val pairs = entries.map { case (k, v) =>
      val rawKey = k.getValueJson
      val keyStr = if (rawKey.startsWith("\"")) rawKey else JsonQuoteHelper.quote(rawKey)
      s"$keyStr: ${v.getValueJson(includeDefaults, preserveProtoFieldNames)}"
    }.mkString(", ")
    s"{$pairs}"
  }
  override def setValueFromJson(element: JsonElement): Unit = {
    resetToDefault()
    if (element != null && element.isJsonObject) {
      element.getAsJsonObject.entrySet().asScala.foreach { entry =>
        val (kCtrl, vCtrl) = addEntry()
        kCtrl.setValueFromJson(new JsonPrimitive(entry.getKey))
        vCtrl.setValueFromJson(entry.getValue)
      }
    }
  }
  override def resetToDefault(): Unit = {
    entries.clear()
    rowsContainer.getChildren.clear()
  }
  override def isDefaultValue: Boolean = entries.isEmpty
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}
