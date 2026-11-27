package io.github.illuseahashmap.workflow.event;

/** Best-effort local wake-up after an Outbox record has committed. */
@FunctionalInterface
public interface OutboxCommitNotifier {

    void committed(String eventType);
}
