package com.shopbooking.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.Tool;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.Escalation;
import com.shopbooking.service.EscalationService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.shopbooking.agent.Tool.objectSchema;
import static com.shopbooking.agent.Tool.stringProp;

/** 转人工（写）：生成工单，携带会话摘要，顾客不用重讲 */
@Component
public class EscalateToHumanTool implements Tool {

    private final EscalationService escalationService;
    private final ObjectMapper objectMapper;

    public EscalateToHumanTool(EscalationService escalationService, ObjectMapper objectMapper) {
        this.escalationService = escalationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "escalate_to_human";
    }

    @Override
    public String description() {
        return "转接人工客服。仅限以下情形调用：顾客投诉或情绪激动；顾客明确要求找店长/老板/真人；"
                + "涉及价格减免、赔偿、售后纠纷；顾客不接受留言转达、坚持要立刻得到人工答复。"
                + "注意：知识库查不到的问题不要直接调用本工具——先坦诚告知需跟店里确认，并主动提出帮顾客留言转达或请店家回电。"
                + "调用后请安抚顾客，告知店家会尽快联系，并说明顾客的预约记录和需求已一并转交，不用重复说明。";
    }

    @Override
    public boolean sideEffect() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "reason", stringProp("转人工原因（如：知识库无此问题/顾客投诉/需要店长处理）"),
                "summary", stringProp("给店长的补充说明，可空")),
                List.of("reason"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        String reason = args.get("reason") == null ? null : String.valueOf(args.get("reason")).trim();
        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("缺少转人工原因");
        }
        String summary = escalationService.buildSummaryFromConversation(ctx.getConversationId());
        String extra = args.get("summary") == null ? null : String.valueOf(args.get("summary"));
        if (extra != null && !extra.isBlank()) {
            summary = (summary == null ? "" : summary + "\n") + "AI 补充：" + extra;
        }
        Map<String, Object> collected = new LinkedHashMap<>();
        if (ctx.getUserId() != null) {
            collected.put("userId", ctx.getUserId());
        }
        String collectedJson = null;
        try {
            collectedJson = objectMapper.writeValueAsString(collected);
        } catch (Exception ignored) {
        }
        Escalation e = escalationService.create(ctx.getShopId(), ctx.getConversationId(), reason, summary, collectedJson);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", e.getId());
        data.put("status", e.getStatus());
        data.put("message", "已生成转人工工单，店家会尽快联系顾客");
        return ToolResult.ok(data);
    }
}
