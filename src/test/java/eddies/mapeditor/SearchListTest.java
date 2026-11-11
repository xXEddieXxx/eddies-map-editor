package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SearchListTest {
  private static final int MANY = 8_000;

  private static List<String> givenNames(final int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> "triggerAttachment" + i + " · Russians")
        .toList();
  }

  private static JList<?> listIn(final Container container) {
    return components(container)
        .filter(JList.class::isInstance)
        .map(JList.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Component> components(final Container container) {
    return Stream.of(container.getComponents())
        .flatMap(
            c ->
                Stream.concat(
                    Stream.of(c),
                    c instanceof Container inner ? components(inner) : Stream.empty()));
  }

  @DisplayName("A list of thousands refreshes with one chosen in a moment, keeping it")
  @Test
  void refreshesManyQuickly() {
    final List<String> names = new ArrayList<>(givenNames(MANY));
    final List<String> chosen = new ArrayList<>();
    final SearchList searchList =
        new SearchList("Search", () -> List.copyOf(names), n -> "TriggerAttachment", chosen::add);
    searchList.setSize(300, 600);
    searchList.validate();
    final String picked = names.get(MANY / 2);
    searchList.choose(picked);

    final long start = System.nanoTime();
    names.add("triggerAttachmentNew · Russians");
    searchList.refresh();
    searchList.refresh();
    searchList.validate();
    final long millis = (System.nanoTime() - start) / 1_000_000;

    assertThat(millis).as("two refreshes and a layout of %s names", MANY).isLessThan(3_000);
    assertThat(Optional.ofNullable(listIn(searchList).getSelectedValue()))
        .as("the chosen one stays chosen")
        .contains(picked);
    assertThat(listIn(searchList).getModel().getSize())
        .as("the new name is listed")
        .isEqualTo(MANY + 1);
    assertThat(chosen).as("a refresh does not choose for the map maker").isEmpty();
  }

  @DisplayName("Rows fit a line below the name even when the first name has none")
  @Test
  void rowsFitDetailsAfterAnEmptyOne() {
    final SearchList searchList =
        new SearchList(
            "Search",
            () -> List.of("Neutrals", "Germans"),
            n -> n.equals("Germans") ? "Axis" : "",
            n -> {});
    searchList.refresh();

    // the list SearchList makes holds names
    @SuppressWarnings("unchecked")
    final JList<String> list = (JList<String>) listIn(searchList);
    final int withDetail =
        list.getCellRenderer()
            .getListCellRendererComponent(list, "Germans", 1, false, false)
            .getPreferredSize()
            .height;

    assertThat(list.getFixedCellHeight())
        .as("the first row, without a line below, as high as one with it")
        .isGreaterThanOrEqualTo(withDetail);
  }
}
