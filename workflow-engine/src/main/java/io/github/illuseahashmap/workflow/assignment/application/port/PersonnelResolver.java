package io.github.illuseahashmap.workflow.assignment.application.port;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface PersonnelResolver {

    List<String> resolve(String processDefinitionKey, String taskDefinitionKey,
                         String businessKey, Map<String, Object> variables);
}
