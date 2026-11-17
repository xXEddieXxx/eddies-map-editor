package eddies.mapeditor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The user guide (docs/guide) names every tool, every view and every menu item the editor has,
 * each tool on a page of its own with a picture: a new one without a word in the guide fails
 * here, so the guide moves with the editor. The names are read from the sources, as the editor
 * shows them.
 */
final class UserGuideTest {
  private static final Path SOURCES = Path.of("src/main/java/eddies/mapeditor");
  private static final Path GUIDE = Path.of("docs/guide");

  private static String guide() throws IOException {
    try (Stream<Path> pages = Files.list(GUIDE)) {
      return pages
          .filter(p -> p.toString().endsWith(".md"))
          .map(
              p -> {
                try {
                  return Files.readString(p);
                } catch (final IOException e) {
                  throw new IllegalStateException(e);
                }
              })
          .collect(Collectors.joining("\n"));
    }
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

  /** The tools' titles, read from every Editor's title(). */
  private static List<String> toolTitles() throws IOException {
    final List<String> titles = new ArrayList<>();
    try (Stream<Path> files = Files.list(SOURCES)) {
      for (final Path file : files.sorted().toList()) {
        final String text = Files.readString(file);
        if (text.contains("implements Editor")) {
          titles.addAll(found(text, "public String title\\(\\) \\{\\s*return \"([^\"]+)\";"));
        }
      }
    }
    return titles;
  }

  @DisplayName("Every tool has a page of the guide, with its picture, and the overview links it")
  @Test
  void everyToolHasAPage() throws Exception {
    final String overview = Files.readString(GUIDE.resolve("README.md"));
    final List<String> titles = toolTitles();
    final List<String> withoutPage = new ArrayList<>();
    final List<String> withoutPicture = new ArrayList<>();
    final List<String> notLinked = new ArrayList<>();
    for (final String title : titles) {
      final String slug = title.toLowerCase().replace(' ', '-');
      final Path page = GUIDE.resolve(slug + ".md");
      if (!Files.isRegularFile(page)) {
        withoutPage.add(title);
        continue;
      }
      if (!Files.readString(page).contains("img/" + slug + ".png")
          || !Files.isRegularFile(GUIDE.resolve("img").resolve(slug + ".png"))) {
        withoutPicture.add(title);
      }
      if (!overview.contains("](" + slug + ".md)")) {
        notLinked.add(title);
      }
    }

    assertThat(titles).as("the tools, read from the sources").hasSizeGreaterThan(15);
    assertThat(withoutPage).as("tools without a page docs/guide/<tool>.md").isEmpty();
    assertThat(withoutPicture).as("pages without their picture docs/guide/img/<tool>.png").isEmpty();
    assertThat(notLinked).as("pages the overview does not link").isEmpty();
  }

  @DisplayName("Every sidebar group, view, File menu and map picture menu item is in the guide")
  @Test
  void namesEveryMenuItemAndView() throws Exception {
    final String guide = guide();
    final String window = source("MapMakerWindow.java");
    final String view = source("MapView.java");
    final List<String> groups = found(window, "Sidebar\\.Group\\(\\s*\"([^\"]+)\"");
    final List<String> fileMenu = new ArrayList<>();
    fileMenu.addAll(found(window, "menuItem\\(\"([^\"]+)\""));
    fileMenu.addAll(found(window, "menu\\.add\\(\"([^\"]+)\"\\)"));
    fileMenu.addAll(found(window, "new JMenuItem\\(\"([^\"]+)\"\\)"));
    fileMenu.addAll(found(window, "new JCheckBoxMenuItem\\(\"([^\"]+)\""));
    final List<String> pictureMenu = new ArrayList<>();
    pictureMenu.addAll(found(view, "pictureMenu\\.add\\(\"([^\"]+)\"\\)"));
    pictureMenu.addAll(found(view, "new JMenuItem\\(\"([^\"]+)\"\\)"));
    pictureMenu.addAll(found(view, "new JCheckBoxMenuItem\\(\"([^\"]+)\""));
    final List<String> views = found(source("MapMode.java"), "\\(\\s*\"([A-Z][a-z]+)\",");
    // the system's folder name differs per system: the guide names both
    final List<String> fileMenuNamed =
        fileMenu.stream().filter(item -> !item.startsWith("Play with")).toList();

    assertThat(missing(guide, groups)).as("sidebar groups the guide does not name").isEmpty();
    assertThat(fileMenuNamed).as("the File menu, read from the sources").hasSizeGreaterThan(5);
    assertThat(missing(guide, fileMenuNamed)).as("File menu items the guide does not name").isEmpty();
    assertThat(missing(guide, pictureMenu))
        .as("map picture menu items the guide does not name")
        .isEmpty();
    assertThat(views).as("the views, read from MapMode").hasSize(6);
    assertThat(missing(guide, views)).as("views the guide does not name").isEmpty();
    assertThat(guide)
        .as("Play with, and the folder on both systems")
        .contains("Play with", "Show in Explorer", "Finder");
  }

  @DisplayName("The README links the guide, and every page links back to it")
  @Test
  void readmeLinksIt() throws Exception {
    assertThat(Files.readString(Path.of("README.md"))).contains("docs/guide/README.md");
    try (Stream<Path> pages = Files.list(GUIDE)) {
      final List<String> withoutBackLink =
          pages
              .filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals("README.md"))
              .filter(
                  p -> {
                    try {
                      return !Files.readString(p).contains("[← Guide](README.md)");
                    } catch (final IOException e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .map(p -> p.getFileName().toString())
              .toList();
      assertThat(withoutBackLink).as("pages without the link back to the overview").isEmpty();
    }
  }
}
