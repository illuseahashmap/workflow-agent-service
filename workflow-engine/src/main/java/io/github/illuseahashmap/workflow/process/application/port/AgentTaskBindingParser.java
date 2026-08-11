package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.domain.AgentTaskBinding;
import java.util.List;

/** Parses and validates business Agent bindings without exposing Flowable types to application code. */
public interface AgentTaskBindingParser {

    List<AgentTaskBinding> parse(String bpmnXml);
}
