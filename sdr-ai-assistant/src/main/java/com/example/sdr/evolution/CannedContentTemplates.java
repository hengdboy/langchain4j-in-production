package com.example.sdr.evolution;

import java.util.List;
import java.util.Map;

/**
 * Ch24 v0.28 阶段三多语言开发信模板（按 arm_id 选 tone + cta）。
 *
 * <h2>臂空间</h2>
 * <p>教学版定义 6 个 arm：{@code tone ∈ {formal, casual, concise}} × {@code cta ∈ {quote, sample}}
 * × {@code channel=email}。每个 arm 一个固定模板，canned 模式直接返回。
 *
 * <p>真实模式下 {@link com.example.sdr.agent.OutreachContentAgent} 接管，臂信息作为 prompt 参数。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
public final class CannedContentTemplates {

    private CannedContentTemplates() {}

    /** 6 个臂 ID（与 BanditOptimizer.knownArms 一致） */
    public static final List<String> ARM_IDS = List.of(
            "formal:quote:email", "casual:quote:email", "concise:quote:email",
            "formal:sample:email", "casual:sample:email", "concise:sample:email"
    );

    /** arm_id → {subject, body}（占位用 {{kp}} / {{company}} / {{imports}}） */
    private static final Map<String, Template> TEMPLATES = Map.ofEntries(
            Map.entry("formal:quote:email", new Template(
                    "Partnership opportunity — {{company}} × Workbuddy",
                    """
                            Dear {{kp}},

                            We have noted {{company}}'s recent imports of {{imports}}, and believe our
                            smart home & IoT solutions can complement your supply chain.

                            Could we share a formal quotation for your evaluation? I am happy to provide
                            pricing tiers and MOQ details upon a brief alignment call.

                            Best regards,
                            SDR Team
                            (You may unsubscribe anytime.)
                            """.strip())),
            Map.entry("casual:quote:email", new Template(
                    "Quick idea for {{company}} 👋",
                    """
                            Hey {{kp}},

                            Saw {{company}} is bringing in {{imports}} — thought you might want a
                            peek at what we do. Happy to throw a quote your way, no strings.

                            Coffee chat this week?

                            Cheers,
                            SDR Team
                            Unsubscribe anytime.
                            """.strip())),
            Map.entry("concise:quote:email", new Template(
                    "{{company}} — pricing request",
                    """
                            Hi {{kp}},

                            We supply smart home / IoT. {{company}} imports {{imports}}.
                            Send pricing spec? Reply with quantities, we'll return a quote within 24h.

                            SDR Team (unsubscribe: reply STOP)
                            """.strip())),
            Map.entry("formal:sample:email", new Template(
                    "Complimentary sample — {{company}} evaluation",
                    """
                            Dear {{kp}},

                            Given {{company}}'s sustained imports of {{imports}}, we would like
                            to offer complimentary product samples for your engineering team's review.

                            Please advise your preferred SKUs and shipping destination.

                            Sincerely,
                            SDR Team
                            (Unsubscribe anytime.)
                            """.strip())),
            Map.entry("casual:sample:email", new Template(
                    "Free samples headed to {{company}}?",
                    """
                            Hey {{kp}},

                            Big fan of what {{company}} is doing with {{imports}}.
                            Want to drop some samples your way? Just tell us what to ship.

                            SDR Team
                            Unsubscribe anytime.
                            """.strip())),
            Map.entry("concise:sample:email", new Template(
                    "Samples for {{company}}",
                    """
                            Hi {{kp}},

                            Smart home / IoT supplier. {{company}} imports {{imports}}.
                            Free samples available — tell us your top 3 SKUs.

                            SDR Team (STOP = unsubscribe)
                            """.strip()))
    );

    /**
     * 按 arm_id 取模板（不在 6 个内抛 IllegalArgumentException）。
     */
    public static Template forArm(String armId) {
        Template t = TEMPLATES.get(armId);
        if (t == null) {
            throw new IllegalArgumentException("unknown arm: " + armId);
        }
        return t;
    }

    /** 简单模板：subject + body（带占位符） */
    public record Template(String subject, String body) {}

    /** 占位符替换（极简；教学版不引入 StringSubstitutor 依赖） */
    public static Template render(Template t, String company, String kp, String imports) {
        return new Template(
                t.subject().replace("{{company}}", company == null ? "" : company)
                        .replace("{{kp}}", kp == null ? "there" : kp)
                        .replace("{{imports}}", imports == null ? "your products" : imports),
                t.body().replace("{{company}}", company == null ? "" : company)
                        .replace("{{kp}}", kp == null ? "there" : kp)
                        .replace("{{imports}}", imports == null ? "your products" : imports)
        );
    }
}