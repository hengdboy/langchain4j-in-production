package com.example.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lab5 在线入口：6 份虚构简历 × 1 个 JD，真实初筛并按金标等级对账，
 * 同时验证"输出里不能有 PII"。
 *
 * <pre>
 * DEEPSEEK_API_KEY=sk-xxx mvn compile exec:java \
 * -Dexec.mainClass=com.example.lab.ResumeScreeningApp
 * </pre>
 * @author hengdboy
 */
public final class ResumeScreeningApp {

    private ResumeScreeningApp() {}

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", System.getenv("OPENAI_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("缺少环境变量 DEEPSEEK_API_KEY（或 OPENAI_API_KEY）");
            System.exit(1);
        }
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey(apiKey)
                .modelName("deepseek-v4-flash")
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .build();

        String jd = new String(ResumeScreeningApp.class.getClassLoader()
                .getResourceAsStream("jd.txt").readAllBytes(), "UTF-8");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode gold = mapper.readTree(ResumeScreeningApp.class.getClassLoader()
                .getResourceAsStream("gold.json"));

        List<String> files = new ArrayList<>();
        gold.fieldNames().forEachRemaining(files::add);

        ResumeScreeningService service = new ResumeScreeningService(model);
        int hits = 0;
        for (String file : files) {
            String resume = new String(ResumeScreeningApp.class.getClassLoader()
                    .getResourceAsStream(file).readAllBytes(), "UTF-8");
            ResumeScreeningService.ScreeningResult r = service.screen(jd, resume);
            String expect = gold.get(file).asText();
            boolean ok = r.getNormalizedGrade().name().equals(expect);
            hits += ok ? 1 : 0;
            boolean clean = PiiRedactor.isClean(r.getStrengthsSummary() + r.getRisksSummary());
            System.out.printf("%s %-7s 期望=%s 得分=%3d  亮点=%s  风险=%s  PII干净=%s%n",
                    ok ? "" : "", file, expect, r.getMatchScore(),
                    r.getStrengthsSummary(), r.getRisksSummary(), clean ? "是" : "否（应拦截，见服务端兜底）");
        }
        System.out.printf("%n等级一致率：%d/%d；全部输出 PII 干净（服务端兜底在位）%n",
                hits, files.size());
    }
}
