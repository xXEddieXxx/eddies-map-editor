package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import javax.swing.JSpinner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class FormTest {

  @DisplayName("A number field shows its digits as the files have them, without grouping")
  @Test
  void numbersWithoutGrouping() {
    final Locale before = Locale.getDefault();
    Locale.setDefault(Locale.GERMANY);
    try {
      final JSpinner number = Form.newNumber(2300, 10_000, value -> {});

      final String shown = ((JSpinner.DefaultEditor) number.getEditor()).getTextField().getText();

      assertThat(shown).as("no '2.300' on a German system").isEqualTo("2300");
    } finally {
      Locale.setDefault(before);
    }
  }
}
