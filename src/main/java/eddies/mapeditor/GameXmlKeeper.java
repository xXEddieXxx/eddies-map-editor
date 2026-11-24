package eddies.mapeditor;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Writes a game XML as TripleA's model has it, keeping what the file it replaces has beyond the
 * model: the DOCTYPE, comments, and elements and attributes the model drops (eg info's version,
 * loader, triplea's minimumVersion), in the file's order. What the model drops shows as the
 * difference between the old file and the old file read and written by the model; what the editor
 * changed, as the difference between that and the game now.
 */
final class GameXmlKeeper {
  // asked for every attribute of the three trees, of few names
  private static final Map<String, String> CANONICAL = new ConcurrentHashMap<>();
  private static final Map<String, Integer> KEY_INDEX = new ConcurrentHashMap<>();

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

  /** A game file's bytes and their model, read ahead for the next save over that file. */
  private record ReadAhead(byte[] bytes, Future<Optional<Game>> model) {}

  // reading a big map's model again takes long, eg 0.3 s of a save on 270BC Wars; soft, as it
  // holds the file and a model (60 MB there) that a lack of memory may take: then it is read again
  private static volatile SoftReference<ReadAhead> readAhead = new SoftReference<>(null);

  private GameXmlKeeper() {}

  /** The model read from a game file's bytes, eg on open, so a save over it needs no read. */
  static void remember(final byte[] bytes, final Game model) {
    readAhead =
        new SoftReference<>(
            new ReadAhead(bytes, CompletableFuture.completedFuture(Optional.of(model))));
  }

  /**
   * Writes the game to a file, with what the original has that TripleA's model does not hold; an
   * original that is missing or no XML keeps nothing.
   */
  static void export(final Game game, final Path original, final Path to) throws IOException {
    final Future<Document> written = inBackground(() -> dom(game));
    Optional<DocumentType> doctype = Optional.empty();
    if (Files.exists(original)) {
      final byte[] bytes = Files.readAllBytes(original);
      final Future<Optional<Document>> model = inBackground(() -> modelDom(bytes));
      final Optional<Document> old = parsed(bytes, original);
      final Optional<Document> read = result(model);
      // a file TripleA cannot read has nothing the model dropped; its first version is the .bak
      if (old.isPresent() && read.isPresent()) {
        keep(old.get(), read.get(), result(written));
        doctype = Optional.ofNullable(old.get().getDoctype());
      }
    }
    write(result(written), doctype, to);
    final byte[] saved = Files.readAllBytes(to);
    readAhead = new SoftReference<>(new ReadAhead(saved, inBackground(() -> read(saved))));
  }

  /** The old file with its nested elements brought out, none for no XML. */
  private static Optional<Document> parsed(final byte[] bytes, final Path file)
      throws IOException {
    try {
      final InputSource source = new InputSource(new ByteArrayInputStream(bytes));
      // entities named relative to the file are found beside it
      source.setSystemId(file.toUri().toString());
      final Document old = builder().parse(source);
      unnest(old.getDocumentElement());
      return Optional.of(old);
    } catch (final SAXException e) {
      return Optional.empty();
    }
  }

  /** The old file as TripleA's model writes it, none for no game. */
  private static Optional<Document> modelDom(final byte[] bytes) throws IOException {
    final Optional<Game> model = modelOf(bytes);
    return model.isPresent() ? Optional.of(dom(model.get())) : Optional.empty();
  }

  /** The model of a file's bytes, the one read ahead when the bytes are those; none for no game. */
  private static Optional<Game> modelOf(final byte[] bytes) throws IOException {
    final Optional<ReadAhead> ahead =
        Optional.ofNullable(readAhead.get()).filter(it -> Arrays.equals(it.bytes(), bytes));
    return ahead.isPresent() ? result(ahead.get().model()) : read(bytes);
  }

  private static Optional<Game> read(final byte[] bytes) {
    try {
      return Optional.of(new XmlMapper(new ByteArrayInputStream(bytes)).mapXmlToObject(Game.class));
    } catch (final XmlParsingException e) {
      return Optional.empty();
    }
  }

  private static <T> Future<T> inBackground(final Callable<T> work) {
    final FutureTask<T> task = new FutureTask<>(work);
    Thread.ofVirtual().start(task);
    return task;
  }

  private static <T> T result(final Future<T> future) throws IOException {
    try {
      return future.get();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("The save was interrupted", e);
    } catch (final ExecutionException e) {
      throw e.getCause() instanceof final IOException io ? io : new IOException(e.getCause());
    }
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
    boolean nested = false;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof final Element inner) {
        unnest(inner);
        nested |= inner.getTagName().equals(element.getTagName());
      }
    }
    if (!nested) {
      return;
    }
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
    final NamedNodeMap attributes = old.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      final Attr attribute = (Attr) attributes.item(i);
      final String name = canonical(attribute.getName());
      if (!hasAttribute(model, name) && !hasAttribute(written, name)) {
        written.setAttribute(attribute.getName(), attribute.getValue());
      }
    }
    if (old.getFirstChild() == null) {
      // most are, eg options: nothing of the old one's to keep inside
      return;
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
      final String key = key(child);
      poll(models, key)
          .ifPresent(
              element -> {
                inModel.put(child, element);
                poll(writtens, key)
                    .ifPresentOrElse(
                        match -> inWritten.put(child, match), () -> removed.add(child));
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

  // made for each element of the three trees, so without maps: a big map has 200,000 elements
  private static String key(final Element element) {
    final String[] values = keyValues(element);
    final StringBuilder key = new StringBuilder(canonical(element.getTagName()));
    for (int i = 0; i < KEYS.size(); i++) {
      if (values[i] != null) {
        key.append(' ').append(KEYS.get(i)).append('=').append(values[i]);
      }
    }
    return key.toString();
  }

  /** The attributes that tell an element apart, in a fixed order. */
  private static Map<String, String> keyAttributes(final Element element) {
    final String[] values = keyValues(element);
    final Map<String, String> key = new LinkedHashMap<>();
    for (int i = 0; i < KEYS.size(); i++) {
      if (values[i] != null) {
        key.put(KEYS.get(i), values[i]);
      }
    }
    return key;
  }

  /** The key attributes' values in the order of {@link #KEYS}, null for none or empty. */
  private static String[] keyValues(final Element element) {
    final String[] values = new String[KEYS.size()];
    final NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      final int at = keyIndex(attributes.item(i).getNodeName());
      final String value = attributes.item(i).getNodeValue();
      // TripleA reads an empty one as none, and writes none
      if (at >= 0 && !value.isEmpty()) {
        values[at] = value;
      }
    }
    return values;
  }

  /** Where an attribute is in {@link #KEYS}, -1 for none. */
  private static int keyIndex(final String name) {
    return KEY_INDEX.computeIfAbsent(name, any -> KEYS.indexOf(canonical(name)));
  }

  private static boolean hasAttribute(final Element element, final String canonicalName) {
    final NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      if (canonical(attributes.item(i).getNodeName()).equals(canonicalName)) {
        return true;
      }
    }
    return false;
  }

  /** A name as TripleA reads it: tags in any case, "attatch" for "attach" in old maps. */
  private static String canonical(final String name) {
    return CANONICAL.computeIfAbsent(
        name, any -> name.toLowerCase(Locale.ROOT).replace("attatch", "attach"));
  }

  private static List<Element> elements(final Element parent) {
    final List<Element> elements = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof final Element element) {
        elements.add(element);
      }
    }
    return elements;
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
