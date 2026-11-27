package net.protoform.ui.editor

import javafx.scene.layout.StackPane
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.{CodeArea, LineNumberFactory}
import org.fxmisc.richtext.model.{StyleSpans, StyleSpansBuilder}

import java.util.{Collection => JCollection, Collections}
import java.util.regex.Pattern

class JsonCodeArea(editable: Boolean = true) extends StackPane {

  val codeArea = new CodeArea()
  codeArea.setEditable(editable)
  codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea))
  codeArea.getStyleClass.add("json-code-area")

  private val scrollPane = new VirtualizedScrollPane[CodeArea](codeArea)
  getStyleClass.add("code-editor-wrapper")
  getChildren.add(scrollPane)

  codeArea.textProperty().addListener { (_, _, newText) =>
    applyHighlighting(Option(newText).getOrElse(""))
  }

  def getText: String = codeArea.getText

  def setText(text: String): Unit = {
    val safe = Option(text).getOrElse("")
    codeArea.replaceText(safe)
    codeArea.moveTo(0)
    codeArea.requestFollowCaret()
  }

  def clear(): Unit = setText("")

  private def applyHighlighting(text: String): Unit = {
    if (text.isEmpty || text.length > JsonCodeArea.MaxHighlightChars) return
    try {
      codeArea.setStyleSpans(0, JsonCodeArea.computeHighlighting(text))
    } catch {
      case _: Exception => ()
    }
  }
}

object JsonCodeArea {
  private val MaxHighlightChars = 250000
  private val KEY_PATTERN    = """"(?:\\.|[^"\\])*"(?=\s*:)"""
  private val STRING_PATTERN = """"(?:\\.|[^"\\])*""""
  private val NUMBER_PATTERN = """-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?"""
  private val BOOL_PATTERN   = """\b(?:true|false)\b"""
  private val NULL_PATTERN   = """\bnull\b"""
  private val BRACE_PATTERN  = """[{}\[\]]"""

  private val PATTERN: Pattern = Pattern.compile(
    s"(?<KEY>$KEY_PATTERN)" +
    s"|(?<STRING>$STRING_PATTERN)" +
    s"|(?<NUMBER>$NUMBER_PATTERN)" +
    s"|(?<BOOL>$BOOL_PATTERN)" +
    s"|(?<NULL>$NULL_PATTERN)" +
    s"|(?<BRACE>$BRACE_PATTERN)"
  )

  def computeHighlighting(text: String): StyleSpans[JCollection[String]] = {
    val matcher = PATTERN.matcher(text)
    var lastEnd = 0
    val spansBuilder = new StyleSpansBuilder[JCollection[String]]()

    while (matcher.find()) {
      val styleClass =
        if (matcher.group("KEY") != null) "json-key"
        else if (matcher.group("STRING") != null) "json-string"
        else if (matcher.group("NUMBER") != null) "json-number"
        else if (matcher.group("BOOL") != null) "json-bool"
        else if (matcher.group("NULL") != null) "json-null"
        else if (matcher.group("BRACE") != null) "json-brace"
        else null

      spansBuilder.add(Collections.emptyList[String](), matcher.start() - lastEnd)
      spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start())
      lastEnd = matcher.end()
    }
    spansBuilder.add(Collections.emptyList[String](), text.length - lastEnd)
    spansBuilder.create()
  }
}
