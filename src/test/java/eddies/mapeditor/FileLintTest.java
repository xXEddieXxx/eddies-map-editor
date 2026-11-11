package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import eddies.mapeditor.FileLint.Lint;

final class FileLintTest {

  @DisplayName("A broken game XML is reported at its line")
  @Test
  void reportsBrokenXml() {
    final String broken =
"""
<?xml version="1.0"?>
<game>
  <info name="x"
</game>
""";

    final List<Lint> problems = FileLint.lint("my_game.xml", broken);

    assertThat(problems).as("one problem").hasSize(1);
    assertThat(problems.get(0).line()).as("where the parser stopped").isPositive();
  }

  @DisplayName("A wrong line of a point file is found by the line the reader quotes")
  @Test
  void reportsWrongPointLine() {
    final String centers =
"""
Germany (10,20)
France 30,40
""";

    final List<Lint> problems = FileLint.lint("centers.txt", centers);

    assertThat(problems).as("France has no brackets").extracting(Lint::line).containsExactly(2);
  }

  @DisplayName("A wrong line with an apostrophe in its name is found all the same")
  @Test
  void reportsWrongLineWithApostrophe() {
    final String centers =
"""
Germany (10,20)
Cote d'Ivoire 30,40
""";

    final List<Lint> problems = FileLint.lint("centers.txt", centers);

    assertThat(problems).as("the whole quoted line").extracting(Lint::line).containsExactly(2);
  }

  @DisplayName("A size with a space after it reads, as the game trims it")
  @Test
  void acceptsSizeWithSpace() {
    final List<Lint> problems = FileLint.lint("map.properties", "map.width=7000 \n");

    assertThat(problems).as("the game reads 7000").isEmpty();
  }

  @DisplayName("Good centers have no problem")
  @Test
  void acceptsGoodCenters() {
    final String centers =
"""
Germany (10,20)
France (30,40)
""";

    final List<Lint> problems = FileLint.lint("centers.txt", centers);

    assertThat(problems).as("both lines read").isEmpty();
  }

  @DisplayName("map.properties needs colors as six hex digits and sizes as numbers")
  @Test
  void reportsWrongProperties() {
    final String properties =
"""
map.width=7000
color.Germans=grey
map.height=big
""";

    final List<Lint> problems = FileLint.lint("map.properties", properties);

    assertThat(problems)
        .as("the color and the height, at their lines")
        .extracting(Lint::line)
        .containsExactlyInAnyOrder(2, 3);
  }

  @DisplayName("A color with a space after it is refused at its own line, as the game refuses it")
  @Test
  void reportsColorWithSpaceAtItsLine() {
    final String properties =
"""
color.GermansX=00FF00
color.Germans=FF0000\s
""";

    final List<Lint> problems = FileLint.lint("map.properties", properties);

    assertThat(problems)
        .as("color.Germans, not the line of color.GermansX")
        .extracting(Lint::line)
        .containsExactly(2);
  }

  @DisplayName("map.yml without games is reported")
  @Test
  void reportsMapYmlWithoutGames() {
    final List<Lint> problems = FileLint.lint("map.yml", "map_name: Test\n");

    assertThat(problems).as("no games").hasSize(1);
  }

  @DisplayName("A whole map.yml has no problem")
  @Test
  void acceptsWholeMapYml() {
    final String yml =
"""
map_name: Test
games:
- game_name: First
  file_name: first.xml
""";

    final List<Lint> problems = FileLint.lint("map.yml", yml);

    assertThat(problems).as("name and a game").isEmpty();
  }

  @DisplayName("Other files are not checked")
  @Test
  void leavesOtherFiles() {
    assertThat(FileLint.isChecked("notes.html")).as("no check for notes").isFalse();
    assertThat(FileLint.isChecked("other.properties")).as("only map.properties").isFalse();
  }
}
