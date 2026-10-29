package com.example.hiring.mcp.tools;

import com.example.hiring.mcp.mock.HiringDirectoryMock;
import com.example.hiring.tools.MailGateway;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.30 MCP 工具单元测试（内存目录 mock，无 DB）。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
class HiringMcpToolsTest {

    private final HiringDirectoryMock directory = new HiringDirectoryMock();

    @Test
    @DisplayName("【候选人】按 ID 查状态；不含简历原文，也不含联系方式（最小披露）")
    void testGetCandidateStatus() {
        CandidateMcpTools tools = new CandidateMcpTools(directory);
        String out = tools.getCandidateStatus(1L);
        assertThat(out).contains("张伟").contains("OFFER_SENT").contains("85")
                .doesNotContain("@").doesNotContain("138");
        log.info("{}", out);
    }

    @Test
    @DisplayName("【候选人】查不存在的 ID 给友好提示")
    void testGetCandidateNotFound() {
        assertThat(new CandidateMcpTools(directory).getCandidateStatus(999L))
                .contains("未找到");
    }

    @Test
    @DisplayName("【候选人】按状态过滤")
    void testListByStatus() {
        String out = new CandidateMcpTools(directory).listCandidatesByStatus("offer_sent");
        assertThat(out).contains("张伟");
        assertThat(new CandidateMcpTools(directory).listCandidatesByStatus("APPLIED"))
                .contains("暂无");
    }

    @Test
    @DisplayName("【职位】列出在招职位")
    void testListOpenJobs() {
        String out = new JobMcpTools(directory).listOpenJobs();
        assertThat(out).contains("资深 Java 后端工程师").contains("大模型应用算法工程师");
    }

    @Test
    @DisplayName("【面试】按候选人查排期；无安排给提示")
    void testInterviews() {
        String out = new InterviewMcpTools(directory).listInterviews(3L);
        assertThat(out).contains("interviewer-01").contains("video");
        assertThat(new InterviewMcpTools(directory).listInterviews(1L)).contains("暂无");
    }

    @Test
    @DisplayName("【邮件】发送入 mock 网关，列表可见")
    void testEmailTools() {
        MailGateway gateway = new MailGateway();
        EmailMcpTools tools = new EmailMcpTools(gateway);
        assertThat(tools.emailListSent()).contains("暂无");
        tools.emailSend("hr@example.com", "测试", "正文");
        assertThat(tools.emailListSent()).contains("hr@example.com").contains("测试");
    }
}
