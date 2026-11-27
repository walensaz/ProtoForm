package net.protoform.ui.forms

import com.google.gson.{JsonElement, JsonObject, JsonParser}
import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control._
import javafx.scene.layout._
import net.protoform.core.schema.{FieldSchema, ProtoType}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object DynamicFormView {
  val MaxRecursionDepth = 8
}

class DynamicFormView(
  val messageType: ProtoType.MessageType,
  val isNested: Boolean = false,
  val visitedTypes: Set[String] = Set.empty,
  val depth: Int = 0,
  initialAdvancedBadges: Boolean = false,
  responseTypeSimpleName: Option[String] = None
) extends VBox(0) {
  import DynamicFormView.MaxRecursionDepth

  private var advancedBadges: Boolean = initialAdvancedBadges

  private sealed trait FormEntry {
    def resetToDefault(): Unit
    def collectEntries(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): List[(String, String)]
    def populateFromObject(obj: JsonObject): Unit
    def updateDefaultBadges(showDefaults: Boolean): Unit
    def setAdvancedMode(advanced: Boolean): Unit
  }

  private case class SingleFieldEntry(
    field: FieldSchema,
    ctrl: FieldInputControl,
    optionalCheck: Option[CheckBox],
    defaultBadge: Label,
    typeBadge: Label,
    numberBadge: Label,
    compactMetaLabel: Label
  ) extends FormEntry {
    def isAtDefault: Boolean = optionalCheck.fold(ctrl.isDefaultValue)(cb => !cb.isSelected)

    override def resetToDefault(): Unit = {
      optionalCheck.foreach(_.setSelected(false))
      ctrl.resetToDefault()
      refreshBadge()
    }

    override def collectEntries(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): List[(String, String)] = {
      val name = if (preserveProtoFieldNames) field.name else field.jsonName
      optionalCheck match {
        case Some(cb) =>
          if (!cb.isSelected) Nil
          else List(name -> ctrl.getValueJson(includeDefaults, preserveProtoFieldNames))
        case None =>
          if (!includeDefaults && ctrl.isDefaultValue) Nil
          else List(name -> ctrl.getValueJson(includeDefaults, preserveProtoFieldNames))
      }
    }

    override def populateFromObject(obj: JsonObject): Unit = {
      findFieldValue(obj, field) match {
        case Some(el) if optionalCheck.isEmpty || !el.isJsonNull =>
          optionalCheck.foreach(_.setSelected(true))
          ctrl.setValueFromJson(el)
        case _ =>
          optionalCheck.foreach(_.setSelected(false))
          ctrl.resetToDefault()
      }
      refreshBadge()
    }

    override def updateDefaultBadges(showDefaults: Boolean): Unit = {
      refreshBadge()
    }

    override def setAdvancedMode(advanced: Boolean): Unit = {
      typeBadge.setVisible(advanced); typeBadge.setManaged(advanced)
      numberBadge.setVisible(advanced); numberBadge.setManaged(advanced)
      compactMetaLabel.setVisible(!advanced); compactMetaLabel.setManaged(!advanced)
      refreshBadge()
      ctrl match {
        case nested: NestedMessageControl => nested.subForm.setAdvancedBadges(advanced)
        case _                            => ()
      }
    }

    def refreshBadge(): Unit = {
      optionalCheck match {
        case Some(cb) =>
          val enabled = cb.isSelected
          defaultBadge.setText(if (!enabled) "optional" else if (ctrl.isDefaultValue) "set" else "edited")
          defaultBadge.getStyleClass.removeAll("badge-default", "badge-edited")
          defaultBadge.getStyleClass.add(if (enabled) "badge-edited" else "badge-default")
          defaultBadge.setVisible(true)
          defaultBadge.setManaged(true)
        case None =>
          val atDefault = ctrl.isDefaultValue
          defaultBadge.setText(if (atDefault) "default" else "edited")
          defaultBadge.getStyleClass.removeAll("badge-default", "badge-edited")
          defaultBadge.getStyleClass.add(if (atDefault) "badge-default" else "badge-edited")
          val showState = advancedBadges || !atDefault
          defaultBadge.setVisible(showState)
          defaultBadge.setManaged(showState)
      }
    }
  }

  private case class OneofEntry(
    groupName: String,
    control: OneofFieldControl
  ) extends FormEntry {
    override def resetToDefault(): Unit = control.resetToDefault()
    override def collectEntries(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): List[(String, String)] = {
      control.collectEntry(includeDefaults, preserveProtoFieldNames).toList
    }
    override def populateFromObject(obj: JsonObject): Unit = {
      control.populateFromObject(obj)
    }
    override def updateDefaultBadges(showDefaults: Boolean): Unit = {
      control.refreshStateBadge()
    }
    override def setAdvancedMode(advanced: Boolean): Unit = {
      control.setAdvancedMode(advanced)
    }
  }

  private val entries = ListBuffer.empty[FormEntry]
  private val changeListeners = ListBuffer.empty[() => Unit]

  setPadding(new Insets(if (isNested) 2 else 0))
  getStyleClass.add("dynamic-form-view")

  buildFields()

  def onFormChanged(listener: () => Unit): Unit = {
    changeListeners += listener
  }

  private def notifyChanged(): Unit = {
    changeListeners.foreach(_.apply())
  }

  def buildFields(): Unit = {
    getChildren.clear()
    entries.clear()

    if (depth >= MaxRecursionDepth || visitedTypes.contains(messageType.fullName)) {
      val cycleCard = new VBox(4)
      cycleCard.getStyleClass.add("elevated-card")
      val msg = new Label(s"Recursive message '${messageType.simpleName}' stopped at depth $depth (edit via JSON View if deeper nesting is needed).")
      msg.getStyleClass.add("form-subtext")
      msg.setWrapText(true)
      cycleCard.getChildren.add(msg)
      getChildren.add(cycleCard)
      return
    }

    val fields = messageType.fields()
    if (fields.isEmpty) {
      val emptyLabel = new Label("This RPC method requires no input parameters (Empty message).")
      emptyLabel.getStyleClass.add("form-subtext")
      getChildren.add(emptyLabel)
      return
    }

    val nextVisited = visitedTypes + messageType.fullName
    val renderedOneofs = mutable.Set.empty[String]
    val rowsBox = new VBox(0)

    fields.foreach { field =>
      field.oneofGroup match {
        case Some(groupName) =>
          if (renderedOneofs.add(groupName)) {
            val oneofFields = fields.filter(_.oneofGroup.contains(groupName))
            val oneofCtrl = new OneofFieldControl(
              groupName,
              oneofFields,
              branchField => createFieldControl(branchField, nextVisited, depth + 1)
            )
            oneofCtrl.setAdvancedMode(advancedBadges)
            oneofCtrl.onValueChanged(() => notifyChanged())
            entries += OneofEntry(groupName, oneofCtrl)
            rowsBox.getChildren.add(oneofCtrl.container)
          }

        case None =>
          val ctrl = createFieldControl(field, nextVisited, depth + 1)
          val optionalCheck = if (field.isOptional) {
            val cb = new CheckBox()
            cb.getStyleClass.add("interactive-hand")
            cb.setSelected(false)
            cb.setTooltip(new Tooltip("Enable to include this optional field in the request"))
            ctrl.getNode.disableProperty().bind(cb.selectedProperty().not())
            Some(cb)
          } else None

          val defaultBadge = new Label(if (field.isOptional) "optional" else "default")
          defaultBadge.getStyleClass.addAll("badge-field-num", "badge-default")

          val typeBadge = new Label(formatFieldType(field))
          typeBadge.getStyleClass.addAll("badge-type", getFieldTypeBadgeClass(field))

          val numberBadge = new Label(s"#${field.number}")
          numberBadge.getStyleClass.add("badge-field-num")

          val optPrefix = if (field.isOptional) "optional " else ""
          val compactMetaLabel = new Label(s"$optPrefix${formatFieldType(field)} · #${field.number}")
          compactMetaLabel.getStyleClass.add("field-meta-mono")

          val entry = SingleFieldEntry(field, ctrl, optionalCheck, defaultBadge, typeBadge, numberBadge, compactMetaLabel)
          entry.setAdvancedMode(advancedBadges)
          optionalCheck.foreach { cb =>
            cb.selectedProperty().addListener { (_, _, _) =>
              entry.refreshBadge()
              notifyChanged()
            }
          }
          ctrl.onValueChanged { () =>
            entry.refreshBadge()
            notifyChanged()
          }
          entries += entry

          val fieldRow = new VBox(6)
          fieldRow.getStyleClass.add("message-field-row")

          val header = new HBox(7)
          header.setAlignment(Pos.CENTER_LEFT)

          val nameLabel = new Label(field.name)
          nameLabel.getStyleClass.add("form-label")
          nameLabel.setTooltip(new Tooltip(s"Proto field: ${messageType.fullName}.${field.name} (#${field.number}), jsonName: ${field.jsonName}"))

          val headerSpacer = new Region()
          HBox.setHgrow(headerSpacer, Priority.ALWAYS)

          optionalCheck.foreach(cb => header.getChildren.add(cb))
          header.getChildren.addAll(nameLabel, typeBadge, defaultBadge, headerSpacer, compactMetaLabel, numberBadge)
          fieldRow.getChildren.addAll(header, ctrl.getNode)
          rowsBox.getChildren.add(fieldRow)
      }
    }

    if (!rowsBox.getChildren.isEmpty) {
      rowsBox.getChildren.get(rowsBox.getChildren.size() - 1).getStyleClass.add("message-field-row-last")
    }

    if (isNested) {
      getChildren.add(rowsBox)
    } else {
      val msgTitle = new Label(messageType.simpleName)
      msgTitle.getStyleClass.add("section-title")

      val headerChildren = ListBuffer[Node](msgTitle)
      responseTypeSimpleName.foreach { resp =>
        val arrowLbl = new Label(s"→  $resp")
        arrowLbl.getStyleClass.add("info-strip")
        headerChildren += arrowLbl
      }
      val hSpacer = new Region()
      HBox.setHgrow(hSpacer, Priority.ALWAYS)
      headerChildren += hSpacer

      val countLbl = new Label(s"${fields.size} ${if (fields.size == 1) "field" else "fields"}")
      countLbl.getStyleClass.add("field-meta-mono")
      headerChildren += countLbl

      val objectHeader = new HBox(8)
      objectHeader.setAlignment(Pos.CENTER_LEFT)
      objectHeader.getStyleClass.add("message-object-header")
      objectHeader.getChildren.addAll(headerChildren.asJava)

      val objectCard = new VBox(0, objectHeader, rowsBox)
      objectCard.getStyleClass.add("message-object-card")
      getChildren.add(objectCard)
    }
  }

  private def findFieldValue(obj: JsonObject, field: FieldSchema): Option[JsonElement] = {
    if (obj.has(field.name)) Some(obj.get(field.name))
    else if (obj.has(field.jsonName)) Some(obj.get(field.jsonName))
    else None
  }

  private def getFieldTypeBadgeClass(field: FieldSchema): String = {
    if (field.isMap) "badge-type-map"
    else if (field.isRepeated) "badge-type-repeated"
    else field.protoType match {
      case ProtoType.StringType | ProtoType.BytesType => "badge-type-string"
      case ProtoType.DoubleType | ProtoType.FloatType |
           ProtoType.Int64Type | ProtoType.UInt64Type | ProtoType.Int32Type |
           ProtoType.Fixed64Type | ProtoType.Fixed32Type | ProtoType.UInt32Type |
           ProtoType.SFixed32Type | ProtoType.SFixed64Type |
           ProtoType.SInt32Type | ProtoType.SInt64Type => "badge-type-numeric"
      case ProtoType.BoolType        => "badge-type-bool"
      case _: ProtoType.EnumType     => "badge-type-enum"
      case _: ProtoType.MessageType  => "badge-type-message"
      case _: ProtoType.MapType      => "badge-type-map"
    }
  }

  private def createFieldControl(
    field: FieldSchema,
    nextVisited: Set[String],
    nextDepth: Int
  ): FieldInputControl = {
    field.protoType match {
      case ProtoType.MapType(keyType, valueType) =>
        new MapFieldControl(
          () => createControlForType(keyType, nextVisited, nextDepth),
          () => createControlForType(valueType, nextVisited, nextDepth)
        )
      case _ if field.isRepeated =>
        new RepeatedFieldControl(() => createControlForType(field.protoType, nextVisited, nextDepth))
      case singleType =>
        createControlForType(singleType, nextVisited, nextDepth)
    }
  }

  private def createControlForType(
    protoType: ProtoType,
    nextVisited: Set[String],
    nextDepth: Int
  ): FieldInputControl = {
    protoType match {
      case mt: ProtoType.MessageType =>
        mt.fullName match {
          case "google.protobuf.Timestamp" => new TimestampControl()
          case "google.protobuf.Duration"  => new DurationControl()
          case "google.protobuf.FieldMask" => new FieldMaskControl()
          case _                           => new NestedMessageControl(mt, nextVisited, nextDepth, advancedBadges)
        }
      case ProtoType.BytesType =>
        new BytesControl()
      case ProtoType.StringType =>
        new ScalarStringControl("")
      case ProtoType.DoubleType | ProtoType.FloatType =>
        new ScalarNumericControl("0.0", isFloating = true)
      case ProtoType.Int64Type | ProtoType.UInt64Type | ProtoType.Int32Type |
           ProtoType.Fixed64Type | ProtoType.Fixed32Type | ProtoType.UInt32Type |
           ProtoType.SFixed32Type | ProtoType.SFixed64Type |
           ProtoType.SInt32Type | ProtoType.SInt64Type =>
        new ScalarNumericControl("0", isFloating = false)
      case ProtoType.BoolType =>
        new ScalarBoolControl(false)
      case ProtoType.EnumType(_, values) =>
        val enumValues = values.map(v => (v.name, v.number))
        val defaultVal = enumValues.headOption.map(_._1).getOrElse("")
        new EnumControl(enumValues, defaultVal)
      case ProtoType.MapType(k, v) =>
        new MapFieldControl(
          () => createControlForType(k, nextVisited, nextDepth),
          () => createControlForType(v, nextVisited, nextDepth)
        )
    }
  }

  private def formatFieldType(field: FieldSchema): String = {
    def shortType(pt: ProtoType): String = pt match {
      case mt: ProtoType.MessageType => mt.simpleName
      case et: ProtoType.EnumType    => et.simpleName
      case ProtoType.MapType(k, v)   => s"map<${shortType(k)}, ${shortType(v)}>"
      case other                     => other.typeName
    }

    val baseType = shortType(field.protoType)
    if (field.isRepeated && !field.isMap) s"repeated $baseType" else baseType
  }

  def collectJson(includeDefaults: Boolean = true, preserveProtoFieldNames: Boolean = true): String = {
    val collected = entries.flatMap(_.collectEntries(includeDefaults, preserveProtoFieldNames)).toList
    if (collected.isEmpty) {
      "{}"
    } else {
      val indent = "  " * (depth + 1)
      val closingIndent = "  " * depth
      val lines = collected.map { case (name, valueJson) =>
        s"""$indent"$name": $valueJson"""
      }
      s"{\n${lines.mkString(",\n")}\n$closingIndent}"
    }
  }

  def populateFromJson(json: String): Either[String, Unit] = {
    val trimmed = Option(json).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      resetAllToDefaults()
      return Right(())
    }
    try {
      val el = JsonParser.parseString(trimmed)
      if (el == null || !el.isJsonObject) {
        Left("Request JSON must be a JSON object to populate the form.")
      } else {
        populateFromObject(el.getAsJsonObject)
        Right(())
      }
    } catch {
      case NonFatal(e) =>
        Left(Option(e.getMessage).getOrElse("Invalid JSON syntax"))
    }
  }

  def populateFromObject(obj: JsonObject): Unit = {
    entries.foreach(_.populateFromObject(obj))
  }

  def isAllDefault: Boolean = {
    entries.forall {
      case s: SingleFieldEntry => s.isAtDefault
      case o: OneofEntry       => o.control.isUnset
    }
  }

  def refreshDefaultBadges(showDefaults: Boolean): Unit = {
    entries.foreach(_.updateDefaultBadges(showDefaults))
  }

  def setAdvancedBadges(advanced: Boolean): Unit = {
    advancedBadges = advanced
    entries.foreach(_.setAdvancedMode(advanced))
  }

  def resetAllToDefaults(): Unit = {
    entries.foreach(_.resetToDefault())
  }
}

class NestedMessageControl(
  messageType: ProtoType.MessageType,
  visitedTypes: Set[String],
  depth: Int,
  initialAdvancedBadges: Boolean = false
) extends FieldInputControl {
  val subForm = new DynamicFormView(messageType, isNested = true, visitedTypes = visitedTypes, depth = depth, initialAdvancedBadges = initialAdvancedBadges)
  val titledPane = new TitledPane(s"${messageType.simpleName} (Message)", subForm)
  titledPane.getStyleClass.add("nested-message-pane")
  titledPane.setExpanded(true)

  override def getNode: Node = titledPane
  override def getValueJson: String = getValueJson(includeDefaults = true, preserveProtoFieldNames = true)
  override def getValueJson(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): String =
    subForm.collectJson(includeDefaults, preserveProtoFieldNames)

  override def setValueFromJson(element: JsonElement): Unit = {
    if (element != null && element.isJsonObject) {
      subForm.populateFromObject(element.getAsJsonObject)
    } else {
      subForm.resetAllToDefaults()
    }
  }

  override def resetToDefault(): Unit = subForm.resetAllToDefaults()
  override def isDefaultValue: Boolean = subForm.isAllDefault
  override def onValueChanged(listener: () => Unit): Unit = subForm.onFormChanged(listener)
}

class OneofFieldControl(
  groupName: String,
  fields: List[FieldSchema],
  controlFactory: FieldSchema => FieldInputControl
) {
  private val UnsetOption = "[None / Unset]"
  private val branchControls: Map[String, (FieldSchema, FieldInputControl)] =
    fields.map { field =>
      val ctrl = controlFactory(field)
      field.name -> (field, ctrl)
    }.toMap

  private val listeners = ListBuffer.empty[() => Unit]

  val container = new VBox(6)
  container.getStyleClass.add("message-field-row")

  private val header = new HBox(8)
  header.setAlignment(Pos.CENTER_LEFT)
  private val titleLabel = new Label(s"oneof $groupName")
  titleLabel.getStyleClass.add("form-label")
  private val badge = new Label("oneof")
  badge.getStyleClass.addAll("badge-type", "badge-type-enum")
  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)
  private val stateBadge = new Label("unset")
  stateBadge.getStyleClass.addAll("badge-field-num", "badge-default")

  private val branchCombo = new ComboBox[String]()
  branchCombo.getStyleClass.addAll("small", "interactive-hand")
  branchCombo.getItems.add(UnsetOption)
  branchCombo.getItems.addAll(fields.map(_.name).asJava)
  branchCombo.setValue(fields.headOption.map(_.name).getOrElse(UnsetOption))

  header.getChildren.addAll(titleLabel, badge, spacer, stateBadge, branchCombo)

  private val activeHolder = new StackPane()
  activeHolder.getStyleClass.add("oneof-branch-box")
  container.getChildren.addAll(header, activeHolder)

  branchControls.values.foreach { case (_, ctrl) =>
    ctrl.onValueChanged { () =>
      refreshStateBadge()
      listeners.foreach(_.apply())
    }
  }

  branchCombo.valueProperty().addListener { (_, _, selected) =>
    renderBranch(selected)
    refreshStateBadge()
    listeners.foreach(_.apply())
  }

  renderBranch(branchCombo.getValue)
  refreshStateBadge()

  private def renderBranch(selected: String): Unit = {
    activeHolder.getChildren.clear()
    val branchOpt = Option(selected).flatMap(branchControls.get)
    branchOpt.foreach { case (_, ctrl) =>
      activeHolder.getChildren.add(ctrl.getNode)
    }
    val hasBranch = branchOpt.isDefined
    activeHolder.setVisible(hasBranch)
    activeHolder.setManaged(hasBranch)
  }

  private var advancedMode: Boolean = false

  private def refreshStateVisibility(): Unit = {
    val showState = advancedMode || !isUnset
    stateBadge.setVisible(showState)
    stateBadge.setManaged(showState)
  }

  def setAdvancedMode(advanced: Boolean): Unit = {
    advancedMode = advanced
    badge.setVisible(advanced); badge.setManaged(advanced)
    refreshStateVisibility()
  }

  def isUnset: Boolean = Option(branchCombo.getValue).forall(_ == UnsetOption)

  def refreshStateBadge(): Unit = {
    val active = Option(branchCombo.getValue).getOrElse(UnsetOption)
    stateBadge.setText(if (active == UnsetOption) "unset" else s"active: $active")
    stateBadge.getStyleClass.removeAll("badge-default", "badge-edited")
    stateBadge.getStyleClass.add(if (active == UnsetOption) "badge-default" else "badge-edited")
    refreshStateVisibility()
  }

  def collectEntry(includeDefaults: Boolean, preserveProtoFieldNames: Boolean): Option[(String, String)] = {
    Option(branchCombo.getValue).flatMap(branchControls.get).flatMap { case (field, ctrl) =>
      if (!includeDefaults && ctrl.isDefaultValue && isUnset) {
        None
      } else {
        val key = if (preserveProtoFieldNames) field.name else field.jsonName
        Some(key -> ctrl.getValueJson(includeDefaults, preserveProtoFieldNames))
      }
    }
  }

  def populateFromObject(obj: JsonObject): Unit = {
    val matched = fields.find(f => obj.has(f.name) || obj.has(f.jsonName))
    matched match {
      case Some(field) =>
        branchCombo.setValue(field.name)
        val el = if (obj.has(field.name)) obj.get(field.name) else obj.get(field.jsonName)
        branchControls.get(field.name).foreach(_._2.setValueFromJson(el))
      case None =>
        branchCombo.setValue(UnsetOption)
    }
    refreshStateBadge()
  }

  def resetToDefault(): Unit = {
    branchControls.values.foreach(_._2.resetToDefault())
    branchCombo.setValue(fields.headOption.map(_.name).getOrElse(UnsetOption))
    refreshStateBadge()
  }

  def onValueChanged(listener: () => Unit): Unit = listeners += listener
}
