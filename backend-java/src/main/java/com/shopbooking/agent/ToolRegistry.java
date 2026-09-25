package com.shopbooking.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.AgentStep;
import com.shopbooking.service.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：8 个工具的唯一执行入口。
 * 模型只输出"我要调什么工具、参数是什么"的结构化文本，执行永远发生在 JVM 里；
 * 写工具先过幂等键校验，所有调用的入参在服务端二次校验。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    public ToolRegistry(List<Tool> toolList, IdempotencyService idempotency,
                        ObjectMapper objectMapper, AppProperties props) {
        for (Tool tool : toolList) {
            tools.put(tool.name(), tool);
        }
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** tools[] 载荷（strict 模式） */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            defs.add(tool.definition(props.getDeepseek().isStrictTools()));
        }
        return defs;
    }

    public ToolResult invoke(String name, Map<String, Object> args, AgentContext ctx, int turn) {
        long start = System.currentTimeMillis();
        Tool tool = tools.get(name);
        if (tool == null) {
            return record(ctx, turn, name, args, ToolResult.fail("未知工具：" + name), 0, false);
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        String idemKey = null;
        if (tool.sideEffect()) {
            idemKey = idempotency.buildKey(ctx.getSessionKey(), turn, name, safeArgs);
            Optional<String> cached = idempotency.tryGet(idemKey);
            if (cached.isPresent()) {
                try {
                    Map<String, Object> data = objectMapper.readValue(cached.get(),
                            objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                    ToolResult result = ToolResult.restore(data);
                    return record(ctx, turn, name, safeArgs, result, elapsed(start), true);
                } catch (Exception e) {
                    log.warn("幂等缓存解析失败，按未命中处理：{}", e.getMessage());
                }
            }
            Boolean acquired = idempotency.tryBegin(idemKey);
            if (Boolean.FALSE.equals(acquired)) {
                return record(ctx, turn, name, safeArgs,
                        ToolResult.fail("相同操作正在处理中，请勿重复提交，请基于已有结果继续回复顾客"), elapsed(start), true);
            }
        }

        ToolResult result;
        try {
            result = tool.execute(safeArgs, ctx);
        } catch (DuplicateKeyException e) {
            idempotency.warnDuplicateBlocked(name + " " + safeArgs);
            result = ToolResult.fail("该操作已生效，请勿重复提交，请基于已有结果继续回复顾客");
        } catch (com.shopbooking.common.BusinessException e) {
            result = ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            log.error("工具 {} 执行异常", name, e);
            result = ToolResult.fail("工具执行出错：" + e.getMessage());
        }
        if (idemKey != null && result.isSuccess()) {
            try {
                idempotency.save(idemKey, objectMapper.writeValueAsString(result.getData()));
            } catch (Exception ignored) {
            }
        }
        return record(ctx, turn, name, safeArgs, result, elapsed(start), false);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    @SuppressWarnings("unchecked")
    private ToolResult record(AgentContext ctx, int turn, String name, Map<String, Object> args,
                              ToolResult result, long durationMs, boolean hitIdempotent) {
        AgentStep step = new AgentStep();
        step.setConversationId(ctx.getConversationId());
        step.setTurn(turn);
        step.setToolName(name);
        try {
            step.setArgs(objectMapper.writeValueAsString(args));
            step.setResult(objectMapper.writeValueAsString(result.getData()));
        } catch (Exception ignored) {
        }
        step.setDurationMs((int) durationMs);
        step.setHitIdempotent(hitIdempotent);
        step.setSuccess(result.isSuccess());
        ctx.addStep(step);
        return result;
    }
}
