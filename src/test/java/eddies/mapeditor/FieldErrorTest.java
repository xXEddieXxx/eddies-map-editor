package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import com.formdev.flatlaf.FlatClientProperties;
import java.util.Optional;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class FieldErrorTest {

  @DisplayName("A field's problem shows below it and outlines it, and goes once it is fixed")
  @Test
  void showsAndHidesProblem() {
    final JTextField field = new JTextField();
    final FieldError error = new FieldError(field);
    final boolean shownAtFirst = error.isVisible();

    error.setProblem(Optional.of("A game of that name exists"));

    assertThat(shownAtFirst).as("no problem, nothing shows").isFalse();
    assertThat(error.hasProblem()).as("the problem is there").isTrue();
    assertThat(error.getText())
        .as("the mark before the words, not color alone")
        .isEqualTo("! A game of that name exists");
    assertThat(field.getClientProperty(FlatClientProperties.OUTLINE))
        .as("the field gets the red outline")
        .isEqualTo(FlatClientProperties.OUTLINE_ERROR);

    error.setProblem(Optional.empty());

    assertThat(error.hasProblem()).as("fixed, the problem is gone").isFalse();
    assertThat(error.isVisible()).as("fixed, the line hides").isFalse();
    assertThat(field.getClientProperty(FlatClientProperties.OUTLINE))
        .as("fixed, the outline is the normal one")
        .isNull();
  }
}
