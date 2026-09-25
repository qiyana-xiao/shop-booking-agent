package com.shopbooking.agent.tools;

import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.Tool;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.common.BusinessException;
import com.shopbooking.service.BookingService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.shopbooking.agent.Tool.objectSchema;
import static com.shopbooking.agent.Tool.stringProp;

/** 取消预约（写）：释放时段并记录原因，库存实时回补 */
@Component
public class CancelBookingTool implements Tool {

    private final BookingService bookingService;

    public CancelBookingTool(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public String name() {
        return "cancel_booking";
    }

    @Override
    public String description() {
        return "取消顾客的预约并释放时段（库存实时回补）。顾客明确表示不来了、要取消时调用。取消后请礼貌确认，并询问是否需要约别的时间。";
    }

    @Override
    public boolean sideEffect() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "bookingNo", stringProp("预约单号"),
                "reason", stringProp("取消原因（可空）")),
                List.of("bookingNo"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        String bookingNo = requireStr(args.get("bookingNo"), "bookingNo");
        String reason = args.get("reason") == null ? null : String.valueOf(args.get("reason"));
        Map<String, Object> booking = bookingService.cancel(bookingNo, reason,
                ctx.getSessionKey(), ctx.getUserId(), null);
        return ToolResult.ok(booking);
    }

    private String requireStr(Object v, String field) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw BusinessException.badRequest("缺少参数：" + field);
        }
        return String.valueOf(v).trim();
    }
}
