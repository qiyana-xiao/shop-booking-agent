package com.shopbooking.common;

/**
 * 对话窗按纯文本渲染，模型输出的 Markdown 符号（**加粗**、# 标题、` 反引号）
 * 会原样显示给顾客，观感很差。AI 回复在落库/返回前统一过这里剥掉格式符号，只留文字。
 */
public final class MarkdownCleaner {

    private MarkdownCleaner() {
    }

    public static String strip(String text) {
        if (text == null || text.isEmpty() || text.indexOf('*') < 0
                && text.indexOf('#') < 0 && text.indexOf('`') < 0 && text.indexOf("__") < 0) {
            return text;
        }
        String s = text;
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");        // **加粗**
        s = s.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1"); // *斜体*
        s = s.replaceAll("__(.+?)__", "$1");                // __下划线加粗__
        s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");     // 行首标题井号
        s = s.replace("`", "");                              // 行内代码反引号
        s = s.replaceAll("(?m)^(\\s*)[*+]\\s+", "$1· ");     // * / + 列表项改圆点
        return s;
    }
}
