package com.example.hiring.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mock 背调工具：学历/履历核验演示。
 *
 * <h2>教学定位</h2>
 * <p>真实背调是第三方服务（学信网/背调公司）。教学版用姓名哈希做确定性 mock：
 * 同一输入永远同一结果——保证测试可复现、demo 可讲解。
 *
 * <h2>合规提示（进 Ch25）</h2>
 * <p>背调必须在候选人<b>书面授权</b>后才能发起；本工具的调用点
 * （OFFER_SENT 之后、入职之前）在设计上就保证了这一点——
 * 状态机本身就是合规护栏的一部分。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
@Component
public class BackgroundCheckTool {

    /** mock 结论池（确定性取样） */
    private static final List<String> VERDICTS = List.of(
            "VERIFIED", "VERIFIED", "VERIFIED", "MISMATCH", "PENDING");

    /**
     * 核验学历与最近履历。
     *
     * @param name       候选人姓名
     * @param university 毕业院校（解析画像中的 education 字段）
     * @return 核验结果（deterministic：同输入同输出）
     */
    public CheckResult verify(String name, String university) {
        String seed = (name == null ? "" : name) + "|" + (university == null ? "" : university);
        int idx = Math.abs(seed.hashCode()) % VERDICTS.size();
        String verdict = VERDICTS.get(idx);
        String caseId = "BC-" + UUID.nameUUIDFromBytes(
                seed.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
        log.info("[BackgroundCheck] {} ({}): {} caseId={}", name, university, verdict, caseId);
        return new CheckResult(caseId, verdict, verdict.equals("VERIFIED"),
                "mock: 教学版按姓名+院校哈希取样，非真实核验");
    }

    /**
     * 背调结果。
     *
     * @param caseId   背调案件号
     * @param verdict  VERIFIED / MISMATCH / PENDING
     * @param passed   是否通过（VERIFIED 才算）
     * @param note     说明
     */
    public record CheckResult(String caseId, String verdict, boolean passed, String note) {}
}
