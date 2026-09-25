package com.shopbooking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopbooking.common.BusinessException;
import com.shopbooking.entity.KnowledgeItem;
import com.shopbooking.mapper.KnowledgeItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 店铺 FAQ：关键词优先匹配，命中率低时走分类兜底；三种录入（模板/手动/工单回流） */
@Service
public class KnowledgeService {

    private final KnowledgeItemMapper knowledgeMapper;

    public KnowledgeService(KnowledgeItemMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    public List<KnowledgeItem> list(Long shopId, String keyword, String status) {
        LambdaQueryWrapper<KnowledgeItem> q = new LambdaQueryWrapper<KnowledgeItem>()
                .eq(KnowledgeItem::getShopId, shopId)
                .orderByDesc(KnowledgeItem::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(KnowledgeItem::getQuestion, keyword).or().like(KnowledgeItem::getKeywords, keyword));
        }
        if (status != null && !status.isBlank()) {
            q.eq(KnowledgeItem::getStatus, status);
        }
        return knowledgeMapper.selectList(q);
    }

    /** 关键词检索（Agent 工具用）：按 分词命中数 打分，命中即累加 hit_count */
    @Transactional
    public List<Map<String, Object>> search(Long shopId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<KnowledgeItem> items = knowledgeMapper.selectList(new LambdaQueryWrapper<KnowledgeItem>()
                .eq(KnowledgeItem::getShopId, shopId)
                .eq(KnowledgeItem::getStatus, "ON"));
        List<String> tokens = tokenize(query);
        List<Map<String, Object>> matched = new ArrayList<>();
        for (KnowledgeItem item : items) {
            int score = score(item, tokens);
            if (score > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("question", item.getQuestion());
                m.put("answer", item.getAnswer());
                m.put("score", score);
                matched.add(m);
                item.setHitCount(item.getHitCount() == null ? 1 : item.getHitCount() + 1);
                knowledgeMapper.updateById(item);
            }
        }
        matched.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        return matched.size() > 3 ? matched.subList(0, 3) : matched;
    }

    private int score(KnowledgeItem item, List<String> tokens) {
        String question = item.getQuestion() == null ? "" : item.getQuestion();
        String keywords = item.getKeywords() == null ? "" : item.getKeywords();
        String answer = item.getAnswer() == null ? "" : item.getAnswer();
        int score = 0;
        for (String token : tokens) {
            if (keywords.contains(token)) {
                score += 3;
            } else if (question.contains(token)) {
                score += 2;
            } else if (answer.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        for (String word : query.split("[\\s，。？！,.?!、；;：:（）()\"']+")) {
            if (word.length() >= 2) {
                tokens.add(word);
            }
        }
        // 中文无空格分词兜底：滑窗 2-gram
        if (tokens.size() <= 1) {
            String compact = query.replaceAll("[\\s，。？！,.?!、；;：:（）()\"']+", "");
            for (int i = 0; i + 2 <= compact.length(); i++) {
                tokens.add(compact.substring(i, i + 2));
            }
        }
        return tokens;
    }

    @Transactional
    public KnowledgeItem create(Long shopId, Map<String, Object> body) {
        String question = str(body.get("question"));
        String answer = str(body.get("answer"));
        if (question == null || question.isBlank() || question.length() > 200) {
            throw BusinessException.badRequest("问题必填（200 字以内）");
        }
        if (answer == null || answer.isBlank()) {
            throw BusinessException.badRequest("答案必填");
        }
        KnowledgeItem item = new KnowledgeItem();
        item.setShopId(shopId);
        item.setQuestion(question.trim());
        item.setAnswer(answer.trim());
        item.setKeywords(str(body.get("keywords")));
        item.setCategory(str(body.get("category")));
        item.setHitCount(0);
        item.setStatus("ON");
        item.setSource(body.get("source") == null ? "MANUAL" : String.valueOf(body.get("source")));
        knowledgeMapper.insert(item);
        return item;
    }

    @Transactional
    public KnowledgeItem update(Long id, Map<String, Object> body) {
        KnowledgeItem item = require(id);
        if (body.containsKey("question")) {
            item.setQuestion(str(body.get("question")));
        }
        if (body.containsKey("answer")) {
            item.setAnswer(str(body.get("answer")));
        }
        if (body.containsKey("keywords")) {
            item.setKeywords(str(body.get("keywords")));
        }
        if (body.containsKey("category")) {
            item.setCategory(str(body.get("category")));
        }
        if (body.containsKey("status")) {
            item.setStatus(String.valueOf(body.get("status")));
        }
        knowledgeMapper.updateById(item);
        return item;
    }

    @Transactional
    public void toggleStatus(Long id, String status) {
        KnowledgeItem item = require(id);
        item.setStatus("ON".equals(status) ? "ON" : "OFF");
        knowledgeMapper.updateById(item);
    }

    @Transactional
    public void delete(Long id) {
        knowledgeMapper.deleteById(id);
    }

    public KnowledgeItem require(Long id) {
        KnowledgeItem item = knowledgeMapper.selectById(id);
        if (item == null) {
            throw BusinessException.notFound("知识条目不存在");
        }
        return item;
    }

    @Transactional
    public void linkEscalation(Long knowledgeId, Long escalationId) {
        KnowledgeItem item = require(knowledgeId);
        item.setEscalationId(escalationId);
        knowledgeMapper.updateById(item);
    }

    /** 行业 FAQ 模板目录（勾选即入库） */
    public Map<String, List<Map<String, String>>> templates() {
        return Map.of(
                "restaurant", List.of(
                        Map.of("question", "你们营业时间是什么时候？", "answer", "我们每天 10:00-22:00 营业，最后下单时间 21:30。"),
                        Map.of("question", "门口可以停车吗？", "answer", "可以，店门口有免费停车位，高峰期可能需要等位。"),
                        Map.of("question", "可以带宠物吗？", "answer", "小型宠物可以带（需放入宠物包），大型宠物暂不接待，感谢理解。"),
                        Map.of("question", "店里有 WiFi 吗？", "answer", "有的，到店后扫码即可连接，密码在餐桌立牌上。"),
                        Map.of("question", "可以开发票吗？", "answer", "可以，结账后凭小票到前台开具电子发票。"),
                        Map.of("question", "包间有最低消费吗？", "answer", "包间按人数收取，无强制最低消费，详询店员。"),
                        Map.of("question", "可以自带酒水吗？", "answer", "可以自带酒水，不收取开瓶费。"),
                        Map.of("question", "有没有儿童座椅？", "answer", "有，需要的话到店告知服务员即可。")),
                "beauty", List.of(
                        Map.of("question", "你们营业时间是什么时候？", "answer", "我们每天 10:00-21:00 营业，建议提前预约。"),
                        Map.of("question", "做美甲大概需要多久？", "answer", "普通美甲约 60 分钟，款式复杂的约 90-120 分钟。"),
                        Map.of("question", "可以刷卡/扫码支付吗？", "answer", "支持微信、支付宝、银行卡，均可。"),
                        Map.of("question", "需要提前预约吗？", "answer", "周末和晚间建议提前 1-2 天预约，平日到店即可。"),
                        Map.of("question", "过敏体质可以做吗？", "answer", "请提前告知美甲师，我们会做皮肤测试后再服务。")),
                "housekeeping", List.of(
                        Map.of("question", "保洁服务包含哪些内容？", "answer", "包含厨房、卫生间、客厅、卧室的清洁整理，不含外墙和灯具拆洗。"),
                        Map.of("question", "需要自己准备清洁用品吗？", "answer", "不需要，阿姨会自带工具和环保清洁剂。"),
                        Map.of("question", "可以指定时间上门吗？", "answer", "可以，下单时备注期望时间，我们会尽量安排。"),
                        Map.of("question", "对保洁效果不满意怎么办？", "answer", "24 小时内联系客服，我们免费返工。")));
    }

    /** 模板批量导入（勾选的入库，答案可再改） */
    @Transactional
    public int importTemplates(Long shopId, String industry, List<Map<String, String>> selected) {
        int count = 0;
        for (Map<String, String> sel : selected) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", sel.get("question"));
            body.put("answer", sel.get("answer"));
            body.put("source", "TEMPLATE");
            body.put("category", industryName(industry));
            create(shopId, body);
            count++;
        }
        return count;
    }

    private String industryName(String industry) {
        return switch (industry == null ? "" : industry) {
            case "restaurant" -> "餐饮";
            case "beauty" -> "美业";
            case "housekeeping" -> "家政";
            default -> "通用";
        };
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
