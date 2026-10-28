package com.example.lab;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.List;

/**
 * Lab1 命令行入口：离线评估 + 在线问答两种模式。
 *
 * <p>用法：
 * <pre>
 * mvn compile exec:java -Dexec.mainClass=com.example.lab.ContractQaApp -Dexec.args="eval"
 * mvn compile exec:java -Dexec.mainClass=com.example.lab.ContractQaApp \
 * -Dexec.args="ask 房东提前收房赔多少"   # 需要 DEEPSEEK_API_KEY 或 OPENAI_API_KEY 环境变量
 * </pre>
 */
public final class ContractQaApp {

    private ContractQaApp() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("用法: eval | ask <问题>");
            System.exit(1);
        }
        if (args[0].equals("eval")) {
            runEvaluation(args);
        } else if (args[0].equals("ask")) {
            runAsk(args[1]);
        }
    }

    private static void runEvaluation(String[] args) {
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 450;
        int overlap = args.length > 2 ? Integer.parseInt(args[2]) : 80;
        ContractKnowledge kb = new ContractKnowledge(size, overlap);
        int chunks = kb.ingestClasspathCorpus();
        System.out.println("切块 " + size + "/" + overlap + "，入库完成：" + chunks + " 个块");
        RetrievalEvaluator.Report report = RetrievalEvaluator.evaluate(
                kb, RetrievalEvaluator.loadGoldenSet(), 5);
        System.out.printf("题目数=%d  recall@1=%.3f  recall@3=%.3f  recall@5=%.3f  MRR=%.4f%n",
                report.totalCases(), report.recallAt1(), report.recallAt3(),
                report.recallAt5(), report.mrr());
        for (RetrievalEvaluator.CaseResult c : report.details()) {
            System.out.printf("  rank=%d rr=%.3f  %s%n", c.rank(), c.reciprocalRank(), c.question());
        }
    }

    private static void runAsk(String question) {
        String apiKey = System.getenv().getOrDefault("DEEPSEEK_API_KEY", System.getenv("OPENAI_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("缺少环境变量 DEEPSEEK_API_KEY（或 OPENAI_API_KEY）");
            System.exit(1);
        }
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey(apiKey)
                .modelName("deepseek-v4-flash")
                .timeout(Duration.ofSeconds(60))
                .build();

        ContractKnowledge kb = new ContractKnowledge(450, 80);
        kb.ingestClasspathCorpus();
        List<TextSegment> context = kb.search(question, 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < context.size(); i++) {
            sb.append("【片段").append(i + 1).append("】")
              .append(context.get(i).text().replace('\n', ' '))
              .append('\n');
        }
        ChatResponse resp = model.chat(List.of(
                new SystemMessage("你是合同助理。只依据【参考资料】回答，资料未提及就说\"合同中未找到相关条款\"。回答末尾注明引用的片段编号。"),
                new UserMessage("【参考资料】\n" + sb + "\n【问题】" + question)));
        System.out.println(resp.aiMessage().text());
    }
}
