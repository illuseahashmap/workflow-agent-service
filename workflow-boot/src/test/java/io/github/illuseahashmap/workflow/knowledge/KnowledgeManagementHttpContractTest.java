package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeManagementHttpContractTest {
    @Test
    void invalidBodyAndPaginationReturn400BeforeApplicationInvocation() throws Exception {
        var management = mock(KnowledgeManagementPort.class);
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeManagementController(management,
                () -> new TenantContext.TenantInfo("1", "default", "Default")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content("{invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/knowledge/ingestion-jobs?pageSize=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/knowledge/ingestion-jobs?pageNum=abc")).andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/knowledge/scope-grants")).andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/knowledge/retrieval-profiles/not-a-number/publish")).andExpect(status().isBadRequest());
        verifyNoInteractions(management);
    }

    @Test
    void applicationConflictReturns409() throws Exception {
        var management = mock(KnowledgeManagementPort.class);
        when(management.publishProfile(eq("default"), eq(1L)))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "Profile is already published"));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeManagementController(management,
                () -> new TenantContext.TenantInfo("1", "default", "Default")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/knowledge/retrieval-profiles/1/publish")).andExpect(status().isConflict());
    }
}
