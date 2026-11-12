package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Font;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LineNumbersTest {

  @DisplayName("The line numbers are as high as the text, in the font set after them")
  @Test
  void asHighAsTheText() {
    final XmlTextArea area = new XmlTextArea();
    final LineNumbers numbers = new LineNumbers(area);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 31));
    area.setText("<a/>\n".repeat(3000));

    final int numbersHeight = numbers.getPreferredSize().height;

    assertThat(numbersHeight)
        .as("one number beside each line, down to the last")
        .isEqualTo(area.getPreferredSize().height);
  }

  @DisplayName("A wrapped line keeps one number, at its start; the next line's is below its rows")
  @Test
  void wrappedLineKeepsOneNumber() {
    final XmlTextArea area = givenWrappedText("word ".repeat(40) + "\nshort");
    final LineNumbers numbers = new LineNumbers(area);
    final int row = area.getFontMetrics(area.getFont()).getHeight();

    final List<Integer> tops = numbers.lineTops();

    assertThat(tops).as("a number per line of the file, not per row").hasSize(2);
    assertThat(tops.get(1) - tops.get(0))
        .as("the long line takes several rows before the next number")
        .isGreaterThan(row);
  }

  private static XmlTextArea givenWrappedText(final String text) {
    final XmlTextArea area = new XmlTextArea();
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setText(text);
    area.setSize(200, 1000);
    return area;
  }
}
