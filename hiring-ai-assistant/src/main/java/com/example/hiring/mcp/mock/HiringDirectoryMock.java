package com.example.hiring.mcp.mock;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.30 MCP 内存目录（独立于 MySQL 的确定性演示数据）。
 *
 * <h2>为什么不用数据库</h2>
 * <p>stdio MCP Server 是轻量子进程（每次 IDE/客户端启动拉起一次），
 * 不该也不需要连 MySQL——它暴露的是<b>只读查询</b>工具。与主应用共享数据要走 HTTP MCP，
 * mock 数据让子进程零依赖启动。
 *
 * <p>生产接法：MCP Server 与主应用共享数据要走 HTTP MCP + 鉴权，
 * 或把只读查询收敛到一个内部 API。
 *
 * @author hengdboy
 * @since v0.30
 */
@Component
public class HiringDirectoryMock {

    /** 招聘官视角的候选人目录（candidateId → 概要） */
    public final Map<Long, CandidateEntry> candidates = new ConcurrentHashMap<>(Map.of(
            1L, new CandidateEntry(1L, "zhangwei.demo@example.com", "张伟",
                    "OFFER_SENT", 85, "资深 Java 后端工程师（AI 方向）"),
            2L, new CandidateEntry(2L, "liping.demo@example.com", "李萍",
                    "REJECTED", 20, "资深 Java 后端工程师（AI 方向）"),
            3L, new CandidateEntry(3L, "chenjing.demo@example.com", "陈静",
                    "INTERVIEW_SCHEDULED", 75, "资深 Java 后端工程师（AI 方向）")
    ));

    /** 职位目录 */
    public final Map<Long, JobEntry> jobs = new ConcurrentHashMap<>(Map.of(
            1L, new JobEntry(1L, "资深 Java 后端工程师（AI 方向）", "AI 平台部", 1, "OPEN"),
            2L, new JobEntry(2L, "大模型应用算法工程师", "AI 平台部", 2, "OPEN")
    ));

    /** 面试安排目录 */
    public final Map<Long, InterviewEntry> interviews = new ConcurrentHashMap<>(Map.of(
            1L, new InterviewEntry(1L, 3L, LocalDateTime.now().plusDays(2).withHour(14).withMinute(0),
                    90, "video", "interviewer-01", "BOOKED")
    ));

    /** 候选人条目。 */
    public record CandidateEntry(Long id, String email, String name, String status,
                                 Integer matchScore, String jobTitle) {}

    /** 职位条目。 */
    public record JobEntry(Long id, String title, String department,
                           Integer headcount, String status) {}

    /** 面试条目。 */
    public record InterviewEntry(Long id, Long candidateId, LocalDateTime slotStart,
                                 Integer durationMinutes, String mode,
                                 String interviewer, String status) {}

    /** 按 ID 查候选人。 */
    public CandidateEntry candidate(Long id) {
        return candidates.get(id);
    }

    /** 全部职位。 */
    public List<JobEntry> openJobs() {
        return jobs.values().stream().filter(j -> "OPEN".equals(j.status())).toList();
    }

    /** 按候选人查面试。 */
    public List<InterviewEntry> interviewsOf(Long candidateId) {
        return interviews.values().stream()
                .filter(i -> candidateId.equals(i.candidateId())).toList();
    }
}
