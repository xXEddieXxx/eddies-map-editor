package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.ActionEvent;
import javax.swing.text.View;
import javax.swing.text.WrappedPlainView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class XmlTextAreaTest {

  /** Each character's kind as a digit: 0 text, 1 tag, 2 attribute name, 3 value, 4 comment. */
  private static String kindsOf(final String xml) {
    final StringBuilder kinds = new StringBuilder();
    int state = 0;
    for (int i = 0; i < xml.length(); i++) {
      final int after = XmlTextArea.next(state, xml, i);
      kinds.append(XmlTextArea.kind(state, after, xml.charAt(i)).ordinal());
      state = after;
    }
    return kinds.toString();
  }

  @DisplayName("Tags, attribute names, values, text and comments each get their own color")
  @Test
  void colorsEachPart() {
    final String xml = "<a b=\"c\">x<!--y--></a>";

    final String kinds = kindsOf(xml);

    assertThat(kinds)
        .as("tag, attribute, value, text, comment, closing tag")
        .isEqualTo("1112233310444444441111");
  }

  @DisplayName("A comment or a value over several lines keeps its color on the next lines")
  @Test
  void colorsOverLines() {
    final String xml = "<!-- a\nb -->\n<c d='e\nf'/>";

    final String kinds = kindsOf(xml);

    assertThat(kinds.substring(xml.indexOf('b'), xml.indexOf("-->") + 3))
        .as("the comment's second line")
        .isEqualTo("44444");
    assertThat(kinds.substring(xml.indexOf('f'), xml.indexOf("'/>") + 1))
        .as("the value's second line")
        .isEqualTo("33");
    assertThat(kinds.substring(xml.length() - 2)).as("the tag closes").isEqualTo("11");
  }

  @DisplayName("A \"<\" before a space is text, so a quote after it starts no value")
  @Test
  void lessThanInText() {
    final String xml = "a < b, don't";

    final String kinds = kindsOf(xml);

    assertThat(kinds).as("all text").isEqualTo("0".repeat(xml.length()));
  }

  @DisplayName("A new line takes the indentation of the line it breaks, up to the caret")
  @Test
  void keepsIndentation() {
    final String text = "<a>\n\t  <b/>\n";

    final String atB = XmlTextArea.indentationAt(text, text.indexOf("/>"));
    final String inIndentation = XmlTextArea.indentationAt(text, text.indexOf('\t') + 1);

    assertThat(atB).as("the whole indentation of the line").isEqualTo("\t  ");
    assertThat(inIndentation).as("only the part before the caret").isEqualTo("\t");
  }

  private static XmlTextArea givenReadOnlyText(final String text) {
    final XmlTextArea area = new XmlTextArea();
    area.setText(text);
    area.setEditable(false);
    return area;
  }

  @DisplayName("Enter changes nothing in a file shown read only, eg of another map")
  @Test
  void enterLeavesReadOnlyText() {
    final XmlTextArea area = givenReadOnlyText("<a>\n  <b/>");

    area.getActionMap()
        .get("newIndentedLine")
        .actionPerformed(new ActionEvent(area, ActionEvent.ACTION_PERFORMED, ""));

    assertThat(area.getText()).as("no new line in a read-only text").isEqualTo("<a>\n  <b/>");
  }

  @DisplayName("Wrapped text, eg the game notes, is colored too")
  @Test
  void colorsWrappedText() {
    final XmlTextArea area = new XmlTextArea();

    area.setLineWrap(true);

    final View lines = area.getUI().getRootView(area).getView(0);
    assertThat(lines)
        .as("a view that wraps and colors, not Swing's own")
        .isInstanceOf(WrappedPlainView.class)
        .isNotExactlyInstanceOf(WrappedPlainView.class);
  }
}
