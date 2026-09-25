package com.shopbooking.agent.tools;

import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.Tool;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.common.BusinessException;
import com.shopbooking.service.BookingService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.shopbooking.agent.Tool.intProp;
import static com.shopbooking.agent.Tool.objectSchema;

/** 锁座（写）：占用档期 15 分钟，返回预约单号。数据库乐观锁 + 唯一索引保证不超卖 */
@Component
public class HoldSlotTool implements Tool {

    private final BookingService bookingService;

    public HoldSlotTool(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public String name() {
        return "hold_slot";
    }

    @Override
    public String description() {
        return "为顾客锁定档期 15 分钟（锁座）。必须在 check_availability 确认有余量之后调用。"
                + "slotId 使用 check_availability 返回的 slotId。锁座成功后请告知顾客已预留并尽快收集姓名和手机号完成确认。"
                + "如果返回错误（如 SLOT_FULL），请改用 check_availability 推荐邻近时段。";
    }

    @Override
    public boolean sideEffect() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "slotId", intProp("档期 ID（check_availability 返回的 slotId）"),
                "partySize", intProp("顾客人数")),
                List.of("slotId"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        Long slotId = requireLong(args.get("slotId"), "slotId");
        Integer partySize = args.get("partySize") == null ? null
                : Integer.parseInt(String.valueOf(args.get("partySize")));
        Map<String, Object> booking = bookingService.hold(slotId, partySize, ctx.getSessionKey(), ctx.getUserId());
        return ToolResult.ok(booking);
    }

    private Long requireLong(Object v, String field) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw BusinessException.badRequest("缺少参数：" + field);
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw BusinessException.badRequest(field + " 格式不正确");
        }
    }
}
