package eddies.mapeditor;

import com.formdev.flatlaf.ui.FlatTextAreaUI;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.swing.AbstractAction;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.WrappedPlainView;

/**
 * A text area that can color XML: tags, attribute names, values and comments each in their own
 * color, so a map maker sees the structure while typing.
 */
final class XmlTextArea extends JTextArea {
  @Serial private static final long serialVersionUID = 1L;

  // where the reading of the XML is at a character
  private static final int TEXT = 0;
  private static final int NAME = 1;
  private static final int ATTRIBUTES = 2;
  private static final int DOUBLE_QUOTED = 3;
  private static final int SINGLE_QUOTED = 4;
  private static final int COMMENT = 5;

  /**
   * What a character is; each but text has its color in {@link Theme#getXmlColors}, in this order.
   */
  enum Kind {
    TEXT,
    TAG,
    ATTRIBUTE,
    VALUE,
    COMMENT
  }

  private boolean xml;

  XmlTextArea() {
    // Courier, Java's monospaced font on Windows, is thin and hard to read
    final int size = getFont().getSize() + 1;
    setFont(
        Stream.of("Cascadia Mono", "Consolas", "Menlo")
            .map(name -> new Font(name, Font.PLAIN, size))
            // a font that is not installed comes back as Dialog
            .filter(font -> font.getFamily(Locale.ROOT).equals(font.getName()))
            .findFirst()
            .orElseGet(() -> new Font(Font.MONOSPACED, Font.PLAIN, size)));
    setTabSize(4);
    // a new line starts as indented as the one it breaks, as in editors for code
    getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "newIndentedLine");
    getActionMap()
        .put(
            "newIndentedLine",
            new AbstractAction() {
              @Serial private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final ActionEvent e) {
                // replaceSelection does not ask, unlike the editor kit's own actions
                if (!isEditable() || !isEnabled()) {
                  UIManager.getLookAndFeel().provideErrorFeedback(XmlTextArea.this);
                  return;
                }
                replaceSelection("\n" + indentationAt(getText(), getSelectionStart()));
              }
            });
  }

  /** The spaces and tabs that start the line the position is in. */
  static String indentationAt(final String text, final int position) {
    final int start = text.lastIndexOf('\n', position - 1) + 1;
    int end = start;
    while (end < Math.min(position, text.length())
        && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
      end++;
    }
    return text.substring(start, end);
  }

  void colorAsXml() {
    xml = true;
    repaint();
  }

  void colorAsPlain() {
    xml = false;
    repaint();
  }

  // a theme change makes a new UI, which must make the coloring view again
  @Override
  public void updateUI() {
    setUI(
        new FlatTextAreaUI() {
          @Override
          public View create(final Element element) {
            // right-to-left text needs Swing's own views
            if (Boolean.TRUE.equals(element.getDocument().getProperty("i18n"))) {
              return super.create(element);
            }
            return getLineWrap() ? new XmlWrappedView(element) : new XmlView(element);
          }
        });
  }

  /** The state after the character at {@code i}, read in the state before it. */
  static int next(final int state, final String s, final int i) {
    final char c = s.charAt(i);
    return switch (state) {
      // a "<" before a space is text, eg "a < b" in a note
      case TEXT ->
          c != '<' || i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1))
              ? TEXT
              : s.startsWith("<!--", i) ? COMMENT : NAME;
      case NAME -> c == '>' ? TEXT : Character.isWhitespace(c) ? ATTRIBUTES : NAME;
      case ATTRIBUTES ->
          switch (c) {
            case '"' -> DOUBLE_QUOTED;
            case '\'' -> SINGLE_QUOTED;
            case '>' -> TEXT;
            default -> ATTRIBUTES;
          };
      case DOUBLE_QUOTED -> c == '"' ? ATTRIBUTES : DOUBLE_QUOTED;
      case SINGLE_QUOTED -> c == '\'' ? ATTRIBUTES : SINGLE_QUOTED;
      default -> c == '>' && i >= 2 && s.startsWith("--", i - 2) ? TEXT : COMMENT;
    };
  }

  /** What a character is, from the states before and after it. */
  static Kind kind(final int before, final int after, final char c) {
    if (before == COMMENT || after == COMMENT) {
      return Kind.COMMENT;
    }
    if (before == DOUBLE_QUOTED
        || before == SINGLE_QUOTED
        || after == DOUBLE_QUOTED
        || after == SINGLE_QUOTED) {
      return Kind.VALUE;
    }
    if (before == TEXT && after == TEXT) {
      return Kind.TEXT;
    }
    if (before == ATTRIBUTES && after == ATTRIBUTES) {
      return c == '/' || c == '?' ? Kind.TAG : Kind.ATTRIBUTE;
    }
    return Kind.TAG;
  }

  /** Draws each line in the colors of its XML. */
  private final class XmlView extends PlainView {
    private final Coloring coloring = new Coloring(this);

    XmlView(final Element element) {
      super(element);
    }

    @Override
    protected float drawUnselectedText(
        final Graphics2D g, final float x, final float y, final int p0, final int p1)
        throws BadLocationException {
      return xml ? coloring.draw(g, x, y, p0, p1) : super.drawUnselectedText(g, x, y, p0, p1);
    }

    // a change can recolor the lines below it, eg an opened comment
    @Override
    public void insertUpdate(final DocumentEvent e, final Shape a, final ViewFactory f) {
      super.insertUpdate(e, a, f);
      coloring.changed();
    }

    @Override
    public void removeUpdate(final DocumentEvent e, final Shape a, final ViewFactory f) {
      super.removeUpdate(e, a, f);
      coloring.changed();
    }
  }

  /** The same, with long lines wrapped, eg the game notes. */
  private final class XmlWrappedView extends WrappedPlainView {
    private final Coloring coloring = new Coloring(this);

    XmlWrappedView(final Element element) {
      super(element, getWrapStyleWord());
    }

    @Override
    protected float drawUnselectedText(
        final Graphics2D g, final float x, final float y, final int p0, final int p1)
        throws BadLocationException {
      return xml ? coloring.draw(g, x, y, p0, p1) : super.drawUnselectedText(g, x, y, p0, p1);
    }

    @Override
    public void insertUpdate(final DocumentEvent e, final Shape a, final ViewFactory f) {
      super.insertUpdate(e, a, f);
      coloring.changed();
    }

    @Override
    public void removeUpdate(final DocumentEvent e, final Shape a, final ViewFactory f) {
      super.removeUpdate(e, a, f);
      coloring.changed();
    }
  }

  /** Draws a part of a line in its colors, from the state each line starts in. */
  private final class Coloring {
    private final View view;
    private final Segment segment = new Segment();
    // the text and the state at each line's start, read again after each change
    private String text = "";
    private int[] lineStates = new int[0];
    private boolean read;
    private List<Color> colors = List.of();

    Coloring(final View view) {
      this.view = view;
    }

    float draw(final Graphics2D g, final float x, final float y, final int p0, final int p1)
        throws BadLocationException {
      readStates();
      final Element root = view.getElement();
      final int line = root.getElementIndex(p0);
      int state = lineStates[Math.min(line, lineStates.length - 1)];
      for (int i = root.getElement(line).getStartOffset(); i < p0; i++) {
        state = next(state, text, i);
      }
      float at = x;
      int start = p0;
      Kind startKind = Kind.TEXT;
      for (int i = p0; i < p1; i++) {
        final int after = next(state, text, i);
        final Kind kind = kind(state, after, text.charAt(i));
        state = after;
        if (kind != startKind && i > start) {
          at = drawRun(g, at, y, start, i, color(startKind, colors));
          start = i;
        }
        startKind = kind;
      }
      return p1 > start ? drawRun(g, at, y, start, p1, color(startKind, colors)) : at;
    }

    private Color color(final Kind kind, final List<Color> colors) {
      return kind == Kind.TEXT ? getForeground() : colors.get(kind.ordinal() - 1);
    }

    private float drawRun(
        final Graphics2D g,
        final float x,
        final float y,
        final int p0,
        final int p1,
        final Color color)
        throws BadLocationException {
      view.getDocument().getText(p0, p1 - p0, segment);
      g.setColor(color);
      return Utilities.drawTabbedText(segment, x, y, g, (TabExpander) view, p0);
    }

    private void readStates() throws BadLocationException {
      if (read) {
        return;
      }
      text = view.getDocument().getText(0, view.getDocument().getLength());
      final int[] states = new int[view.getElement().getElementCount()];
      int line = 0;
      int state = TEXT;
      for (int i = 0; i < text.length(); i++) {
        state = next(state, text, i);
        if (text.charAt(i) == '\n' && line + 1 < states.length) {
          states[++line] = state;
        }
      }
      lineStates = states;
      colors = Theme.getXmlColors();
      read = true;
    }

    void changed() {
      read = false;
      if (xml) {
        repaint();
      }
    }
  }
}
