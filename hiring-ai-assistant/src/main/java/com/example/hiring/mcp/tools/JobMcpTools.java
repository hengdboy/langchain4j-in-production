package com.example.hiring.mcp.tools;

import com.example.hiring.mcp.mock.HiringDirectoryMock;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * v0.30 MCP 工具：职位查询。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
public class JobMcpTools {

    private final HiringDirectoryMock directory;

    public JobMcpTools(HiringDirectoryMock directory) {
        this.directory = directory;
    }

    @Tool(name = "listOpenJobs", value = "列出所有在招职位（名称/部门/人数）")
    public String listOpenJobs() {
        List<HiringDirectoryMock.JobEntry> jobs = directory.openJobs();
        if (jobs.isEmpty()) {
            return "当前没有在招职位";
        }
        StringBuilder sb = new StringBuilder("在招职位：\n");
        jobs.forEach(j -> sb.append("- %d %s（%s，招 %d 人）\n"
                .formatted(j.id(), j.title(), j.department(), j.headcount())));
        return sb.toString();
    }
}
