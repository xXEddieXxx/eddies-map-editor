package eddies.mapeditor;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.extern.slf4j.Slf4j;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * The text of a file, a note or a description, colored by what it is: XML, HTML, properties or
 * plain. Lines are numbered in {@link #scrolled}, XML and HTML fold, brackets pair, Ctrl+F finds.
 * The text size (Ctrl+wheel) and the colors (right-click: the app's light or dark ones, or a theme
 * file of the map maker's own) are kept for every text.
 */
@Slf4j
final class CodeArea extends RSyntaxTextArea {
  private static final long serialVersionUID = 1L;
  static final String TEXT_SIZE = "textSize";
  static final String THEME_FILE = "textThemeFile";
  private static final int LEAST_SIZE = 8;
  private static final int MOST_SIZE = 40;
  private static final String THEMES = "/org/fife/ui/rsyntaxtextarea/themes/";
  // every text shown, so a new size or theme reaches all of them at once
  private static final Set<CodeArea> ALL = Collections.newSetFromMap(new WeakHashMap<>());

  private final transient Preferences preferences;
  private boolean made;

  CodeArea() {
    this(Preferences.userNodeForPackage(CodeArea.class));
  }

  CodeArea(final Preferences preferences) {
    this.preferences = preferences;
    made = true;
    ALL.add(this);
    setTabSize(4);
    setTabsEmulated(false);
    setAutoIndentEnabled(true);
    setBracketMatchingEnabled(true);
    setCodeFoldingEnabled(true);
    setMarkOccurrences(false);
    setAnimateBracketMatching(false);
    setClearWhitespaceLinesEnabled(false);
    setLineWrap(false);
    setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
    addMouseWheelListener(
        e -> {
          if (e.isControlDown()) {
            setTextSize(textSize() - e.getWheelRotation());
          } else {
            getParent().dispatchEvent(e);
          }
        });
    getInputMap()
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "showFind");
    getActionMap()
        .put(
            "showFind",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                FindBar.show(CodeArea.this);
              }
            });
    applyTheme();
  }

  /** The text area in a scroll pane, with the lines numbered and XML or HTML folding. */
  static JScrollPane scrolled(final CodeArea text, final boolean lineNumbers) {
    final RTextScrollPane scroll = new RTextScrollPane(text, lineNumbers);
    scroll.setFoldIndicatorEnabled(lineNumbers);
    scroll.setIconRowHeaderEnabled(false);
    // the theme styles the gutter too, once there is one
    text.applyTheme();
    return scroll;
  }

  void colorAsXml() {
    setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
  }

  void colorAsHtml() {
    setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_HTML);
  }

  void colorAsPlain() {
    setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
  }

  /** Colors the text as the file's name says: XML, HTML, properties or YAML, else plain. */
  void colorAs(final String fileName) {
    final String name = fileName.toLowerCase(Locale.ROOT);
    setSyntaxEditingStyle(
        name.endsWith(".xml")
            ? SyntaxConstants.SYNTAX_STYLE_XML
            : name.matches(".*\\.html?")
                ? SyntaxConstants.SYNTAX_STYLE_HTML
                : name.endsWith(".properties")
                    ? SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
                    : name.matches(".*\\.ya?ml")
                        ? SyntaxConstants.SYNTAX_STYLE_YAML
                        : SyntaxConstants.SYNTAX_STYLE_NONE);
  }

  /** Whether the text is colored, eg as XML; plain text is not. */
  boolean isColored() {
    return !SyntaxConstants.SYNTAX_STYLE_NONE.equals(getSyntaxEditingStyle());
  }

  // ---- size and theme, for every text

  /** The size of the text, as chosen; by default a point above the app's. */
  int textSize() {
    return preferences.getInt(TEXT_SIZE, defaultSize());
  }

  /** Sets the text size of every text, 8 to 40, and keeps it. */
  void setTextSize(final int size) {
    final int clamped = Math.clamp(size, LEAST_SIZE, MOST_SIZE);
    if (clamped == defaultSize()) {
      preferences.remove(TEXT_SIZE);
    } else {
      preferences.putInt(TEXT_SIZE, clamped);
    }
    ALL.forEach(CodeArea::applyTheme);
  }

  private static int defaultSize() {
    return Optional.ofNullable(UIManager.getFont("TextArea.font")).map(Font::getSize).orElse(12)
        + 1;
  }

  /** The theme file chosen, if any; its colors in place of the app's. */
  Optional<Path> themeFile() {
    return Optional.ofNullable(preferences.get(THEME_FILE, null)).map(Path::of);
  }

  /** Colors every text by the theme file (RSyntaxTextArea's XML), or by the app again for none. */
  void setThemeFile(final Optional<Path> file) {
    file.ifPresentOrElse(
        f -> preferences.put(THEME_FILE, f.toString()), () -> preferences.remove(THEME_FILE));
    ALL.forEach(CodeArea::applyTheme);
  }

  // a theme change makes a new UI, which forgets the colors
  @Override
  public void updateUI() {
    super.updateUI();
    if (made) {
      applyTheme();
    }
  }

  private void applyTheme() {
    final Optional<Path> own = themeFile().filter(Files::isRegularFile);
    final Optional<Theme> theme = own.map(this::loadTheme).orElseGet(this::appTheme);
    theme.ifPresent(t -> t.apply(this));
    if (own.isEmpty()) {
      // the app's colors over the built-in theme, so the text sits in the app's panels
      setBackground(UIManager.getColor("TextArea.background"));
      setForeground(UIManager.getColor("TextArea.foreground"));
      setCaretColor(UIManager.getColor("TextArea.caretForeground"));
      setSelectionColor(UIManager.getColor("TextArea.selectionBackground"));
      setCurrentLineHighlightColor(blend(getBackground(), getForeground(), 0.06f));
      final Gutter gutter = gutter();
      if (gutter != null) {
        gutter.setBackground(getBackground());
        gutter.setLineNumberColor(eddies.mapeditor.Theme.getSecondaryTextColor());
        gutter.setBorderColor(eddies.mapeditor.Theme.getSeparatorColor());
        gutter.setFoldIndicatorForeground(eddies.mapeditor.Theme.getSecondaryTextColor());
        gutter.setFoldBackground(getBackground());
      }
    }
    setFont(monospaced(textSize()));
    final Gutter gutter = gutter();
    if (gutter != null) {
      gutter.setLineNumberFont(getFont());
    }
  }

  private Gutter gutter() {
    return getParent() != null && getParent().getParent() instanceof RTextScrollPane scroll
        ? scroll.getGutter()
        : null;
  }

  /** RSyntaxTextArea's light or dark theme, as the app is. */
  private Optional<Theme> appTheme() {
    final String name = FlatLaf.isLafDark() ? "dark.xml" : "default.xml";
    try (InputStream in = CodeArea.class.getResourceAsStream(THEMES + name)) {
      return in == null ? Optional.empty() : Optional.of(Theme.load(in));
    } catch (final IOException e) {
      log.warn("Could not read the text theme {}", name, e);
      return Optional.empty();
    }
  }

  private Optional<Theme> loadTheme(final Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return Optional.of(Theme.load(in));
    } catch (final IOException | RuntimeException e) {
      log.warn("Could not read the text theme {}", file, e);
      return appTheme();
    }
  }

  /** Cascadia Mono, Consolas or Menlo: Courier, Java's monospaced font on Windows, is thin. */
  static Font monospaced(final int size) {
    return Stream.of("Cascadia Mono", "Consolas", "Menlo")
        .map(name -> new Font(name, Font.PLAIN, size))
        // a font that is not installed comes back as Dialog
        .filter(font -> font.getFamily(Locale.ROOT).equals(font.getName()))
        .findFirst()
        .orElseGet(() -> new Font(Font.MONOSPACED, Font.PLAIN, size));
  }

  private static Color blend(final Color a, final Color b, final float ofB) {
    return new Color(
        Math.round(a.getRed() + (b.getRed() - a.getRed()) * ofB),
        Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * ofB),
        Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * ofB));
  }

  // ---- the right-click menu: cut, copy, paste and undo, then the size and the colors

  @Override
  protected JPopupMenu createPopupMenu() {
    final JPopupMenu menu = super.createPopupMenu();
    menu.addSeparator();
    menu.add(item("Bigger text", () -> setTextSize(textSize() + 1)))
        .setToolTipText("Ctrl and the mouse wheel do the same");
    menu.add(item("Smaller text", () -> setTextSize(textSize() - 1)));
    menu.add(item("Text size as before", () -> setTextSize(defaultSize())));
    menu.addSeparator();
    menu.add(item("Colors from a theme file…", this::chooseThemeFile))
        .setToolTipText(
            "An RSyntaxTextArea theme, an XML file: the colors of the text, its selection and"
                + " the line numbers");
    final JMenuItem asApp = item("Colors as the app", () -> setThemeFile(Optional.empty()));
    asApp.setEnabled(themeFile().isPresent());
    menu.add(asApp);
    return menu;
  }

  private static JMenuItem item(final String text, final Runnable action) {
    final JMenuItem item = new JMenuItem(text);
    item.addActionListener(e -> action.run());
    return item;
  }

  private void chooseThemeFile() {
    final JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Colors from a theme file");
    chooser.setFileFilter(new FileNameExtensionFilter("Theme (XML)", "xml"));
    themeFile().map(Path::toFile).ifPresent(chooser::setSelectedFile);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      setThemeFile(Optional.of(chooser.getSelectedFile().toPath()));
    }
  }

  /** The key the find bar closes with, for the bar's own field. */
  static final KeyStroke ESCAPE = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

  /** The scroll pane above the text, if it is in one. */
  Optional<JScrollPane> scrollPane() {
    return getParent() != null && getParent().getParent() instanceof JScrollPane scroll
        ? Optional.of(scroll)
        : Optional.empty();
  }

  /** The find bar's place: over the text, as the scroll pane's column header. */
  void showAbove(final JComponent bar) {
    scrollPane()
        .ifPresent(
            scroll -> {
              scroll.setColumnHeaderView(bar);
              scroll.revalidate();
            });
  }

  void hideAbove() {
    scrollPane()
        .ifPresent(
            scroll -> {
              scroll.setColumnHeader(null);
              scroll.revalidate();
              requestFocusInWindow();
            });
  }
}
