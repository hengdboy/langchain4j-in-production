package com.example.sdr;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.0 端到端脚本结构测试。
 *
 * <p>不启动 App，仅验证脚本可被 bash 解析且包含关键 6 阶段 + 横切验证步骤。
 *
 * @author langchain4j-tutorial
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
    @DisplayName("【脚本】覆盖全部 6 阶段 + 3 个横切")
    void testScriptCoverage() throws IOException {
        String content = Files.readString(SCRIPT);
        assertThat(content).contains("Step 1").contains("Step 2").contains("Step 3")
                .contains("Step 4").contains("Step 5").contains("Step 6");
        assertThat(content).contains("Observability").contains("Audit").contains("Bandit");
        assertThat(content).contains("/api/leads")
                .contains("/api/observability/agent-calls")
                .contains("/api/audit/lead");
        log.info("✅ 脚本覆盖 6 阶段 + 3 横切 + 关键端点");
    }

    @Test
    @DisplayName("【脚本】支持 --no-app 模式（CI 用）")
    void testScriptNoApp() throws IOException {
        String content = Files.readString(SCRIPT);
        assertThat(content).contains("--no-app").contains("APP_RUNNING");
        log.info("✅ 脚本支持 CI 模式（不依赖 App 启动）");
    }
}