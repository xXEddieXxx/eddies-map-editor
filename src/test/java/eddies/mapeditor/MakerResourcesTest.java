package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class MakerResourcesTest {

  @DisplayName("Guides & links, the user guide and the README name the one forum address")
  @Test
  void forumIsTheSameEverywhere() throws Exception {
    // given
    final List<String> titles =
        List.of(MakerResources.menu(new JPanel(), () -> {}).getComponents()).stream()
            .filter(JMenuItem.class::isInstance)
            .map(item -> ((JMenuItem) item).getText())
            .toList();

    // when
    final String guide = Files.readString(Path.of("docs/guide/README.md"));
    final String readme = Files.readString(Path.of("README.md"));

    // then
    assertThat(titles)
        .as("the menu has the forum")
        .contains("The map makers' forum: ask, show and discuss");
    assertThat(guide).as("the user guide's start page links it").contains(MakerResources.FORUM);
    assertThat(readme).as("the README links it").contains(MakerResources.FORUM);
  }
}
