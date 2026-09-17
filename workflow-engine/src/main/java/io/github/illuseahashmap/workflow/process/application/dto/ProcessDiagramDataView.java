package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProcessDiagramDataView(
        String bpmnXml,
        List<String> completedActivityIds,
        List<String> activeActivityIds,
        List<String> highlightedFlows,
        Map<String, ActivityDetail> activityDetails
) {

    public record ActivityDetail(
            String activityId,
            String activityName,
            String activityType,
            String assignee,
            List<String> candidateUsers,
            List<String> candidateGroups,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Long durationInMillis,
            String comment
    ) {
    }
}
