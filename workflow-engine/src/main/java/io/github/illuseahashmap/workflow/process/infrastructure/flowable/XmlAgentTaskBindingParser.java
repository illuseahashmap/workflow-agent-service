package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.domain.AgentProcessFailurePolicy;
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
    private static final String FLOWABLE_NAMESPACE = "http://flowable.org/bpmn";
    private final ObjectMapper objectMapper;

    public XmlAgentTaskBindingParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
                if (!(waitTask instanceof Element task)) {
                    throw invalid("workflow:agentTask must belong to a BPMN task");
                }
                validateTaskSemantics(task);
                String inputMapping = validJsonObject(extension.getAttribute("inputMapping"), "inputMapping");
                String outputMapping = validJsonObject(extension.getAttribute("outputMapping"), "outputMapping");
                AgentProcessFailurePolicy processFailurePolicy = failurePolicy(extension);
                bindings.add(new AgentTaskBinding(
                        required(task, "id"),
                        task.getAttribute("name"),
                        positiveLong(extension, "agentVersionId"),
                        inputMapping,
                        outputMapping,
                        processFailurePolicy,
                        timeout(extension)));
            }
            return List.copyOf(bindings);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("BPMN contains invalid Agent task configuration");
        }
    }

    private void validateTaskSemantics(Element task) {
        if ("receiveTask".equals(task.getLocalName())) {
            return; // Compatibility for already deployed diagrams.
        }
        if (!"serviceTask".equals(task.getLocalName())) {
            throw invalid("workflow:agentTask must belong to a bpmn:serviceTask");
        }
        String delegateExpression = task.getAttributeNS(FLOWABLE_NAMESPACE, "delegateExpression");
        String triggerable = task.getAttributeNS(FLOWABLE_NAMESPACE, "triggerable");
        String async = task.getAttributeNS(FLOWABLE_NAMESPACE, "async");
        if (!"${agentTaskDelegate}".equals(delegateExpression)
                || !"true".equalsIgnoreCase(triggerable)
                || !"true".equalsIgnoreCase(async)) {
            throw invalid("Agent serviceTask requires agentTaskDelegate, flowable:async=true, "
                    + "and flowable:triggerable=true");
        }
    }

    private String validJsonObject(String value, String field) {
        String json = defaultJson(value);
        try {
            if (!objectMapper.readTree(json).isObject()) {
                throw invalid("Agent task " + field + " must be a JSON object");
            }
            return json;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Agent task " + field + " must be valid JSON");
        }
    }

    private AgentProcessFailurePolicy failurePolicy(Element extension) {
        String value = extension.getAttribute("processFailurePolicy");
        if (value == null || value.isBlank()) {
            value = extension.getAttribute("failurePolicy");
        }
        try {
            return AgentProcessFailurePolicy.parseCompatible(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("Agent task processFailurePolicy is not supported");
        }
    }

    private int timeout(Element extension) {
        String value = extension.getAttribute("processWaitTimeoutSeconds");
        if (value == null || value.isBlank()) {
            value = extension.getAttribute("timeoutSeconds");
        }
        if (value == null || value.isBlank()) {
            return 300;
        }
        try {
            int seconds = Integer.parseInt(value);
            if (seconds >= 1 && seconds <= 3600) {
                return seconds;
            }
        } catch (NumberFormatException ignored) {
            // normalized below
        }
        throw invalid("Agent task processWaitTimeoutSeconds must be between 1 and 3600");
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

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
