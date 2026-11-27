package net.protoform.ui.forms

import com.google.gson.{JsonElement, JsonObject}
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.{Button, Label, TextField, Tooltip}
import javafx.scene.layout.{HBox, Priority}
import javafx.stage.FileChooser
import net.protoform.core.call.WellKnownTypeHelper
import org.kordamp.ikonli.feather.Feather
import org.kordamp.ikonli.javafx.FontIcon

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

class TimestampControl extends FieldInputControl {
  private val DefaultIso = "1970-01-01T00:00:00Z"
  private val textField = new TextField(DefaultIso)
  textField.setPromptText(DefaultIso)
  HBox.setHgrow(textField, Priority.ALWAYS)

  private val nowBtn = new Button("Now", FontIcon.of(Feather.CLOCK, 11))
  nowBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  nowBtn.setTooltip(new Tooltip("Set to current UTC ISO-8601 timestamp"))

  private val statusLbl = new Label("UTC")
  statusLbl.getStyleClass.addAll("info-strip", "mono-text")

  private val row = new HBox(6, textField, nowBtn, statusLbl)
  row.setAlignment(Pos.CENTER_LEFT)

  private val listeners = ListBuffer.empty[() => Unit]

  private def refreshValidation(): Unit = {
    statusLbl.getStyleClass.removeAll("info-strip", "status-text-connected", "status-text-error")
    WellKnownTypeHelper.validateTimestamp(textField.getText) match {
      case Right(_) =>
        statusLbl.setText("ISO OK")
        statusLbl.getStyleClass.add("status-text-connected")
      case Left(_) =>
        statusLbl.setText("Invalid")
        statusLbl.getStyleClass.add("status-text-error")
    }
  }

  nowBtn.setOnAction { _ =>
    textField.setText(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString)
  }

  textField.textProperty().addListener { (_, _, _) =>
    refreshValidation()
    listeners.foreach(_.apply())
  }
  refreshValidation()

  override def getNode: Node = row
  override def getValueJson: String = {
    val t = Option(textField.getText).map(_.trim).filter(_.nonEmpty).getOrElse(DefaultIso)
    JsonQuoteHelper.quote(t)
  }
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element == null || element.isJsonNull) {
      resetToDefault()
    } else if (element.isJsonPrimitive) {
      textField.setText(element.getAsString)
    } else if (element.isJsonObject) {
      val obj = element.getAsJsonObject
      val sec = if (obj.has("seconds")) Try(obj.get("seconds").getAsLong).getOrElse(0L) else 0L
      val nanos = if (obj.has("nanos")) Try(obj.get("nanos").getAsInt).getOrElse(0) else 0
      textField.setText(Instant.ofEpochSecond(sec, nanos.toLong).toString)
    }
  }
  override def resetToDefault(): Unit = textField.setText(DefaultIso)
  override def isDefaultValue: Boolean = Option(textField.getText).map(_.trim).getOrElse("") == DefaultIso
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class DurationControl extends FieldInputControl {
  private val DefaultDuration = "0s"
  private val textField = new TextField(DefaultDuration)
  textField.setPromptText("e.g. 5s, 150ms, 1.5s")
  HBox.setHgrow(textField, Priority.ALWAYS)

  private val presets = List("100ms" -> "0.1s", "1s" -> "1s", "5s" -> "5s", "30s" -> "30s").map { case (lbl, value) =>
    val b = new Button(lbl)
    b.getStyleClass.addAll("small", "subtle", "interactive-hand")
    b.setOnAction(_ => textField.setText(value))
    b
  }

  private val statusLbl = new Label("0s")
  statusLbl.getStyleClass.addAll("info-strip", "mono-text")

  private val row = new HBox(5)
  row.setAlignment(Pos.CENTER_LEFT)
  row.getChildren.add(textField)
  row.getChildren.addAll(presets: _*)
  row.getChildren.add(statusLbl)

  private val listeners = ListBuffer.empty[() => Unit]

  private def refreshValidation(): Unit = {
    statusLbl.getStyleClass.removeAll("info-strip", "status-text-connected", "status-text-error")
    WellKnownTypeHelper.normalizeDuration(textField.getText) match {
      case Right(norm) =>
        statusLbl.setText(norm)
        statusLbl.getStyleClass.add("status-text-connected")
      case Left(_) =>
        statusLbl.setText("Invalid")
        statusLbl.getStyleClass.add("status-text-error")
    }
  }

  textField.textProperty().addListener { (_, _, _) =>
    refreshValidation()
    listeners.foreach(_.apply())
  }
  refreshValidation()

  override def getNode: Node = row
  override def getValueJson: String = {
    val normalized = WellKnownTypeHelper.normalizeDuration(textField.getText).getOrElse {
      Option(textField.getText).map(_.trim).filter(_.nonEmpty).getOrElse(DefaultDuration)
    }
    JsonQuoteHelper.quote(normalized)
  }
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element == null || element.isJsonNull) {
      resetToDefault()
    } else if (element.isJsonPrimitive) {
      textField.setText(element.getAsString)
    } else if (element.isJsonObject) {
      val obj = element.getAsJsonObject
      val sec = if (obj.has("seconds")) Try(obj.get("seconds").getAsLong).getOrElse(0L) else 0L
      val nanos = if (obj.has("nanos")) Try(obj.get("nanos").getAsInt).getOrElse(0) else 0
      val total = BigDecimal(sec) + (BigDecimal(nanos) / BigDecimal(1000000000L))
      textField.setText(s"${total.bigDecimal.stripTrailingZeros().toPlainString}s")
    }
  }
  override def resetToDefault(): Unit = textField.setText(DefaultDuration)
  override def isDefaultValue: Boolean = {
    WellKnownTypeHelper.normalizeDuration(textField.getText).contains(DefaultDuration)
  }
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class FieldMaskControl extends FieldInputControl {
  private val textField = new TextField("")
  textField.setPromptText("Comma-separated paths (e.g. name,address.city)")
  HBox.setHgrow(textField, Priority.ALWAYS)

  private val countLbl = new Label("0 paths")
  countLbl.getStyleClass.addAll("info-strip", "mono-text")

  private val row = new HBox(6, textField, countLbl)
  row.setAlignment(Pos.CENTER_LEFT)

  private val listeners = ListBuffer.empty[() => Unit]

  private def refreshCount(): Unit = {
    val count = Option(textField.getText).getOrElse("").split(",").map(_.trim).count(_.nonEmpty)
    countLbl.setText(s"$count path${if (count == 1) "" else "s"}")
  }

  textField.textProperty().addListener { (_, _, _) =>
    refreshCount()
    listeners.foreach(_.apply())
  }
  refreshCount()

  override def getNode: Node = row
  override def getValueJson: String = JsonQuoteHelper.quote(Option(textField.getText).map(_.trim).getOrElse(""))
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element == null || element.isJsonNull) {
      resetToDefault()
    } else if (element.isJsonPrimitive) {
      textField.setText(element.getAsString)
    } else if (element.isJsonObject) {
      val obj = element.getAsJsonObject
      if (obj.has("paths") && obj.get("paths").isJsonArray) {
        val paths = obj.getAsJsonArray.asScala.map(_.getAsString).mkString(",")
        textField.setText(paths)
      }
    }
  }
  override def resetToDefault(): Unit = textField.setText("")
  override def isDefaultValue: Boolean = Option(textField.getText).forall(_.trim.isEmpty)
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}

class BytesControl extends FieldInputControl {
  private val MaxFileBytes = 10 * 1024 * 1024
  private val textField = new TextField("")
  textField.setPromptText("Base64-encoded bytes...")
  HBox.setHgrow(textField, Priority.ALWAYS)

  private val fileBtn = new Button("File…", FontIcon.of(Feather.FILE, 11))
  fileBtn.getStyleClass.addAll("small", "subtle", "interactive-hand")
  fileBtn.setTooltip(new Tooltip("Load file contents and encode as Base64"))

  private val infoLbl = new Label("0 B")
  infoLbl.getStyleClass.addAll("info-strip", "mono-text")

  private val row = new HBox(6, textField, fileBtn, infoLbl)
  row.setAlignment(Pos.CENTER_LEFT)

  private val listeners = ListBuffer.empty[() => Unit]

  private def refreshInfo(): Unit = {
    infoLbl.getStyleClass.removeAll("info-strip", "status-text-error")
    val raw = Option(textField.getText).map(_.trim).getOrElse("")
    if (raw.isEmpty) {
      infoLbl.setText("0 B")
      infoLbl.getStyleClass.add("info-strip")
    } else {
      try {
        val decoded = Base64.getDecoder.decode(raw)
        infoLbl.setText(s"${decoded.length} B")
        infoLbl.getStyleClass.add("info-strip")
      } catch {
        case NonFatal(_) =>
          infoLbl.setText("Bad B64")
          infoLbl.getStyleClass.add("status-text-error")
      }
    }
  }

  fileBtn.setOnAction { _ =>
    val chooser = new FileChooser()
    chooser.setTitle("Load Binary File as Base64")
    val owner = Option(row.getScene).map(_.getWindow).orNull
    Option(chooser.showOpenDialog(owner)).foreach { file =>
      if (file.length() <= MaxFileBytes) {
        val bytes = Files.readAllBytes(file.toPath)
        textField.setText(Base64.getEncoder.encodeToString(bytes))
      } else {
        infoLbl.setText(">10MB")
      }
    }
  }

  textField.textProperty().addListener { (_, _, _) =>
    refreshInfo()
    listeners.foreach(_.apply())
  }
  refreshInfo()

  override def getNode: Node = row
  override def getValueJson: String = JsonQuoteHelper.quote(Option(textField.getText).map(_.trim).getOrElse(""))
  override def setValueFromJson(element: JsonElement): Unit = {
    if (element == null || element.isJsonNull) resetToDefault()
    else if (element.isJsonPrimitive) textField.setText(element.getAsString)
  }
  override def resetToDefault(): Unit = textField.setText("")
  override def isDefaultValue: Boolean = Option(textField.getText).forall(_.trim.isEmpty)
  override def onValueChanged(listener: () => Unit): Unit = listeners += listener
}
