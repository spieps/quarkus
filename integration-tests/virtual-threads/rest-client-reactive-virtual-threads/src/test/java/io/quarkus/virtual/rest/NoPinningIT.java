package io.quarkus.virtual.rest;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * An integration test reading the output of the unit test to verify that no tests where pinning the carrier thread.
 * It reads the reports generated by surefire.
 */
public class NoPinningIT {

    @Test
    void verify() throws IOException, ParserConfigurationException, SAXException {
        var reports = new File("target", "surefire-reports");
        Assertions.assertTrue(reports.isDirectory(),
                "Unable to find " + reports.getAbsolutePath() + ", did you run the tests with Maven before?");
        var list = reports.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith("TEST") && name.endsWith("Test.xml");
            }
        });
        Assertions.assertNotNull(list,
                "Unable to find " + reports.getAbsolutePath() + ", did you run the tests with Maven before?");

        for (File report : list) {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report);
            var suite = document.getFirstChild();
            var cases = getChildren(suite.getChildNodes(), "testcase");
            for (Node c : cases) {
                verify(report, c);
            }
        }

    }

    private void verify(File file, Node ca) {
        var fullname = ca.getAttributes().getNamedItem("classname").getTextContent() + "."
                + ca.getAttributes().getNamedItem("name").getTextContent();
        var output = getChildren(ca.getChildNodes(), "system-out");
        if (output.isEmpty()) {
            return;
        }
        var sout = output.get(0).getTextContent();
        if (sout.contains("VThreadContinuation.onPinned")) {
            throw new AssertionError("The test case " + fullname + " pinned the carrier thread, check " + file.getAbsolutePath()
                    + " for details (or the log of the test)");
        }

    }

    private List<Node> getChildren(NodeList nodes, String name) {
        List<Node> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (node.getNodeName().equalsIgnoreCase(name)) {
                list.add(node);
            }
        }
        return list;
    }

}
