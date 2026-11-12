package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class OnTypingTest {

  @DisplayName("Typing runs the action at once, on the Swing thread, for every change")
  @Test
  void runsAtOnceOnTheSwingThread() throws Exception {
    final List<Boolean> onSwingThread = new ArrayList<>();
    final List<Integer> runsWhenTyped = new ArrayList<>();

    SwingUtilities.invokeAndWait(
        () -> {
          final JTextField field = givenField(onSwingThread);
          field.setText("abc");
          runsWhenTyped.add(onSwingThread.size());
          field.setText("");
          runsWhenTyped.add(onSwingThread.size());
        });

    assertThat(runsWhenTyped)
        .as("ran before the typing returned, not a pause later: an insert, then a remove")
        .containsExactly(1, 2);
    assertThat(onSwingThread).as("every run on the Swing thread").containsOnly(true);
  }

  private static JTextField givenField(final List<Boolean> onSwingThread) {
    final JTextField field = new JTextField();
    OnTyping.attach(field, () -> onSwingThread.add(SwingUtilities.isEventDispatchThread()));
    return field;
  }
}
