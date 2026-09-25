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

/** 确认预约（写）：凭预约单号 + 姓名 + 手机号转正式预约 */
@Component
public class ConfirmBookingTool implements Tool {

    private final BookingService bookingService;

    public ConfirmBookingTool(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public String name() {
        return "confirm_booking";
    }

    @Override
    public String description() {
        return "将锁座中的预约转为正式预约。需要 bookingNo（锁座时返回的预约单号）、顾客姓名和 11 位手机号。"
                + "确认成功后请用清晰的格式向顾客输出预约成功卡片（时间/人数/服务/备注），并说明如需改期取消直接回复消息即可。";
    }

    @Override
    public boolean sideEffect() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "bookingNo", stringProp("预约单号，如 SB20260915A1B2C3"),
                "customerName", stringProp("顾客姓名"),
                "customerPhone", stringProp("11 位手机号"),
                "remark", stringProp("备注（生日、忌口等特殊要求，可空）")),
                List.of("bookingNo", "customerName", "customerPhone"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        String bookingNo = requireStr(args.get("bookingNo"), "bookingNo");
        String name = requireStr(args.get("customerName"), "customerName");
        String phone = requireStr(args.get("customerPhone"), "customerPhone");
        String remark = args.get("remark") == null ? null : String.valueOf(args.get("remark"));
        Map<String, Object> booking = bookingService.confirm(bookingNo, name, phone, remark,
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
