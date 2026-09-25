package com.shopbooking.agent.tools;

import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.Tool;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.common.BusinessException;
import com.shopbooking.service.SlotService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.shopbooking.agent.Tool.intProp;
import static com.shopbooking.agent.Tool.objectSchema;
import static com.shopbooking.agent.Tool.stringProp;

/** 按日期+时段+人数查剩余档期（只读，答案每分钟都在变，必须真的去查） */
@Component
public class CheckAvailabilityTool implements Tool {

    private final SlotService slotService;

    public CheckAvailabilityTool(SlotService slotService) {
        this.slotService = slotService;
    }

    @Override
    public String name() {
        return "check_availability";
    }

    @Override
    public String description() {
        return "查询某天的可预约档期。date 必填（YYYY-MM-DD 格式，今天是 "
                + LocalDate.now() + "）；partySize 顾客人数；serviceItemId 已确定的服务类型；startTime 最早可接受的时间（HH:mm）。"
                + "返回各时段剩余数量。注意：结果会随其他顾客预约实时变化，回答档期问题前必须调用本工具，禁止凭记忆回答。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "date", stringProp("查询日期，YYYY-MM-DD"),
                "partySize", intProp("顾客人数"),
                "serviceItemId", intProp("服务项目 ID（可选）"),
                "startTime", stringProp("最早可接受开始时间 HH:mm（可选）")),
                List.of("date"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        LocalDate date = parseDate(str(args.get("date")));
        if (date.isBefore(LocalDate.now())) {
            throw BusinessException.badRequest("查询日期不能是过去");
        }
        Integer partySize = args.get("partySize") == null ? null : Integer.parseInt(String.valueOf(args.get("partySize")));
        Long serviceItemId = args.get("serviceItemId") == null ? null : Long.parseLong(String.valueOf(args.get("serviceItemId")));
        Map<String, Object> result = slotService.checkAvailability(ctx.getShopId(), date,
                str(args.get("startTime")), partySize, serviceItemId);
        return ToolResult.ok(result);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            throw BusinessException.badRequest("缺少查询日期");
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw BusinessException.badRequest("日期格式应为 YYYY-MM-DD，收到：" + s);
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
