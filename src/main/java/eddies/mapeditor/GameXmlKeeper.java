package eddies.mapeditor;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.triplea.generic.xml.reader.XmlMapper;
import org.triplea.generic.xml.reader.exceptions.XmlParsingException;
import org.triplea.map.data.elements.Game;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

/**
 * Writes a game XML as TripleA's model has it, keeping what the file it replaces has beyond the
 * model: the DOCTYPE, comments, and elements and attributes the model drops (eg info's version,
 * loader, triplea's minimumVersion), in the file's order. What the model drops shows as the
 * difference between the old file and the old file read and written by the model; what the editor
 * changed, as the difference between that and the game now.
 */
final class GameXmlKeeper {
  // the attributes that tell elements of one name apart, eg territories or attachments
  private static final List<String> KEYS =
      Stream.of(
              "name",
              "attachTo",
              "territory",
              "t1",
              "t2",
              "player",
              "player1",
              "player2",
              "unitType",
              "resource",
              "resourceOrUnit")
          .map(GameXmlKeeper::canonical)
          .toList();

  private GameXmlKeeper() {}

  /**
   * Writes the game to a file, with what the original has that TripleA's model does not hold; an
   * original that is missing or no XML keeps nothing.
   */
  static void export(final Game game, final Path original, final Path to) throws IOException {
    final Document written = dom(game);
    Optional<DocumentType> doctype = Optional.empty();
    if (Files.exists(original)) {
      try {
        final Document old = builder().parse(original.toFile());
        final Game read;
        try (InputStream in = Files.newInputStream(original)) {
          read = new XmlMapper(in).mapXmlToObject(Game.class);
        }
        unnest(old.getDocumentElement());
        keep(old, dom(read), written);
        doctype = Optional.ofNullable(old.getDoctype());
      } catch (final SAXException | XmlParsingException e) {
        // a file TripleA cannot read has nothing the model dropped; its first version is the .bak
      }
    }
    write(written, doctype, to);
  }

  /**
   * Brings elements inside one of their own name, eg the steps after a step that was not closed,
   * out behind it, but the first: TripleA's XmlParser ends the outer one at the first one's end,
   * which it skips, and reads the others one after the other. Kept inside, each would be written
   * twice; the first stays where it is, so the saved file reads as before.
   */
  // shortcut: a skipped first one's own children, which TripleA reads as the outer one's, are
  // written there again with each save; handle when a map has such a step
  private static void unnest(final Element element) {
    // the children's first: they come out beside them, then on from here
    elements(element).forEach(GameXmlKeeper::unnest);
    final Node behind = element.getNextSibling();
    elements(element).stream()
        .filter(child -> child.getTagName().equals(element.getTagName()))
        .skip(1)
        .forEach(child -> element.getParentNode().insertBefore(child, behind));
  }

  private static Document dom(final Game game) throws IOException {
    try {
      final Document document = builder().newDocument();
      Model.CONTEXT.createMarshaller().marshal(game, document);
      return document;
    } catch (final JAXBException e) {
      throw new IOException("Could not write the game: " + e.getMessage(), e);
    }
  }

  /** TripleA's model to XML, made once: making it takes long. */
  private static final class Model {
    private static final JAXBContext CONTEXT;

    static {
      try {
        CONTEXT = JAXBContext.newInstance(Game.class);
      } catch (final JAXBException e) {
        throw new IllegalStateException("TripleA's game model cannot be written", e);
      }
    }
  }

  private static DocumentBuilder builder() throws IOException {
    try {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // game.dtd is named in many maps but is nowhere beside them
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      return factory.newDocumentBuilder();
    } catch (final ParserConfigurationException e) {
      throw new IOException(e);
    }
  }

  private static void keep(final Document old, final Document model, final Document written) {
    final Element root = written.getDocumentElement();
    // comments and processing instructions before the game go before it, the others after it
    @Nullable Node before = root;
    for (Node node = old.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node == old.getDocumentElement()) {
        before = null;
        if (canonical(root.getTagName()).equals(canonical(node.getNodeName()))) {
          keep((Element) node, model.getDocumentElement(), root);
        }
      } else if (node.getNodeType() != Node.DOCUMENT_TYPE_NODE) {
        written.insertBefore(copy(node, written), before);
      }
    }
  }

  /**
   * Keeps in the written element what the old one has and the model's does not. The model's
   * elements stay in the model's order, as the editor may have moved them; what only the old file
   * has (comments, elements the model drops, the comments of removed elements) goes before the
   * element it was before, or at the end.
   */
  private static void keep(final Element old, final Element model, final Element written) {
    final Map<String, String> modelAttributes = attributes(model);
    final Map<String, String> writtenAttributes = attributes(written);
    final NamedNodeMap attributes = old.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      final Attr attribute = (Attr) attributes.item(i);
      final String name = canonical(attribute.getName());
      if (!modelAttributes.containsKey(name) && !writtenAttributes.containsKey(name)) {
        written.setAttribute(attribute.getName(), attribute.getValue());
      }
    }
    final Document to = written.getOwnerDocument();
    final List<Element> writtenChildren = elements(written);
    final Map<Element, Element> inModel = new IdentityHashMap<>();
    final Map<Element, Element> inWritten = new IdentityHashMap<>();
    match(old, model, written, inModel, inWritten);
    final Map<Element, List<Node>> before = new IdentityHashMap<>();
    List<Node> onlyOld = new ArrayList<>();
    for (Node node = old.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof final Element child) {
        if (!inModel.containsKey(child)) {
          onlyOld.add(copy(child, to));
        } else if (inWritten.containsKey(child)) {
          keep(child, inModel.get(child), inWritten.get(child));
          before.put(inWritten.get(child), onlyOld);
          onlyOld = new ArrayList<>();
        } else {
          onlyOld.add(commentsOf(child, to));
        }
      } else if (node.getNodeType() == Node.COMMENT_NODE
          || node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
          || (isText(node) && nodes(model).stream().noneMatch(GameXmlKeeper::isText))) {
        // text only where the model has none, eg a stray "/>" between elements
        onlyOld.add(copy(node, to));
      }
    }
    writtenChildren.forEach(written::removeChild);
    for (final Element child : writtenChildren) {
      before.getOrDefault(child, List.of()).forEach(written::appendChild);
      written.appendChild(child);
    }
    onlyOld.forEach(written::appendChild);
  }

  /**
   * Which old child the model has (none: the model drops it) and which the editor's game has (none:
   * removed); a removed one and a new one whose keys differ in one attribute only are one renamed,
   * eg a territory.
   */
  private static void match(
      final Element old,
      final Element model,
      final Element written,
      final Map<Element, Element> inModel,
      final Map<Element, Element> inWritten) {
    final Map<String, Deque<Element>> models = byKey(model);
    final Map<String, Deque<Element>> writtens = byKey(written);
    final List<Element> removed = new ArrayList<>();
    for (final Element child : elements(old)) {
      poll(models, key(child))
          .ifPresent(
              element -> {
                inModel.put(child, element);
                poll(writtens, key(child))
                    .ifPresentOrElse(match -> inWritten.put(child, match), () -> removed.add(child));
              });
    }
    final List<Element> added = writtens.values().stream().flatMap(Deque::stream).toList();
    final Set<Element> taken = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final Element child : removed) {
      final List<Element> renamed =
          added.stream()
              .filter(element -> !taken.contains(element))
              .filter(element -> isRenamed(child, element))
              .toList();
      if (renamed.size() == 1) {
        taken.add(renamed.getFirst());
        inWritten.put(child, renamed.getFirst());
      }
    }
  }

  private static boolean isRenamed(final Element old, final Element written) {
    if (!canonical(old.getTagName()).equals(canonical(written.getTagName()))) {
      return false;
    }
    final Map<String, String> was = keyAttributes(old);
    final Map<String, String> is = keyAttributes(written);
    return !was.isEmpty()
        && was.keySet().equals(is.keySet())
        && was.keySet().stream().filter(name -> !was.get(name).equals(is.get(name))).count() == 1;
  }

  private static boolean isText(final Node node) {
    return node instanceof Text && !node.getTextContent().isBlank();
  }

  /** The comments in an element the editor removed, so they stay where it was. */
  private static Node commentsOf(final Element removed, final Document to) {
    final Node comments = to.createDocumentFragment();
    final NodeList all = removed.getElementsByTagName("*");
    IntStream.range(-1, all.getLength())
        .mapToObj(i -> i < 0 ? removed : all.item(i))
        .flatMap(element -> nodes(element).stream())
        .filter(node -> node.getNodeType() == Node.COMMENT_NODE)
        .forEach(comment -> comments.appendChild(to.importNode(comment, false)));
    return comments;
  }

  private static Map<String, Deque<Element>> byKey(final Element parent) {
    final Map<String, Deque<Element>> byKey = new HashMap<>();
    elements(parent)
        .forEach(child -> byKey.computeIfAbsent(key(child), any -> new ArrayDeque<>()).add(child));
    return byKey;
  }

  private static Optional<Element> poll(final Map<String, Deque<Element>> byKey, final String key) {
    return Optional.ofNullable(byKey.get(key)).map(Deque::poll);
  }

  private static String key(final Element element) {
    final StringBuilder key = new StringBuilder(canonical(element.getTagName()));
    keyAttributes(element)
        .forEach((name, value) -> key.append(' ').append(name).append('=').append(value));
    return key.toString();
  }

  /** The attributes that tell an element apart, in a fixed order. */
  private static Map<String, String> keyAttributes(final Element element) {
    final Map<String, String> attributes = attributes(element);
    final Map<String, String> key = new LinkedHashMap<>();
    for (final String name : KEYS) {
      // TripleA reads an empty one as none, and writes none
      Optional.ofNullable(attributes.get(name))
          .filter(value -> !value.isEmpty())
          .ifPresent(value -> key.put(name, value));
    }
    return key;
  }

  private static Map<String, String> attributes(final Element element) {
    final Map<String, String> byName = new HashMap<>();
    final NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      byName.put(canonical(attributes.item(i).getNodeName()), attributes.item(i).getNodeValue());
    }
    return byName;
  }

  /** A name as TripleA reads it: tags in any case, "attatch" for "attach" in old maps. */
  private static String canonical(final String name) {
    return name.toLowerCase(Locale.ROOT).replace("attatch", "attach");
  }

  private static List<Element> elements(final Element parent) {
    return nodes(parent).stream()
        .filter(Element.class::isInstance)
        .map(Element.class::cast)
        .toList();
  }

  private static List<Node> nodes(final Node parent) {
    final List<Node> nodes = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      nodes.add(node);
    }
    return nodes;
  }

  /** A node of the old file for the written one, without the old file's line breaks and indent. */
  private static Node copy(final Node node, final Document to) {
    final Node copy = to.importNode(node, true);
    dropIndent(copy);
    return copy;
  }

  private static void dropIndent(final Node node) {
    for (final Node child : nodes(node)) {
      if (child.getNodeType() == Node.TEXT_NODE
          && child.getTextContent().isBlank()
          && node.getChildNodes().getLength() > 1) {
        node.removeChild(child);
      } else {
        dropIndent(child);
      }
    }
  }

  private static void write(
      final Document document, final Optional<DocumentType> doctype, final Path to)
      throws IOException {
    try {
      final Transformer transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      doctype
          .map(DocumentType::getSystemId)
          .ifPresent(id -> transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, id));
      doctype
          .map(DocumentType::getPublicId)
          .ifPresent(id -> transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, id));
      // no standalone="no" in the declaration
      document.setXmlStandalone(true);
      transformer.transform(new DOMSource(document), new StreamResult(to.toFile()));
    } catch (final TransformerException e) {
      throw new IOException("Could not write the game: " + e.getMessage(), e);
    }
  }
}
