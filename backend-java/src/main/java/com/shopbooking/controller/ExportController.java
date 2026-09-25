package com.shopbooking.controller;

import com.shopbooking.entity.Shop;
import com.shopbooking.mapper.BookingMapper;
import com.shopbooking.security.SecurityUser;
import com.shopbooking.service.AuditService;
import com.shopbooking.service.KnowledgeService;
import com.shopbooking.service.ServiceItemService;
import com.shopbooking.service.ShopService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据导出（仅老板）：预约 CSV（带 BOM，Excel 直开）与配置 JSON 快照，数据能带走 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final BookingMapper bookingMapper;
    private final ShopService shopService;
    private final ServiceItemService itemService;
    private final KnowledgeService knowledgeService;
    private final AuditService auditService;

    public ExportController(BookingMapper bookingMapper, ShopService shopService,
                            ServiceItemService itemService, KnowledgeService knowledgeService,
                            AuditService auditService) {
        this.bookingMapper = bookingMapper;
        this.shopService = shopService;
        this.itemService = itemService;
        this.knowledgeService = knowledgeService;
        this.auditService = auditService;
    }

    @GetMapping("/bookings.csv")
    public ResponseEntity<byte[]> bookings(@RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        Shop shop = shopService.requirePrimaryShop();
        LocalDate fromDate = from == null || from.isBlank() ? LocalDate.now().minusDays(90) : LocalDate.parse(from);
        LocalDate toDate = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        if (toDate.isBefore(fromDate)) {
            throw com.shopbooking.common.BusinessException.badRequest("结束日期不能早于开始日期");
        }

        List<Map<String, Object>> rows = bookingMapper.listExportRows(shop.getId(), fromDate, toDate);
        StringBuilder csv = new StringBuilder();
        csv.append("预约单号,状态,日期,开始时间,服务,人数,顾客姓名,手机号,备注,取消原因,创建时间\n");
        for (Map<String, Object> row : rows) {
            csv.append(csvCell(row.get("bookingNo"))).append(',')
                    .append(csvCell(row.get("status"))).append(',')
                    .append(csvCell(row.get("slotDate"))).append(',')
                    .append(csvCell(row.get("startTime"))).append(',')
                    .append(csvCell(row.get("serviceName"))).append(',')
                    .append(csvCell(row.get("partySize"))).append(',')
                    .append(csvCell(row.get("customerName"))).append(',')
                    .append(csvCell(row.get("customerPhone"))).append(',')
                    .append(csvCell(row.get("remark"))).append(',')
                    .append(csvCell(row.get("cancelReason"))).append(',')
                    .append(csvCell(row.get("createdAt")))
                    .append('\n');
        }
        auditService.audit(currentUser(), "EXPORT_BOOKINGS", "shop:" + shop.getId(),
                fromDate + "~" + toDate + "，" + rows.size() + " 条");

        byte[] body = ('\ufeff' + csv.toString()).getBytes(StandardCharsets.UTF_8);
        String filename = "bookings-" + fromDate + "-" + toDate + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    /** 配置快照：店铺 + 服务项 + 知识库，换机/备份可带走 */
    @GetMapping("/config.json")
    public ResponseEntity<byte[]> config() {
        Shop shop = shopService.requirePrimaryShop();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("exportedAt", java.time.LocalDateTime.now().toString());
        snapshot.put("shop", Map.of(
                "name", shop.getName() == null ? "" : shop.getName(),
                "address", shop.getAddress() == null ? "" : shop.getAddress(),
                "phone", shop.getPhone() == null ? "" : shop.getPhone(),
                "openHours", shop.getOpenHours() == null ? "" : shop.getOpenHours(),
                "slotGranularityMinutes", shop.getSlotGranularityMinutes() == null ? 60 : shop.getSlotGranularityMinutes()));
        snapshot.put("serviceItems", itemService.listAll(shop.getId()));
        snapshot.put("knowledge", knowledgeService.list(shop.getId(), null, null));

        auditService.audit(currentUser(), "EXPORT_CONFIG", "shop:" + shop.getId(), null);

        String json = toJson(snapshot);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shop-config.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }

    private String csvCell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SecurityUser currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SecurityUser user ? user : null;
    }
}
