package com.example.hiring.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理集成测试：业务异常 → HTTP 语义映射（RFC 7807 ProblemDetail）。
 *
 * @author hengdboy
 * @since v0.31
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("【404】GET 不存在的候选人 → HiringException(NOT_FOUND) 映射 404")
    void candidateNotFound() throws Exception {
        mockMvc.perform(get("/api/candidates/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Candidate not found: 99999999"));
    }

    @Test
    @DisplayName("【404】GET 不存在的职位 → 原先 200 空 body，现为 404")
    void jobNotFound() throws Exception {
        mockMvc.perform(get("/api/jobs/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Job not found: 99999999"));
    }

    @Test
    @DisplayName("【400】投递引用不存在/已关闭的职位 → IllegalArgumentException 映射 400")
    void invalidJobReference() throws Exception {
        mockMvc.perform(post("/api/candidates")
                        .contentType("application/json")
                        .content("{\"email\":\"ghost@example.com\",\"jobId\":99999999,"
                                + "\"resumeText\":\"张三，五年后端经验\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("职位不存在或已关闭: 99999999"));
    }

    @Test
    @DisplayName("【409】对不在待定池的候选人复核 → IllegalStateException 映射 409")
    void reviewWrongState() throws Exception {
        mockMvc.perform(post("/api/candidates/99999999/review")
                        .contentType("application/json")
                        .content("{\"approve\": false, \"reason\": \"经验不符\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("【400】非法 JSON 走 ErrorResponse 委托，框架语义不被兜底吞成 500")
    void malformedJsonKeepsFrameworkStatus() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }
}
