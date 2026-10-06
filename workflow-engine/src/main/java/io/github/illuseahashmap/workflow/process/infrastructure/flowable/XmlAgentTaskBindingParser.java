package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.domain.AgentProcessFailurePolicy;
import io.github.illuseahashmap.workflow.process.domain.AgentTaskBinding;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                if (processFailurePolicy == AgentProcessFailurePolicy.MANUAL_REVIEW) {
                    validateManualReviewPath(document, required(task, "id"));
                }
                bindings.add(new AgentTaskBinding(
                        required(task, "id"),
                        task.getAttribute("name"),
                        positiveLong(extension, "agentVersionId"),
                        inputMapping,
                        outputMapping,
                        processFailurePolicy,
                        timeout(extension), validJsonArray(extension.getAttribute("toolSet"), "toolSet")));
            }
            return List.copyOf(bindings);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("BPMN contains invalid Agent task configuration");
        }
    }

    private void validateTaskSemantics(Element task) {
        if (!"receiveTask".equals(task.getLocalName())) {
            throw invalid("workflow:agentTask must belong to a bpmn:receiveTask");
        }
    }

    private void validateManualReviewPath(org.w3c.dom.Document document, String taskId) {
        Map<String, Element> elementsById = new HashMap<>();
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < elements.getLength(); index++) {
            if (elements.item(index) instanceof Element element && element.hasAttribute("id")) {
                elementsById.put(element.getAttribute("id"), element);
            }
        }

        Map<String, List<PathEdge>> outgoing = new HashMap<>();
        NodeList sequenceFlows = document.getElementsByTagNameNS("*", "sequenceFlow");
        for (int index = 0; index < sequenceFlows.getLength(); index++) {
            Element flow = (Element) sequenceFlows.item(index);
            String sourceRef = flow.getAttribute("sourceRef");
            String targetRef = flow.getAttribute("targetRef");
            boolean reviewCondition = flow.getTextContent().contains("agentReviewRequired");
            outgoing.computeIfAbsent(sourceRef, ignored -> new ArrayList<>())
                    .add(new PathEdge(targetRef, reviewCondition));
        }

        var queue = new ArrayDeque<PathState>();
        var visited = new HashSet<PathState>();
        queue.add(new PathState(taskId, false));
        while (!queue.isEmpty()) {
            PathState current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            Element element = elementsById.get(current.elementId());
            if (current.reviewConditionSeen()
                    && element != null && "userTask".equals(element.getLocalName())) {
                return;
            }
            for (PathEdge edge : outgoing.getOrDefault(current.elementId(), List.of())) {
                queue.addLast(new PathState(
                        edge.targetRef(), current.reviewConditionSeen() || edge.reviewCondition()));
            }
        }
        throw invalid("Agent task " + taskId
                + " uses MANUAL_REVIEW but has no agentReviewRequired path to a bpmn:userTask");
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

    private String validJsonArray(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (!objectMapper.readTree(value).isArray()) {
                throw invalid("Agent task " + field + " must be a JSON array");
            }
            return value;
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

    private record PathEdge(String targetRef, boolean reviewCondition) {
    }

    private record PathState(String elementId, boolean reviewConditionSeen) {
    }
}
