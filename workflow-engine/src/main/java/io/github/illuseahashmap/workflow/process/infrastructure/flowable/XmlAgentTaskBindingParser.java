package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.domain.AgentTaskBinding;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Reads only the platform extension, with XXE disabled before parsing user-supplied BPMN. */
@Component
public class XmlAgentTaskBindingParser implements AgentTaskBindingParser {

    private static final String WORKFLOW_NAMESPACE = "http://workflow-agent.local/bpmn";

    @Override
    public List<AgentTaskBinding> parse(String bpmnXml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(bpmnXml)));
            var xpath = XPathFactory.newInstance().newXPath();
            NodeList nodes = (NodeList) xpath.evaluate(
                    "//*[local-name()='agentTask' and namespace-uri()='" + WORKFLOW_NAMESPACE + "']",
                    document, XPathConstants.NODESET);
            List<AgentTaskBinding> bindings = new ArrayList<>();
            for (int index = 0; index < nodes.getLength(); index++) {
                Element extension = (Element) nodes.item(index);
                Node parent = extension.getParentNode();
                Node waitTask = parent == null ? null : parent.getParentNode();
                if (!(waitTask instanceof Element task)
                        || !"receiveTask".equals(task.getLocalName())) {
                    throw invalid("workflow:agentTask must belong to a bpmn:receiveTask wait state");
                }
                bindings.add(new AgentTaskBinding(
                        required(task, "id"),
                        task.getAttribute("name"),
                        positiveLong(extension, "agentVersionId"),
                        defaultJson(extension.getAttribute("inputMapping")),
                        defaultJson(extension.getAttribute("outputMapping")),
                        defaultValue(extension.getAttribute("failurePolicy"), "FAIL_PROCESS")));
            }
            return List.copyOf(bindings);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("BPMN contains invalid Agent task configuration");
        }
    }

    private long positiveLong(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        try {
            long result = Long.parseLong(value);
            if (result > 0) {
                return result;
            }
        } catch (NumberFormatException ignored) {
            // normalized below
        }
        throw invalid("Agent task requires a positive agentVersionId");
    }

    private String required(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        if (value == null || value.isBlank()) {
            throw invalid("Agent task requires " + attribute);
        }
        return value;
    }

    private String defaultJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
