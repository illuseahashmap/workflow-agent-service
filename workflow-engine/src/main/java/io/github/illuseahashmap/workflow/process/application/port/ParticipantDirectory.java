package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Collection;
import java.util.Set;

public interface ParticipantDirectory {

    Set<String> validateSelectableUsernames(Collection<String> usernames);

    void requireTransferableUsernames(Collection<String> usernames);
}
