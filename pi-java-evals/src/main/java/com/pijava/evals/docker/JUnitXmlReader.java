package com.pijava.evals.docker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * 批 32（docs/44 §7.2）：单臂 JUnit XML 读取器。
 *
 * <p>支持 {@code <testsuite>} 与 {@code <testsuites>} 根；计数优先读属性，缺失时
 * 数子元素。坏 XML 抛 {@link IllegalArgumentException}（由调用方记 {@code errored}）。</p>
 */
public final class JUnitXmlReader {

    private JUnitXmlReader() {
    }

    /**
     * Single-arm report summary.
     *
     * @param tests    test count
     * @param failures failure count
     * @param errors   error count
     * @param skipped  skipped count
     * @param message  first failure/error message (empty when none)
     */
    public record JUnitResult(int tests, int failures, int errors, int skipped,
                              String message) {

        /** @return {@code true} when at least one test ran and none failed/errored */
        public boolean passed() {
            return tests > 0 && failures == 0 && errors == 0;
        }
    }

    /**
     * Read a JUnit XML file.
     *
     * @param xml file path
     * @return summary
     * @throws IOException if the file cannot be read
     */
    public static JUnitResult read(Path xml) throws IOException {
        try (var in = Files.newInputStream(xml)) {
            return parse(in);
        }
    }

    /**
     * Parse a JUnit XML string.
     *
     * @param xml XML text
     * @return summary
     */
    public static JUnitResult read(String xml) {
        try (var in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JUnit XML: " + e.getMessage(), e);
        }
    }

    private static JUnitResult parse(InputStream in) throws IOException {
        Document document;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            document = factory.newDocumentBuilder().parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("Invalid JUnit XML: " + e.getMessage(), e);
        }
        var root = document.getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("Invalid JUnit XML: empty document");
        }
        var suites = suites(root);
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        for (var suite : suites) {
            tests += count(suite, "tests", "testcase");
            failures += count(suite, "failures", "failure");
            errors += count(suite, "errors", "error");
            skipped += count(suite, "skipped", "skipped");
        }
        return new JUnitResult(tests, failures, errors, skipped, firstMessage(suites));
    }

    private static List<Element> suites(Element root) {
        return switch (root.getTagName()) {
            case "testsuite" -> List.of(root);
            case "testsuites" -> children(root, "testsuite");
            default -> throw new IllegalArgumentException(
                "Invalid JUnit XML: unexpected root element " + root.getTagName());
        };
    }

    private static int count(Element suite, String attribute, String childName) {
        var raw = suite.getAttribute(attribute);
        if (raw != null && !raw.isBlank()) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Invalid JUnit XML: bad " + attribute + " value " + raw, e);
            }
        }
        return children(suite, childName).size();
    }

    private static String firstMessage(List<Element> suites) {
        for (var suite : suites) {
            for (var testCase : children(suite, "testcase")) {
                for (var child : elements(testCase)) {
                    var tag = child.getTagName();
                    if ("failure".equals(tag) || "error".equals(tag)) {
                        var message = child.getAttribute("message");
                        if (message != null && !message.isBlank()) {
                            return message;
                        }
                        var text = child.getTextContent();
                        return text == null ? "" : text.trim();
                    }
                }
            }
        }
        return "";
    }

    private static List<Element> children(Element parent, String name) {
        return elements(parent).stream()
            .filter(child -> name.equals(child.getTagName()))
            .toList();
    }

    private static List<Element> elements(Element parent) {
        var elements = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) node);
            }
        }
        return elements;
    }
}
