package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The user guide (docs/USER-GUIDE.md) names every tool, every view and every menu item the editor
 * has: a new one without a word in the guide fails here, so the guide moves with the editor. The
 * names are read from the sources, as the editor shows them.
 */
final class UserGuideTest {
  private static final Path SOURCES = Path.of("src/main/java/eddies/mapeditor");
  private static final Path GUIDE = Path.of("docs/USER-GUIDE.md");

  private static String guide() throws IOException {
    return Files.readString(GUIDE);
  }

  private static String source(final String file) throws IOException {
    return Files.readString(SOURCES.resolve(file));
  }

  private static List<String> found(final String text, final String regex) {
    final List<String> all = new ArrayList<>();
    final Matcher matcher = Pattern.compile(regex).matcher(text);
    while (matcher.find()) {
      all.add(matcher.group(1));
    }
    return all;
  }

  private static List<String> missing(final String guide, final List<String> names) {
    return names.stream().filter(name -> !guide.contains(name)).toList();
  }

  @DisplayName("Every tool's title and every sidebar group is a heading or a word in the guide")
  @Test
  void namesEveryTool() throws Exception {
    final List<String> titles = new ArrayList<>();
    try (Stream<Path> files = Files.list(SOURCES)) {
      for (final Path file : files.sorted().toList()) {
        final String text = Files.readString(file);
        if (text.contains("implements Editor") || text.contains("extends JPanel implements Editor")) {
          titles.addAll(found(text, "public String title\\(\\) \\{\\s*return \"([^\"]+)\";"));
        }
      }
    }
    final List<String> groups =
        found(source("MapMakerWindow.java"), "Sidebar\\.Group\\(\\s*\"([^\"]+)\"");

    assertThat(titles).as("the tools, read from the sources").hasSizeGreaterThan(15);
    assertThat(missing(guide(), titles)).as("tools the guide does not name").isEmpty();
    assertThat(missing(guide(), groups)).as("sidebar groups the guide does not name").isEmpty();
  }

  @DisplayName("Every view of the map and every item of the File and map picture menus is in it")
  @Test
  void namesEveryMenuItemAndView() throws Exception {
    final String window = source("MapMakerWindow.java");
    final String view = source("MapView.java");
    final List<String> fileMenu = new ArrayList<>();
    fileMenu.addAll(found(window, "menuItem\\(\"([^\"]+)\""));
    fileMenu.addAll(found(window, "menu\\.add\\(\"([^\"]+)\"\\)"));
    fileMenu.addAll(found(window, "new JMenuItem\\(\"([^\"]+)\"\\)"));
    fileMenu.addAll(found(window, "new JCheckBoxMenuItem\\(\"([^\"]+)\""));
    final List<String> pictureMenu = new ArrayList<>();
    pictureMenu.addAll(found(view, "pictureMenu\\.add\\(\"([^\"]+)\"\\)"));
    pictureMenu.addAll(found(view, "new JCheckBoxMenuItem\\(\"([^\"]+)\""));
    final List<String> views = found(source("MapMode.java"), "\\(\\s*\"([A-Z][a-z]+)\",");
    // the system's folder name differs per system: the guide names both
    final List<String> fileMenuNamed =
        fileMenu.stream().filter(item -> !item.startsWith("Play with")).toList();

    assertThat(fileMenuNamed).as("the File menu, read from the sources").hasSizeGreaterThan(5);
    assertThat(missing(guide(), fileMenuNamed)).as("File menu items the guide does not name").isEmpty();
    assertThat(missing(guide(), pictureMenu))
        .as("map picture menu items the guide does not name")
        .isEmpty();
    assertThat(views).as("the views, read from MapMode").hasSize(6);
    assertThat(missing(guide(), views)).as("views the guide does not name").isEmpty();
    assertThat(guide()).as("Play with, and the folder on both systems")
        .contains("Play with", "Show in Explorer", "Finder");
  }

  @DisplayName("The README links the guide")
  @Test
  void readmeLinksIt() throws Exception {
    assertThat(Files.readString(Path.of("README.md"))).contains("docs/USER-GUIDE.md");
  }
}
