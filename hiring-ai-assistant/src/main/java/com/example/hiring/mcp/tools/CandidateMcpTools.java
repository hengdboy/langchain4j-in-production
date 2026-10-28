package com.example.hiring.mcp.tools;

import com.example.hiring.mcp.mock.HiringDirectoryMock;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * v0.30 MCP 工具：候选人查询（给外部 HR 助手/IM 机器人用）。
 *
 * <p>暴露面刻意收窄：只有<b>只读查询</b>，且只吐概要字段（姓名/状态/匹配分）——
 * 简历原文与联系方式不出 MCP 边界（最小披露原则）。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public class CandidateMcpTools {

    private final HiringDirectoryMock directory;

    public CandidateMcpTools(HiringDirectoryMock directory) {
        this.directory = directory;
    }

    @Tool(name = "getCandidateStatus",
          value = "按候选人 ID 查询招聘流程状态（姓名/状态/匹配分/职位，不含联系方式与简历原文）")
    public String getCandidateStatus(@P("候选人 ID") Long candidateId) {
        var c = directory.candidate(candidateId);
        if (c == null) {
            return "未找到候选人 id=" + candidateId;
        }
        // 最小披露：姓名/状态/匹配分可以出去，邮箱/电话/简历原文绝不出去
        return "候选人 %d：%s 状态=%s 匹配分=%s 职位=%s".formatted(
                c.id(), c.name(), c.status(), c.matchScore(), c.jobTitle());
    }

    @Tool(name = "listCandidatesByStatus",
          value = "按状态列出候选人（如 OFFER_SENT / INTERVIEW_SCHEDULED）")
    public String listCandidatesByStatus(@P("候选人状态") String status) {
        List<HiringDirectoryMock.CandidateEntry> matched = directory.candidates.values().stream()
                .filter(c -> status.equalsIgnoreCase(c.status())).toList();
        if (matched.isEmpty()) {
            return "状态 " + status + " 下暂无候选人";
        }
        StringBuilder sb = new StringBuilder("状态 " + status + " 的候选人：\n");
        matched.forEach(c -> sb.append("- %d %s 匹配分=%s\n".formatted(c.id(), c.name(), c.matchScore())));
        return sb.toString();
    }
}
