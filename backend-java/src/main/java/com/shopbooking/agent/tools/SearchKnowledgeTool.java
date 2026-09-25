package com.shopbooking.agent.tools;

import com.shopbooking.agent.AgentContext;
import com.shopbooking.agent.Tool;
import com.shopbooking.agent.ToolResult;
import com.shopbooking.common.BusinessException;
import com.shopbooking.service.KnowledgeService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.shopbooking.agent.Tool.objectSchema;
import static com.shopbooking.agent.Tool.stringProp;

/** 查店铺 FAQ（只读）：停车、宠物、WiFi 等细节问题。查不到时按第三层策略接住顾客，不编造也不轻易转人工 */
@Component
public class SearchKnowledgeTool implements Tool {

    private final KnowledgeService knowledgeService;

    public SearchKnowledgeTool(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "搜索店铺知识库（常见问题）。仅用于店铺事实之外的细节问题：停车、宠物、WiFi、发票、儿童座椅、退改政策等。"
                + "注意：营业时间、地址、电话、服务项目与价格属于店铺事实，系统提示词里已经给出，直接回答即可，禁止调用本工具。"
                + "如果返回结果为空，说明知识库没有该问题的答案：禁止编造，但也不要转人工——按第三层策略如实告知顾客需要跟店里确认，"
                + "并主动提出帮顾客留言转达或请店家回电。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return objectSchema(Map.of(
                "query", stringProp("顾客的问题或关键词，如：能带宠物吗")),
                List.of("query"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, AgentContext ctx) {
        String query = args.get("query") == null ? null : String.valueOf(args.get("query")).trim();
        if (query == null || query.isBlank()) {
            throw BusinessException.badRequest("缺少查询关键词");
        }
        List<Map<String, Object>> matched = knowledgeService.search(ctx.getShopId(), query);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("matched", matched);
        data.put("total", matched.size());
        if (matched.isEmpty()) {
            data.put("hint", "知识库中没有找到相关内容。不要编造答案，也不要调用 escalate_to_human："
                    + "请坦诚告知顾客需要跟店里确认一下，并主动提出可以帮 TA 留言转达给店家，或请店家回电。");
        }
        return ToolResult.ok(data);
    }
}
