package com.mac.scheduler.service;

import java.util.UUID;

public interface AuditEventPublisher {

    void publishCreated(String action, String resourceType, UUID resourceId);
}
