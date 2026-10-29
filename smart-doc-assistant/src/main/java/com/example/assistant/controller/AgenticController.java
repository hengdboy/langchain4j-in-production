package com.example.assistant.controller;

import com.example.assistant.agentic.ContentPipelineService;
import com.example.assistant.agentic.CustomerServiceSupervisor;
import com.example.assistant.agentic.WorkflowDemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 新增：多 Agent 编排接口
 *
 * <p>用法：
 * <pre>
 * 顺序工作流：主题 → 撰写初稿 → 精简成一句卖点
 * curl -X POST http://localhost:8090/api/agentic/workflow \
 * -H "Content-Type: application/json" -d '{"topic":"智能咖啡机"}'
 *
 * Supervisor 自主编排：主管分派给退款/物流/技术专家
 * curl -X POST http://localhost:8090/api/agentic/supervisor \
 * -H "Content-Type: application/json" -d '{"question":"七天无理由退货怎么退？运费谁出？"}'
 *
 * 并行工作流：查政策 + 算价格 同时执行
 * curl -X POST http://localhost:8090/api/agentic/parallel \
 * -H "Content-Type: application/json" -d '{"q1":"七天无理由退货的条件？","q2":"500 元打 8 折是多少"}'
 *
 * 循环工作流：初稿反复"评分→修改"直到 ≥8 分或 3 轮
 * curl -X POST http://localhost:8090/api/agentic/loop \
 * -H "Content-Type: application/json" -d '{"text":"我们的咖啡机很厉害"}'
 *
 * 条件工作流：按关键词选专家
 * curl -X POST http://localhost:8090/api/agentic/conditional \
 * -H "Content-Type: application/json" -d '{"q":"退货运费谁出"}'
 *
 * 并行映射：一组主题并行生成卖点
 * curl -X POST http://localhost:8090/api/agentic/mapper \
 * -H "Content-Type: application/json" -d '{"topics":["咖啡机","空气炸锅","扫地机器人"]}'
 * </pre>
 * @author hengdboy
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agentic")
public class AgenticController {

    private final ContentPipelineService contentPipelineService;
    private final CustomerServiceSupervisor customerServiceSupervisor;
    private final WorkflowDemoService workflowDemoService;

    @PostMapping("/workflow")
    public Map<String, Object> workflow(@RequestBody Map<String, String> body) {
        String answer = contentPipelineService.run(body.getOrDefault("topic", "智能咖啡机"));
        return Map.of("success", true, "answer", answer);
    }

    @PostMapping("/supervisor")
    public Map<String, Object> supervisor(@RequestBody Map<String, String> body) {
        String answer = customerServiceSupervisor.ask(body.getOrDefault("question", "你好"));
        return Map.of("success", true, "answer", answer);
    }

    @PostMapping("/parallel")
    public Map<String, Object> parallel(@RequestBody Map<String, String> body) {
        String answer = workflowDemoService.runParallel(
                body.getOrDefault("q1", ""), body.getOrDefault("q2", ""));
        return Map.of("success", true, "answer", answer);
    }

    @PostMapping("/loop")
    public Map<String, Object> loop(@RequestBody Map<String, String> body) {
        String answer = workflowDemoService.runLoop(body.getOrDefault("text", ""));
        return Map.of("success", true, "answer", answer);
    }

    @PostMapping("/conditional")
    public Map<String, Object> conditional(@RequestBody Map<String, String> body) {
        String answer = workflowDemoService.runConditional(body.getOrDefault("q", ""));
        return Map.of("success", true, "answer", answer);
    }

    @PostMapping("/mapper")
    public Map<String, Object> mapper(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) body.getOrDefault("topics", List.of("咖啡机"));
        Object answer = workflowDemoService.runMapper(topics);
        return Map.of("success", true, "answer", answer);
    }
}
