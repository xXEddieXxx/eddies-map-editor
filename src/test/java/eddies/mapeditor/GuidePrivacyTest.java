package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What {@link GuideScreenshots} keeps out of the guide's pictures, with a made-up computer. */
final class GuidePrivacyTest {
  private static final String LONG_HOME = "C:\\Users\\Jane Doe";
  private static final String SHORT_TEMP = "C:\\Users\\JANEDO~1\\AppData\\Local\\Temp";

  private static GuidePrivacy givenJanesComputer() {
    return new GuidePrivacy(
        List.of(LONG_HOME),
        List.of(SHORT_TEMP, LONG_HOME + "\\AppData\\Local\\Temp"),
        List.of("Jane Doe"));
  }

  @DisplayName("The home folder becomes a neutral one, with either separator and in any case")
  @Test
  void homeFolder() {
    // given
    final GuidePrivacy own = givenJanesComputer();

    // when, then
    assertThat(own.neutral("Saved in C:\\Users\\Jane Doe\\triplea\\downloadedMaps"))
        .as("as Java names it")
        .isEqualTo("Saved in C:\\Users\\you\\triplea\\downloadedMaps");
    assertThat(own.neutral("file:/c:/users/jane doe/triplea/map.yml"))
        .as("as a link has it")
        .isEqualTo("file:/C:\\Users\\you/triplea/map.yml");
  }

  @DisplayName("The temporary folder becomes a neutral one in its long and its 8.3 form")
  @Test
  void tempFolder() {
    // given
    final GuidePrivacy own = givenJanesComputer();

    // when, then
    assertThat(own.neutral("C:\\Users\\JANEDO~1\\AppData\\Local\\Temp\\junit1\\270bc"))
        .as("the 8.3 form, as java.io.tmpdir often is")
        .isEqualTo("C:\\Temp\\junit1\\270bc");
    assertThat(own.neutral("C:\\Users\\Jane Doe\\AppData\\Local\\Temp\\junit1\\270bc"))
        .as("the long form, not taken for the home folder first")
        .isEqualTo("C:\\Temp\\junit1\\270bc");
  }

  @DisplayName("The user's name and its 8.3 form are found alone too, eg in a folder elsewhere")
  @Test
  void userName() {
    // given
    final GuidePrivacy own = givenJanesComputer();

    // when, then
    assertThat(own.neutral("D:\\maps\\JANEDO~2\\map")).as("8.3").isEqualTo("D:\\maps\\you\\map");
    assertThat(own.neutral("Made by jane doe")).as("in another case").isEqualTo("Made by you");
    assertThat(own.shows("D:\\maps\\janedo~1")).as("told, to fail the picture").isTrue();
  }

  @DisplayName("A home folder that starts with a separator is taken with it")
  @Test
  void unixHome() {
    // given
    final GuidePrivacy own =
        new GuidePrivacy(List.of("/home/jane"), List.of("/tmp"), List.of("jane"));

    // when, then
    assertThat(own.neutral("/home/jane/triplea")).as("home").isEqualTo("C:\\Users\\you/triplea");
    assertThat(own.neutral("/tmp/junit1")).as("temporary").isEqualTo("C:\\Temp/junit1");
  }

  @DisplayName("A text without the computer's folders and names stays and shows nothing")
  @Test
  void otherTexts() {
    // given
    final GuidePrivacy own = givenJanesComputer();
    final String text = "C:\\Users\\you\\triplea · 270BC · Janet";

    // when, then
    assertThat(own.neutral(text)).as("unchanged").isEqualTo(text);
    assertThat(own.shows(text)).as("nothing to fail for").isFalse();
  }

  @DisplayName("This computer's own folders and user name are found")
  @Test
  void thisComputer() {
    // given
    final GuidePrivacy own = GuidePrivacy.ofThisComputer();

    // when, then
    assertThat(own.neutral(System.getProperty("user.home") + "\\triplea"))
        .as("the home folder")
        .isEqualTo(GuidePrivacy.HOME + "\\triplea");
    assertThat(own.neutral(System.getProperty("java.io.tmpdir")))
        .as("the temporary folder, whatever ends it")
        .startsWith(GuidePrivacy.TEMP);
    assertThat(own.shows("of " + System.getProperty("user.name"))).as("the name").isTrue();
  }
}
