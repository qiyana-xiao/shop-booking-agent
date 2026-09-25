package com.shopbooking.dto;

import java.util.List;
import java.util.Map;

/**
 * 4 步开店向导请求：店铺信息 → 营业时间 → 服务项（自定义或行业模板）→ 确认生成档期。
 * 一次提交全部数据，服务端幂等处理（向导可重复提交修正）。
 */
public record SetupWizardRequest(
        String name,
        String address,
        String phone,
        Map<String, HoursBlock> hours,
        Integer slotGranularityMinutes,
        String industry,
        List<Map<String, Object>> serviceItems,
        boolean useTemplates) {

    /** 单日营业时间：MONDAY..SUNDAY → {open, close, closed} */
    public record HoursBlock(String open, String close, boolean closed) {
    }
}
