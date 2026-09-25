package com.shopbooking.agent.tools;

import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.Tool;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.service.ServiceItemService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.shopbooking.agent.Tool.objectSchema;

/** 查询可预约的服务/桌型（只读） */
@Component
public class QueryServiceItemsTool implements Tool {

    private final ServiceItemService itemService;

    public QueryServiceItemsTool(ServiceItemService itemService) {
        this.itemService = itemService;
    }

    @Override
    public String name() {
        return "query_service_items";
    }

    @Override
    public String description() {
        return "查询本店所有可预约的服务或桌型，包括名称、容纳人数、单次时长和价格。顾客询问有什么服务、价格、能坐几人时调用。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(), List.of());
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        List<Map<String, Object>> items = new ArrayList<>();
        itemService.listActive(ctx.getShopId()).forEach(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("serviceItemId", item.getId());
            m.put("name", item.getName());
            m.put("capacityPerUnit", item.getCapacityPerUnit());
            m.put("durationMinutes", item.getDurationMinutes());
            m.put("price", item.getPrice() == null ? null : item.getPrice().toPlainString());
            m.put("cancelPolicy", item.getCancelPolicy());
            items.add(m);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        if (items.isEmpty()) {
            data.put("hint", "本店尚未配置服务项目，请建议顾客直接联系店家");
        }
        return ToolResult.ok(data);
    }
}
