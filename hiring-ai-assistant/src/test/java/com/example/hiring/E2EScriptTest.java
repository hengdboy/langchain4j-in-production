package com.example.hiring;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.30 端到端脚本结构测试。
 *
 * <p>不启动 App，仅验证脚本可被 bash 解析且覆盖关键步骤与断言。
 *
 * @author hengdboy
 * @since v1.0
 */
@Slf4j
class E2EScriptTest {

    private static final Path SCRIPT = Paths.get("scripts/run-e2e.sh");

    @Test
    @DisplayName("【脚本】存在 + 可执行")
    void testScriptExists() {
        assertThat(Files.exists(SCRIPT)).isTrue();
        assertThat(Files.isExecutable(SCRIPT)).isTrue();
    }

    @Test
    @DisplayName("【脚本】bash 语法正确")
    void testScriptSyntax() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("bash", "-n", SCRIPT.toString()).inheritIO().start();
        int exitCode = p.waitFor();
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    @DisplayName("【脚本】覆盖全链路步骤与四条路线")
    void testScriptCoverage() throws IOException {
        String content = Files.readString(SCRIPT);
        assertThat(content)
                .contains("Step 0").contains("Step 1").contains("Step 2")
                .contains("Step 3").contains("Step 4").contains("Step 5").contains("Step 6")
                .contains("/api/jobs").contains("/api/candidates")
                .contains("/reply").contains("/interview/complete")
                .contains("INVITE_SENT").contains("INTERVIEW_SCHEDULED")
                .contains("OFFER_SENT").contains("HIRED").contains("REJECTED")
                .contains("redactedProfile");
        log.info("E2E 脚本契约完整");
    }
}
