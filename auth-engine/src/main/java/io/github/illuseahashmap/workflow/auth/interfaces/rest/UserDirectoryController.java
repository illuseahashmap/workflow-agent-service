package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.UserDirectoryService;
import io.github.illuseahashmap.workflow.auth.application.dto.DirectoryUserView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/directory")
public class UserDirectoryController {

    private final UserDirectoryService userDirectoryService;

    public UserDirectoryController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('workflow:instance:operate')")
    public ApiResponse<PageResult<DirectoryUserView>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(userDirectoryService.search(keyword, pageNum, pageSize));
    }
}
