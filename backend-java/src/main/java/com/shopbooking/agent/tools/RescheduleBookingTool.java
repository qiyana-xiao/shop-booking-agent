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
import static com.shopbooking.agent.Tool.stringProp;

/** 改期（写）：换新时段，原子释放旧时段 */
@Component
public class RescheduleBookingTool implements Tool {

    private final BookingService bookingService;

    public RescheduleBookingTool(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public String name() {
        return "reschedule_booking";
    }

    @Override
    public String description() {
        return "把顾客的预约改到新时段（同一事务内：占新时段并原子释放旧时段，失败自动回滚）。"
                + "newSlotId 必须先用 check_availability 确认有余量。改期成功后请告知顾客新时间，并说明原时段已释放。";
    }

    @Override
    public boolean sideEffect() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "bookingNo", stringProp("预约单号"),
                "newSlotId", intProp("新档期 ID（check_availability 返回的 slotId）")),
                List.of("bookingNo", "newSlotId"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        String bookingNo = requireStr(args.get("bookingNo"), "bookingNo");
        Long newSlotId = requireLong(args.get("newSlotId"), "newSlotId");
        Map<String, Object> booking = bookingService.reschedule(bookingNo, newSlotId,
                ctx.getSessionKey(), ctx.getUserId(), null);
        return ToolResult.ok(booking);
    }

    private String requireStr(Object v, String field) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw BusinessException.badRequest("缺少参数：" + field);
        }
        return String.valueOf(v).trim();
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
