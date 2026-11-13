package eddies.mapeditor;

import com.formdev.flatlaf.util.UIScale;
import eddies.mapeditor.MakerIcon.Glyph;
import eddies.mapeditor.MapChecks.Problem;
import eddies.mapeditor.MapFolder.MapDetails;
import eddies.mapeditor.Theme.Status;
import games.strategy.engine.framework.ui.GameNotesView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import lombok.extern.slf4j.Slf4j;
import org.triplea.awt.OpenFileUtility;
import org.triplea.map.data.elements.Game;
import org.triplea.map.description.file.MapDescriptionYaml;
import org.triplea.swing.SwingComponents;

/**
 * What a map needs before players can download it: its name and version in map.yml, the
 * description and the preview picture the list of maps shows, a list of what is still missing, each
 * with the button that fixes it, and the steps of TripleA's upload guide. The editor does no git:
 * it leads there. All of it is written at once, outside undo, with ".bak", as the other map.yml
 * changes are.
 */
@Slf4j
final class PublishEditor implements Editor {
  static final String TITLE = "Publish";
  private static final int MAX_WIDTH = 1100;
  private static final int TEXT_WIDTH = 480;
  private static final int HINT_WIDTH = 400;
  private static final int DESCRIPTION_HEIGHT = 260;
  private static final int PREVIEW_WIDTH = 240;
  // MapDescriptionYaml.MAX_MAP_NAME_LENGTH
  private static final int NAME_MAX_LENGTH = 70;
  private static final String GUIDE =
      "https://github.com/triplea-game/triplea/blob/main/docs/map-making/how-to/"
          + "uploading-a-map-to-triplea.md";
  private static final Pattern INFO_VERSION =
      Pattern.compile("<info\\b[^>]*\\bversion\\s*=\\s*\"([^\"]*)\"");
  private static final Pattern MINIMUM_VERSION =
      Pattern.compile("<triplea\\b[^>]*\\bminimumVersion\\s*=\\s*\"([^\"]*)\"");

  /** What the list checks. */
  enum Id {
    CHECK,
    MAP_YML,
    NAME,
    VERSION,
    DESCRIPTION,
    PREVIEW,
    FOLDER,
    LEFTOVERS
  }

  /**
   * One line of the list.
   *
   * @param hint what to do about it, for a line that is not done
   */
  record Item(Id id, Status status, String text, String hint) {
    boolean done() {
      return status == Status.DONE;
    }
  }

  /** A line of the list on screen, kept while its item changes, so a click on its button counts. */
  private record Row(JPanel panel, JLabel text, JButton fix, JLabel hint) {}

  private final MapProject project;
  private final Runnable check;
  private final Runnable export;
  private final JPanel page = new JPanel(new BorderLayout());
  private final Form form = new Form(TEXT_WIDTH);
  private final Form cards = new Form(TEXT_WIDTH);
  private final JTextField name = new JTextField();
  private final JTextField version = new JTextField(6);
  private final JLabel ready = Theme.newSecondaryLabel("");
  private final Map<Id, Row> rows = new EnumMap<>(Id.class);
  private final Map<Id, Item> items = new EnumMap<>(Id.class);
  private final XmlTextArea description = new XmlTextArea();
  private final GameNotesView preview = new GameNotesView();
  private final JLabel descriptionLength = Theme.newSecondaryLabel("");
  private final JComponent descriptionPanes;
  // the description as its file has it: typed text that differs is still to be written
  private String writtenDescription = "";
  // how many problems Check found, for the game and files it checked; empty before the first
  private Optional<Integer> problems = Optional.empty();
  private @Nullable Game checkedGame;
  private @Nullable MapFiles checkedFiles;
  // the map's name and preview picture as the list of maps shows them above the description
  private String listedHead = "";

  /**
   * @param check runs Check, whose result the window passes to {@link #setChecked}
   * @param export exports the map as a zip
   */
  PublishEditor(final MapProject project, final Runnable check, final Runnable export) {
    this.project = project;
    this.check = check;
    this.export = export;
    cards.setBorder(BorderFactory.createEmptyBorder());
    onLeaving(name, this::writeName);
    name.addActionListener(e -> writeName());
    onLeaving(version, () -> writeVersion(version.getText()));
    version.addActionListener(e -> writeVersion(version.getText()));
    description.setLineWrap(true);
    description.setWrapStyleWord(true);
    description.colorAsXml();
    description.getAccessibleContext().setAccessibleName("Description");
    OnTyping.attach(description, this::showDescription);
    onLeaving(description, this::writeDescription);
    descriptionPanes =
        Panels.textAndPreview(
            SwingComponents.newJScrollPane(description), SwingComponents.newJScrollPane(preview));
    descriptionPanes.setPreferredSize(new Dimension(0, UIScale.scale(DESCRIPTION_HEIGHT)));
    for (final Id id : Id.values()) {
      rows.put(id, newRow(id));
    }
    final JScrollPane scrolled = SwingComponents.newJScrollPane(form);
    scrolled.setMaximumSize(new Dimension(UIScale.scale(MAX_WIDTH), Integer.MAX_VALUE));
    // a box gives it its maximum width at most, from the left
    final JPanel capped = new JPanel();
    capped.setLayout(new BoxLayout(capped, BoxLayout.X_AXIS));
    capped.add(scrolled);
    page.add(capped, BorderLayout.CENTER);
    // laid out when the tool shows: it reads the map's folder
    project.onChange(
        () -> {
          if (page.isShowing()) {
            fill();
          }
        });
  }

  private static void onLeaving(final JComponent field, final Runnable action) {
    field.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusLost(final FocusEvent e) {
            action.run();
          }
        });
  }

  @Override
  public String title() {
    return TITLE;
  }

  @Override
  public Glyph glyph() {
    return Glyph.PUBLISH;
  }

  @Override
  public String hint() {
    return "What your map needs before players can download it in TripleA: its name and version,"
        + " the description and picture the list of maps shows, and the steps to upload it."
        + " Written at once into the map's folder.";
  }

  @Override
  public JComponent page() {
    return page;
  }

  @Override
  public void showing() {
    // files may have changed in another tool or outside, eg a .bak of a save
    fill();
  }

  @Override
  public void commit() {
    writeDescription();
  }

  /** What Check found for the map as it is now. */
  void setChecked(final List<Problem> found) {
    problems = Optional.of(found.size());
    checkedGame = project.game();
    checkedFiles = project.getFiles();
    if (page.isShowing()) {
      refresh();
    }
  }

  /** Check's result, while the map is as it was checked: a save changes nothing of it. */
  private Optional<Integer> problemsNow() {
    return problems.filter(
        found -> project.game() == checkedGame && project.getFiles() == checkedFiles);
  }

  /** The list as it shows, for tests. */
  List<Item> items() {
    return List.copyOf(items.values());
  }

  private Optional<Path> root() {
    return project.getFolder().map(MapFolder::rootOf);
  }

  /** Lays the page out anew; {@link #refresh} then shows what the map's folder has. */
  private void fill() {
    form.clear();
    cards.clear();
    final Optional<Path> root = root();
    if (root.isEmpty()) {
      form.row(
          Panels.empty(
              Glyph.PUBLISH,
              "Save the map first",
              "What players download is the map's folder: its name, description and preview"
                  + " picture are files in it."));
      form.end();
      return;
    }
    cards.columns();
    showDetails(root.get());
    cards.section("Before you upload");
    cards.row(ready);
    rows.values().forEach(row -> cards.row(row.panel()));
    cards.end();
    form.row(cards);
    form.section(
        "Description",
        "What players read in TripleA's list of maps before they download yours: the setting, the"
            + " time, what is special. HTML, as game notes; saved as description.html at the"
            + " map's top when you leave the text.");
    form.row(descriptionPanes);
    form.row(leftAligned(descriptionLength));
    showUpload(root.get());
    form.end();
    refresh();
  }

  /**
   * Shows what the map's folder has now, in the fields and lines there are: nothing is taken from
   * the page, so a click that made a field write its text still reaches its button.
   */
  private void refresh() {
    final Optional<Path> root = root();
    if (root.isEmpty()) {
      return;
    }
    try {
      final MapDetails details = MapFolder.mapDetails(root.get());
      if (!name.isFocusOwner()) {
        name.setText(details.name());
      }
      if (!version.isFocusOwner()) {
        version.setText(details.version());
      }
    } catch (final IOException e) {
      // the list says so
    }
    if (!description.isFocusOwner() && description.getText().equals(writtenDescription)) {
      // maybe changed outside meanwhile, eg in Files
      readDescription();
    }
    listedHead = listedHead(root.get());
    showDescription();
    items.clear();
    checklist(root.get(), problemsNow()).forEach(item -> items.put(item.id(), item));
    rows.forEach((id, row) -> show(row, Optional.ofNullable(items.get(id))));
    final long done = items.values().stream().filter(Item::done).count();
    ready.setText(
        done == items.size()
            ? "✓ All " + done + " are ready."
            : done + " of " + items.size() + " are ready.");
    cards.revalidate();
    cards.repaint();
  }

  // ---- name and version

  private void showDetails(final Path root) {
    cards.section("Map details");
    if (!Files.isRegularFile(root.resolve(MapDescriptionYaml.MAP_YAML_FILE_NAME))) {
      cards.note(
          "The map has no map.yml yet, the file TripleA lists a map by: its name, its version and"
              + " its games.");
      return;
    }
    cards.field(
        "Map name",
        "The name in TripleA's list of maps to download (map.yml map_name). The games in the map"
            + " have names of their own, changed in Files.",
        name);
    final JButton raise = button("Raise", () -> writeVersion(MapFolder.raised(version.getText())));
    raise.setToolTipText("One higher, for an update you upload");
    cards.field(
        "Version",
        "A simple number, raised with each update you upload (map.yml version). TripleA's guide"
            + " asks for it; today's TripleA tells players that their map is out of date by the"
            + " day of your last upload.",
        leftAligned(version, Box.createHorizontalStrut(Theme.GAP_S), raise));
    inGameFile().ifPresent(cards::note);
  }

  /**
   * What the game file says of a version and of the TripleA it needs, which TripleA 2.6 and later
   * read no more; a save keeps them as they are.
   */
  private Optional<String> inGameFile() {
    try {
      return gameFileLine(MapFolder.readText(project.getXmlFile().orElseThrow()));
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  static Optional<String> gameFileLine(final String xml) {
    final List<String> parts = new ArrayList<>();
    final Matcher infoVersion = INFO_VERSION.matcher(xml);
    if (infoVersion.find()) {
      parts.add("version " + SearchList.escape(infoVersion.group(1)));
    }
    final Matcher minimum = MINIMUM_VERSION.matcher(xml);
    if (minimum.find()) {
      parts.add("for TripleA " + SearchList.escape(minimum.group(1)) + " or later");
    }
    return parts.isEmpty()
        ? Optional.empty()
        : Optional.of(
            "The game file also says: "
                + String.join(", ", parts)
                + ". TripleA reads neither any more; a save keeps them as they are.");
  }

  private void writeName() {
    final Optional<Path> root = root();
    final String typed = name.getText().strip();
    final String before = nameOf(root);
    if (root.isEmpty() || typed.equals(before)) {
      return;
    }
    if (typed.isEmpty() || typed.length() > NAME_MAX_LENGTH) {
      name.setText(before);
      Dialogs.toast(
          page,
          typed.isEmpty()
              ? "! A map needs a name"
              : "! TripleA takes a name of at most " + NAME_MAX_LENGTH + " characters");
      return;
    }
    try {
      MapFolder.setMapName(root.get(), typed);
    } catch (final IOException e) {
      warn("Map name", e);
    }
    refresh();
  }

  private static String nameOf(final Optional<Path> root) {
    try {
      return root.isPresent() ? MapFolder.mapDetails(root.get()).name() : "";
    } catch (final IOException e) {
      return "";
    }
  }

  private void writeVersion(final String typed) {
    final Optional<Path> root = root();
    if (root.isEmpty() || typed.strip().equals(versionOf(root.get()))) {
      return;
    }
    try {
      MapFolder.setMapVersion(root.get(), typed);
      // also when the field has the focus, eg after Raise
      version.setText(typed.strip());
    } catch (final IOException e) {
      warn("Version", e);
    }
    refresh();
  }

  private static String versionOf(final Path root) {
    try {
      return MapFolder.mapDetails(root).version();
    } catch (final IOException e) {
      // a map.yml that does not read: the list says so, a version typed is tried and refused
      return "";
    }
  }

  private void createYaml(final Path root) {
    if (MapDescriptionYaml.generateForMap(root).isEmpty()) {
      Dialogs.warn(
          page,
          "Create map.yml",
          "TripleA could not make one: it needs a game file in a folder named games.");
    }
    fill();
  }

  // ---- the list

  private Row newRow(final Id id) {
    final JLabel text = new JLabel();
    final JButton fix = new JButton();
    Theme.setSecondaryButtonStyle(fix);
    fix.addActionListener(
        e ->
            Optional.ofNullable(items.get(id))
                .ifPresent(item -> root().ifPresent(root -> fix(root, item))));
    final JLabel hint = Theme.newSecondaryLabel("");
    hint.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_XS, Theme.GAP_L, Theme.GAP_S, 0));
    final JPanel line = new JPanel(new BorderLayout(Theme.GAP_S, 0));
    line.setOpaque(false);
    line.add(text, BorderLayout.CENTER);
    line.add(fix, BorderLayout.EAST);
    final JPanel panel = new JPanel(new BorderLayout());
    panel.setOpaque(false);
    panel.add(line, BorderLayout.NORTH);
    panel.add(hint, BorderLayout.CENTER);
    return new Row(panel, text, fix, hint);
  }

  private static void show(final Row row, final Optional<Item> shown) {
    row.panel().setVisible(shown.isPresent());
    if (shown.isEmpty()) {
      return;
    }
    final Item item = shown.get();
    row.text().setText(item.status().mark() + " " + item.text());
    row.text()
        .setForeground(item.done() ? Theme.getSecondaryTextColor() : item.status().color());
    final Optional<String> verb = item.done() ? Optional.empty() : fixVerb(item);
    row.fix().setVisible(verb.isPresent());
    row.fix().setText(verb.orElse(""));
    row.hint().setVisible(!item.done() && !item.hint().isEmpty());
    row.hint()
        .setText(
            "<html><div style='width:"
                + Theme.htmlWidth(HINT_WIDTH)
                + "'>"
                + item.hint()
                + "</div></html>");
  }

  /**
   * What a map to upload needs and whether it has it: what TripleA and its map server really use
   * (map.yml's map_name, description.html and preview.png at the map's top), what its upload guide
   * asks for (a version, the folder's name), and no files of the editor in the upload.
   *
   * @param root the map's own folder, with its map.yml
   * @param problems how many problems Check found, empty when it has not run for the map as it is
   */
  static List<Item> checklist(final Path root, final Optional<Integer> problems) {
    final List<Item> items = new ArrayList<>();
    items.add(
        problems
            .map(
                found ->
                    found == 0
                        ? new Item(Id.CHECK, Status.DONE, "Check found no problems", "")
                        : new Item(
                            Id.CHECK,
                            Status.PROBLEM,
                            "Check found " + found + (found == 1 ? " problem" : " problems"),
                            "Players cannot start a game that does not load."))
            .orElseGet(
                () ->
                    new Item(
                        Id.CHECK,
                        Status.TODO,
                        "Not checked since the last change",
                        "Check loads the game as TripleA does.")));
    try {
      final MapDetails details = MapFolder.mapDetails(root);
      items.add(
          details.name().isEmpty() || details.name().length() > NAME_MAX_LENGTH
              ? new Item(
                  Id.NAME,
                  Status.PROBLEM,
                  details.name().isEmpty() ? "The map has no name" : "The map's name is too long",
                  "The list of maps shows it; at most " + NAME_MAX_LENGTH + " characters.")
              : new Item(Id.NAME, Status.DONE, "Name: " + details.name(), ""));
      items.add(
          details.version().isEmpty()
              ? new Item(
                  Id.VERSION,
                  Status.TODO,
                  "No version yet",
                  "TripleA's guide asks for a number that goes up with each update.")
              : new Item(Id.VERSION, Status.DONE, "Version " + details.version(), ""));
    } catch (final IOException e) {
      items.add(
          new Item(
              Id.MAP_YML,
              Status.PROBLEM,
              "No map.yml that reads",
              "TripleA and its map server find a map by the map.yml at its top: "
                  + SearchList.escape(String.valueOf(e.getMessage()))));
    }
    items.add(descriptionItem(root));
    items.add(
        Files.isRegularFile(root.resolve(MapFolder.PREVIEW_FILE))
            ? new Item(Id.PREVIEW, Status.DONE, "Preview picture (preview.png)", "")
            : new Item(
                Id.PREVIEW,
                Status.TODO,
                "No preview picture yet",
                "The list of maps shows preview.png from the map's top folder above the"
                    + " description."));
    final String folder = root.getFileName().toString();
    final String wanted = folderName(folder);
    items.add(
        folder.equals(wanted)
            ? new Item(Id.FOLDER, Status.DONE, "Folder name: " + folder, "")
            : new Item(
                Id.FOLDER,
                Status.WARNING,
                "Folder name: " + folder,
                "The upload guide asks for lower case with underscores. Close the map, then"
                    + " rename its folder to <b>"
                    + SearchList.escape(wanted)
                    + "</b>; the editor renames no folder itself."));
    items.add(leftoversItem(root));
    return items;
  }

  private static Item descriptionItem(final Path root) {
    final Path file = root.resolve(MapFolder.DESCRIPTION_FILE);
    String text = "";
    try {
      text = Files.isRegularFile(file) ? MapFolder.readNotes(file) : "";
    } catch (final IOException e) {
      // counts as none: writing one replaces it, its first version kept as .bak
    }
    if (text.isBlank()) {
      return new Item(
          Id.DESCRIPTION,
          Status.TODO,
          "No description yet",
          "Players read it in the list of maps before they download: the setting, the time, what"
              + " is special. Without one the list says that none is available.");
    }
    return text.length() > MapFolder.DESCRIPTION_MAX_LENGTH
        ? new Item(
            Id.DESCRIPTION,
            Status.PROBLEM,
            "The description is too long",
            "It has "
                + text.length()
                + " characters; the map server shows none longer than "
                + MapFolder.DESCRIPTION_MAX_LENGTH
                + ".")
        : new Item(Id.DESCRIPTION, Status.DONE, "Description (description.html)", "");
  }

  private static Item leftoversItem(final Path root) {
    int leftovers = 0;
    try {
      leftovers = MapFolder.leftovers(root).size();
    } catch (final IOException e) {
      // a folder that cannot be listed: the export will say so
    }
    return leftovers == 0
        ? new Item(Id.LEFTOVERS, Status.DONE, "No backup files in the folder", "")
        : new Item(
            Id.LEFTOVERS,
            Status.TODO,
            leftovers + (leftovers == 1 ? " backup file" : " backup files") + " in the folder",
            "The editor keeps the first version of each file it changed as .bak. They are no part"
                + " of the map: the zip leaves them out; with Git, do not add them.");
  }

  /** The folder name the upload guide asks for: lower case, underscores between the words. */
  static String folderName(final String name) {
    final String wanted =
        name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    return wanted.isEmpty() ? "my_map" : wanted;
  }

  /** What the line's button says; none for a line fixed in a field beside the list. */
  private static Optional<String> fixVerb(final Item item) {
    return switch (item.id()) {
      case CHECK -> Optional.of(item.status() == Status.TODO ? "Check" : "Show");
      case MAP_YML -> Optional.of("Create or open map.yml");
      case NAME -> Optional.empty();
      case VERSION -> Optional.of("Set version 1");
      case DESCRIPTION -> Optional.of("Write it");
      case PREVIEW -> Optional.of("Make it from the map");
      case FOLDER -> Optional.of("Show folder");
      case LEFTOVERS -> Optional.of("Export as zip without them…");
    };
  }

  /** Does what the line asks for, or opens where it is done. */
  private void fix(final Path root, final Item item) {
    switch (item.id()) {
      case CHECK -> {
        if (item.status() == Status.TODO) {
          check.run();
        } else {
          MapMakerWindow.openTool(page, "Home", null);
        }
      }
      case MAP_YML -> {
        if (Files.exists(root.resolve(MapDescriptionYaml.MAP_YAML_FILE_NAME))) {
          MapMakerWindow.openTool(page, "Files", null);
        } else {
          createYaml(root);
        }
      }
      case NAME -> name.requestFocusInWindow();
      case VERSION -> writeVersion("1");
      case DESCRIPTION -> description.requestFocusInWindow();
      case PREVIEW -> makePreview(root);
      case FOLDER -> showFolder(root);
      case LEFTOVERS -> export.run();
    }
  }

  /** The map as players see it, shrunk, as its preview.png; in the background as it may be big. */
  private void makePreview(final Path root) {
    final MapFiles files = project.getFiles();
    new SwingWorker<Boolean, Void>() {
      @Override
      protected Boolean doInBackground() throws IOException {
        final Optional<BufferedImage> shown = files.shownPicture();
        if (shown.isPresent()) {
          MapFolder.writePreview(root, shown.get());
        }
        return shown.isPresent();
      }

      @Override
      protected void done() {
        try {
          if (!get()) {
            Dialogs.toast(page, "! The map has no picture yet to make the preview from");
          }
        } catch (final Exception e) {
          log.warn("Could not write the preview", e);
          Dialogs.warn(page, "Preview picture", "Could not write it.\n" + e.getMessage());
        }
        refresh();
      }
    }.execute();
  }

  private void showFolder(final Path root) {
    try {
      Desktop.getDesktop().open(root.toFile());
    } catch (final IOException | UnsupportedOperationException e) {
      Dialogs.warn(page, "Show the map's folder", "Could not open " + root + "\n" + e.getMessage());
    }
  }

  // ---- the description

  private void readDescription() {
    final Optional<Path> file =
        root().map(root -> root.resolve(MapFolder.DESCRIPTION_FILE)).filter(Files::isRegularFile);
    String read = "";
    try {
      read = file.isPresent() ? MapFolder.readNotes(file.get()) : "";
    } catch (final IOException e) {
      log.info("Could not read {}", file.get(), e);
    }
    if (!read.equals(writtenDescription) || !read.equals(description.getText())) {
      writtenDescription = read;
      description.setText(read);
      description.setCaretPosition(0);
    }
  }

  /** The preview as the list of maps shows a map: its name, its picture, then the description. */
  private void showDescription() {
    final String typed = description.getText();
    descriptionLength.setText(
        typed.length()
            + " of "
            + MapFolder.DESCRIPTION_MAX_LENGTH
            + " characters"
            + (typed.equals(writtenDescription) ? "" : " · saved when you leave the text"));
    descriptionLength.setForeground(
        typed.length() > MapFolder.DESCRIPTION_MAX_LENGTH
            ? Theme.getDangerColor()
            : Theme.getSecondaryTextColor());
    // the head is read from the folder by refresh, not for every key
    Panels.showKeepingPlace(preview, listedHead + typed);
  }

  /**
   * What TripleA's download window puts above a map's description
   * (DownloadMapsWindowModel.toHtmlString): the map's name and its preview picture.
   */
  static String listedHead(final Path root) {
    final StringBuilder html = new StringBuilder();
    final String name = nameOf(Optional.of(root));
    html.append("<h1>")
        .append(SearchList.escape(name.isEmpty() ? root.getFileName().toString() : name))
        .append("</h1>\n");
    final Path picture = root.resolve(MapFolder.PREVIEW_FILE);
    pictureSize(picture)
        .ifPresent(
            size -> {
              // smaller than the list shows it, so that the text below shows here too
              final int width = Math.min(PREVIEW_WIDTH, size.width);
              html.append("<img src='")
                  .append(picture.toUri())
                  .append("' width='")
                  .append(width)
                  .append("' height='")
                  .append(Math.max(1, size.height * width / size.width))
                  .append("' />\n");
            });
    return html.toString();
  }

  /** The picture's size read from the head of its file; empty for none, or one that is broken. */
  private static Optional<Dimension> pictureSize(final Path picture) {
    if (!Files.isRegularFile(picture)) {
      return Optional.empty();
    }
    try (ImageInputStream in = ImageIO.createImageInputStream(picture.toFile())) {
      final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        return Optional.empty();
      }
      final ImageReader reader = readers.next();
      try {
        reader.setInput(in);
        return Optional.of(new Dimension(reader.getWidth(0), reader.getHeight(0)))
            .filter(size -> size.width > 0 && size.height > 0);
      } finally {
        reader.dispose();
      }
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Writes the description typed as the map's description.html, a blank one removes the file; the
   * first version stays as ".bak". Typed text that could not be written stays in the field.
   */
  private void writeDescription() {
    final String typed = description.getText();
    final Optional<Path> root = root();
    if (root.isEmpty() || typed.equals(writtenDescription)) {
      return;
    }
    final Path file = root.get().resolve(MapFolder.DESCRIPTION_FILE);
    try {
      if (typed.isBlank()) {
        MapFolder.remove(file);
      } else {
        MapFolder.replaceText(file, typed);
      }
      writtenDescription = typed;
      refresh();
    } catch (final IOException e) {
      warn("Description", e);
    }
  }

  // ---- the way to the players

  private void showUpload(final Path root) {
    form.section(
        "Upload",
        "TripleA's maps are kept at GitHub. The editor does not upload: these are the steps of"
            + " TripleA's guide, which shows each click.");
    step(
        1,
        "Sign up at GitHub, where TripleA's maps are kept.",
        "github.com",
        "https://github.com/");
    step(
        2,
        "Create a repository there, named like the map's folder: <b>"
            + SearchList.escape(folderName(root.getFileName().toString()))
            + "</b>.",
        "New repository",
        "https://github.com/new");
    step(
        3,
        "Install Git, the program that uploads the folder.",
        "git-scm.com",
        "https://git-scm.com/downloads");
    step(
        4,
        "Upload the map's folder with Git GUI, without the .bak files; the guide shows how.",
        "The guide",
        GUIDE + "#5-use-git-gui-to-upload-your-map");
    step(
        5,
        "Ask for your map to be added to TripleA's maps: a new issue, <i>Map Registration</i>.",
        "New issue",
        "https://github.com/triplea-game/triplea/issues/new/choose");
    step(
        6,
        "Later updates go into your map's repository at triplea-maps; players are then told that"
            + " their map is out of date.",
        "triplea-maps",
        "https://github.com/triplea-maps");
  }

  private void step(final int number, final String text, final String link, final String url) {
    final JButton open = new JButton(link + " ›");
    Theme.setTertiaryButtonStyle(open);
    open.setToolTipText("Opens " + url + " in your browser");
    open.getAccessibleContext().setAccessibleName("Open " + link + " in the browser");
    open.addActionListener(e -> OpenFileUtility.openUrl(page, url));
    // the link right after its step, not across the page from it
    form.row(
        leftAligned(
            new JLabel("<html>" + number + ". " + text + "</html>"),
            Box.createHorizontalStrut(Theme.GAP_S),
            open));
  }

  private static JButton button(final String text, final Runnable action) {
    final JButton button = new JButton(text);
    Theme.setSecondaryButtonStyle(button);
    button.addActionListener(e -> action.run());
    return button;
  }

  private static JPanel leftAligned(final Component... parts) {
    final JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    line.setOpaque(false);
    for (final Component part : parts) {
      line.add(part);
    }
    return line;
  }

  private void warn(final String title, final IOException e) {
    log.warn("Could not write: {}", title, e);
    Dialogs.warn(page, title, "Could not write it.\n" + e.getMessage());
  }
}
