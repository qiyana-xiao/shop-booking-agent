package com.shopbooking.service;

import com.shopbooking.security.SecurityUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 老板/店员写操作审计：只写操作类型、目标和脱敏摘要，不写完整消息正文。 */
@Service
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    public void audit(SecurityUser actor, String action, String target, String detail) {
        AUDIT.info("[{}] user={}({}) target={} detail={}",
                action,
                actor == null ? "anonymous" : actor.getUsername(),
                actor == null ? "-" : actor.getRole(),
                target,
                detail == null ? "-" : detail);
    }
}
