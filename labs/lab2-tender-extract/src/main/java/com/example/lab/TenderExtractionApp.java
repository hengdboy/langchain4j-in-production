package com.example.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lab2 在线入口：对 resources/announcements/ 全部公告做真实抽取并按金标评分。
 *
 * <p>用法（需要 DEEPSEEK_API_KEY 或 OPENAI_API_KEY 环境变量）：
 * <pre>
 * mvn compile exec:java -Dexec.mainClass=com.example.lab.TenderExtractionApp
 * </pre>
 * 输出 8 份公告 × 6 字段 = 48 项的字段级准确率与逐项差异。
 */
public final class TenderExtractionApp {

    private TenderExtractionApp() {}

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

        ObjectMapper mapper = new ObjectMapper();
        JsonNode gold = mapper.readTree(TenderExtractionApp.class.getClassLoader()
                .getResourceAsStream("gold.json"));
        List<String> files = new java.util.ArrayList<>();
        gold.fieldNames().forEachRemaining(files::add);

        TenderExtractionService service = new TenderExtractionService(model);
        int total = 0, hit = 0;
        for (String file : files) {
            String text = new String(TenderExtractionApp.class.getClassLoader()
                    .getResourceAsStream("announcements/" + file).readAllBytes(), "UTF-8");
            TenderNormalizer.TenderRecord rec = service.extract(text);
            JsonNode g = gold.get(file);

            total += 6;
            boolean p = eq(rec.projectName(), g.get("projectName").asText());
            boolean u = eq(rec.purchaserName(), g.get("purchaserName").asText());
            boolean b = eqNum(rec.budgetYuan(), g.get("budgetYuan"));
            boolean d = eq(rec.submissionDeadline(), g.get("submissionDeadline").asText());
            boolean q = eqQuals(rec.requiredQualifications(), g.get("requiredQualifications"));
            boolean bond = eqBond(rec.performanceBondYuan(), g.get("performanceBondYuan"));
            hit += (p ? 1 : 0) + (u ? 1 : 0) + (b ? 1 : 0) + (d ? 1 : 0) + (q ? 1 : 0) + (bond ? 1 : 0);

            System.out.printf("%s  项目%s  单位%s  预算%s(%s)  截止%s(%s)  资质%s  保证金%s%n",
                    p && u && b && d && q && bond ? "" : "",
                    mark(p, rec.projectName()), mark(u, rec.purchaserName()),
                    mark(b, String.valueOf(rec.budgetYuan())), g.get("budgetYuan").asText(),
                    mark(d, rec.submissionDeadline()), g.get("submissionDeadline").asText(),
                    mark(q, rec.requiredQualifications().toString()),
                    mark(bond, String.valueOf(rec.performanceBondYuan())));
        }
        System.out.printf("%n字段级准确率：%d/%d = %.1f%%%n", hit, total, 100.0 * hit / total);
    }

    private static String mark(boolean ok, String val) { return (ok ? "" : "") + "[" + val + "]"; }
    private static boolean eq(String a, String b) { return a != null && a.equals(b); }
    private static boolean eqNum(Long a, JsonNode b) { return a != null && b != null && a == b.asLong(); }

    private static boolean eqQuals(List<String> got, JsonNode want) {
        Set<String> gs = new HashSet<>(got);
        Set<String> ws = new HashSet<>();
        want.forEach(n -> ws.add(n.asText()));
        return gs.equals(ws);
    }

    /** 保证金对账：金标 -1 表示"比例型"，模型输出 null 即算对；0 只和不收取对。 */
    private static boolean eqBond(Long got, JsonNode want) {
        long w = want.asLong();
        if (w == -1) return got == null;
        return got != null && got == w;
    }
}
