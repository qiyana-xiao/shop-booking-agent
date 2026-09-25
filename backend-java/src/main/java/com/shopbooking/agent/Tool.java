package com.shopbooking.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 智能体可调用的工具契约：名称、参数 schema、是否带副作用、执行 */
public interface Tool {

    String name();

    String description();

    /** 写工具标注 true：执行前过幂等键校验 */
    default boolean sideEffect() {
        return false;
    }

    /** DeepSeek tools[].function.parameters 的 JSON Schema（strict 模式：所有字段进 required） */
    Map<String, Object> parametersSchema();

    ToolResult execute(Map<String, Object> args, AgentContext ctx);

    /** 拼装 tools[] 元素 */
    default Map<String, Object> definition(boolean strict) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        function.put("description", description());
        function.put("parameters", parametersSchema());
        if (strict) {
            function.put("strict", true);
        }
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "function");
        def.put("function", function);
        return def;
    }

    /** 便捷构造 object schema */
    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }
}
