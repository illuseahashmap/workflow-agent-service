package io.github.illuseahashmap.workflow.boot.integration;

import io.github.illuseahashmap.workflow.auth.application.UserDirectoryService;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AuthParticipantDirectoryAdapter implements ParticipantDirectory {

    private final UserDirectoryService userDirectoryService;

    public AuthParticipantDirectoryAdapter(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @Override
    public Set<String> validateSelectableUsernames(Collection<String> usernames) {
        return userDirectoryService.validateSelectableUsernames(usernames);
    }
}
