package com.example.hiring.mcp.tools;

import com.example.hiring.mcp.mock.HiringDirectoryMock;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * v0.30 MCP 工具：面试安排查询。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public class InterviewMcpTools {

    private final HiringDirectoryMock directory;

    public InterviewMcpTools(HiringDirectoryMock directory) {
        this.directory = directory;
    }

    @Tool(name = "listInterviews",
          value = "按候选人 ID 查询已安排的面试（时间/时长/形式/面试官）")
    public String listInterviews(@P("候选人 ID") Long candidateId) {
        List<HiringDirectoryMock.InterviewEntry> list = directory.interviewsOf(candidateId);
        if (list.isEmpty()) {
            return "候选人 " + candidateId + " 暂无面试安排";
        }
        StringBuilder sb = new StringBuilder("候选人 " + candidateId + " 的面试安排：\n");
        list.forEach(i -> sb.append("- 时间=%s 时长=%dmin 形式=%s 面试官=%s 状态=%s\n"
                .formatted(i.slotStart(), i.durationMinutes(), i.mode(), i.interviewer(), i.status())));
        return sb.toString();
    }
}
