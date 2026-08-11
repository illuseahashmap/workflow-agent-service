package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantAction;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.InclusiveGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.common.engine.impl.variable.MapDelegateVariableContainer;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the user-task frontier of a BPMN model. It owns graph traversal
 * and Flowable condition evaluation, leaving participant policy to the
 * application service.
 */
@Component
public final class FlowableUserTaskPathResolver {

    private final RepositoryService repositoryService;
    private final ExpressionManager expressionManager;

    public FlowableUserTaskPathResolver(
            RepositoryService repositoryService,
            ProcessEngineConfigurationImpl processEngineConfiguration) {
        this.repositoryService = repositoryService;
        this.expressionManager = processEngineConfiguration.getExpressionManager();
    }

    public List<UserTask> firstUserTasks(String processDefinitionId, Map<String, Object> variables) {
        BpmnModel model = requireModel(processDefinitionId);
        List<FlowNode> startEvents = model.getMainProcess().getFlowElements().stream()
                .filter(StartEvent.class::isInstance)
                .map(StartEvent.class::cast)
                .map(FlowNode.class::cast)
                .toList();
        return nextUserTasks(processDefinitionId, startEvents, safeVariables(variables));
    }

    public List<UserTask> targetTasks(
            Task task, TaskParticipantAction action, String targetActivityId,
            Map<String, Object> variables) {
        BpmnModel model = requireModel(task.getProcessDefinitionId());
        if (action == TaskParticipantAction.REJECT) {
            UserTask target = StringUtils.hasText(targetActivityId)
                    ? findUserTask(model, targetActivityId.trim())
                    : model.getMainProcess().getFlowElements().stream()
                            .filter(UserTask.class::isInstance)
                            .map(UserTask.class::cast)
                            .findFirst()
                            .orElse(null);
            if (target == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Reject target user task does not exist");
            }
            return List.of(target);
        }
        FlowElement current = model.getMainProcess().getFlowElement(task.getTaskDefinitionKey(), true);
        if (!(current instanceof FlowNode flowNode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Current workflow activity does not exist");
        }
        return nextUserTasks(task.getProcessDefinitionId(), List.of(flowNode), safeVariables(variables));
    }

    public UserTask findUserTask(BpmnModel model, String activityId) {
        FlowElement element = model.getMainProcess().getFlowElement(activityId, true);
        return element instanceof UserTask userTask ? userTask : null;
    }

    public BpmnModel requireModel(String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process model does not exist");
        }
        return model;
    }

    public String displayName(UserTask task) {
        return StringUtils.hasText(task.getName()) ? task.getName().trim() : task.getId();
    }

    private List<UserTask> nextUserTasks(
            String processDefinitionId, Collection<FlowNode> sources, Map<String, Object> variables) {
        Set<String> agentWaitStateIds = agentWaitStateIds(processDefinitionId);
        Queue<FlowElement> queue = new ArrayDeque<>();
        for (FlowNode source : sources) {
            enqueueTargets(queue, selectedOutgoingFlows(source, variables));
        }
        Set<String> visited = new HashSet<>();
        Map<String, UserTask> results = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            FlowElement element = queue.remove();
            if (!visited.add(element.getId())) {
                continue;
            }
            if (element instanceof UserTask userTask) {
                results.putIfAbsent(userTask.getId(), userTask);
                continue;
            }
            if (agentWaitStateIds.contains(element.getId()) || isAgentWaitState(element)) {
                // An Agent task is an asynchronous wait state. Do not expose user tasks
                // behind it as start-time participant requirements.
                continue;
            }
            if (element instanceof FlowNode flowNode) {
                enqueueTargets(queue, selectedOutgoingFlows(flowNode, variables));
            }
        }
        return List.copyOf(results.values());
    }

    private List<SequenceFlow> selectedOutgoingFlows(FlowNode node, Map<String, Object> variables) {
        if (node instanceof ExclusiveGateway gateway) {
            return selectExclusiveFlow(gateway, variables);
        }
        if (node instanceof InclusiveGateway gateway) {
            return selectInclusiveFlows(gateway, variables);
        }
        if (node instanceof Gateway) {
            return node.getOutgoingFlows();
        }
        return selectConditionalActivityFlows(node, variables);
    }

    private List<SequenceFlow> selectConditionalActivityFlows(
            FlowNode node, Map<String, Object> variables) {
        List<SequenceFlow> outgoingFlows = node.getOutgoingFlows();
        if (outgoingFlows.stream().noneMatch(this::hasCondition)) {
            return outgoingFlows;
        }
        SequenceFlow defaultFlow = node instanceof Activity activity
                ? defaultActivityFlow(activity) : null;
        List<SequenceFlow> selected = outgoingFlows.stream()
                .filter(flow -> flow != defaultFlow)
                .filter(this::hasCondition)
                .filter(flow -> conditionMatches(flow, variables))
                .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        if (defaultFlow != null) {
            return List.of(defaultFlow);
        }
        List<SequenceFlow> unconditional = outgoingFlows.stream()
                .filter(flow -> !hasCondition(flow))
                .toList();
        if (!unconditional.isEmpty()) {
            return unconditional;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "No outgoing condition matched activity " + node.getId());
    }

    private SequenceFlow defaultActivityFlow(Activity activity) {
        if (!StringUtils.hasText(activity.getDefaultFlow())) {
            return null;
        }
        return activity.getOutgoingFlows().stream()
                .filter(flow -> activity.getDefaultFlow().equals(flow.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "Activity " + activity.getId() + " references an unknown default flow"));
    }

    private boolean hasCondition(SequenceFlow flow) {
        return StringUtils.hasText(flow.getConditionExpression());
    }

    private List<SequenceFlow> selectExclusiveFlow(ExclusiveGateway gateway, Map<String, Object> variables) {
        SequenceFlow defaultFlow = defaultFlow(gateway);
        for (SequenceFlow flow : gateway.getOutgoingFlows()) {
            if (flow != defaultFlow && conditionMatches(flow, variables)) {
                return List.of(flow);
            }
        }
        if (defaultFlow != null) {
            return List.of(defaultFlow);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "No outgoing condition matched gateway " + gateway.getId());
    }

    private List<SequenceFlow> selectInclusiveFlows(InclusiveGateway gateway, Map<String, Object> variables) {
        SequenceFlow defaultFlow = defaultFlow(gateway);
        List<SequenceFlow> selected = gateway.getOutgoingFlows().stream()
                .filter(flow -> flow != defaultFlow)
                .filter(flow -> conditionMatches(flow, variables))
                .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        if (defaultFlow != null) {
            return List.of(defaultFlow);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "No outgoing condition matched gateway " + gateway.getId());
    }

    private SequenceFlow defaultFlow(Gateway gateway) {
        if (!StringUtils.hasText(gateway.getDefaultFlow())) {
            return null;
        }
        return gateway.getOutgoingFlows().stream()
                .filter(flow -> gateway.getDefaultFlow().equals(flow.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "Gateway " + gateway.getId() + " references an unknown default flow"));
    }

    private boolean conditionMatches(SequenceFlow flow, Map<String, Object> variables) {
        if (!hasCondition(flow)) {
            return true;
        }
        try {
            Object result = expressionManager.createExpression(flow.getConditionExpression())
                    .getValue(new MapDelegateVariableContainer(new HashMap<>(variables), null));
            if (result instanceof Boolean booleanResult) {
                return booleanResult;
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Gateway condition must evaluate to a boolean: " + flow.getId());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Gateway condition cannot be evaluated for flow " + flow.getId(), exception);
        }
    }

    private void enqueueTargets(Queue<FlowElement> queue, Collection<SequenceFlow> flows) {
        flows.stream()
                .map(SequenceFlow::getTargetFlowElement)
                .filter(java.util.Objects::nonNull)
                .forEach(queue::add);
    }

    private boolean isAgentWaitState(FlowElement element) {
        if (element.getExtensionElements() == null) {
            return false;
        }
        return element.getExtensionElements().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .anyMatch(this::containsAgentExtension);
    }

    private boolean containsAgentExtension(ExtensionElement extension) {
        if (isAgentExtension(extension)) {
            return true;
        }
        if (extension.getChildElements() == null) {
            return false;
        }
        return extension.getChildElements().values().stream()
                .flatMap(Collection::stream)
                .anyMatch(this::containsAgentExtension);
    }

    private Set<String> agentWaitStateIds(String processDefinitionId) {
        var definition = repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null || definition.getDeploymentId() == null
                || !StringUtils.hasText(definition.getResourceName())) {
            return Set.of();
        }
        try (InputStream input = repositoryService.getResourceAsStream(
                definition.getDeploymentId(), definition.getResourceName())) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(input);
            NodeList allElements = document.getElementsByTagName("*");
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < allElements.getLength(); index++) {
                Element candidate = (Element) allElements.item(index);
                String localName = candidate.getLocalName();
                String nodeName = candidate.getNodeName();
                if (!"agentTask".equals(localName)
                        && !"agentTask".equals(nodeName)
                        && !nodeName.endsWith(":agentTask")) {
                    continue;
                }
                Node current = candidate.getParentNode();
                while (current != null) {
                    if (current instanceof Element element && element.hasAttribute("id")
                            && !"extensionElements".equals(element.getLocalName())) {
                        ids.add(element.getAttribute("id"));
                        break;
                    }
                    current = current.getParentNode();
                }
            }
            return Set.copyOf(ids);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Unable to inspect deployed BPMN Agent tasks", exception);
        }
    }

    private boolean isAgentExtension(ExtensionElement extension) {
        return "agentTask".equals(extension.getName())
                || "agentTask".equals(extension.getNamespacePrefix() + ":" + extension.getName())
                || "http://workflow-agent.local/bpmn".equals(extension.getNamespace());
    }

    private Map<String, Object> safeVariables(Map<String, Object> variables) {
        return variables == null ? Map.of() : new HashMap<>(variables);
    }
}
