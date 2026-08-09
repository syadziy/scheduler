package com.mac.scheduler.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ControllerAuthorizationTest {

    @Test
    void managementEndpointsDeclareTheirPermissionOnTheController() {
        assertPermission(SchedulerManagementController.class, "createTask", "PERM_scheduler:manage");
        assertPermission(SchedulerManagementController.class, "createTaskGroup", "PERM_scheduler:manage");
        assertPermission(SchedulerManagementController.class, "createSchedule", "PERM_scheduler:manage");
    }

    @Test
    void historyEndpointDeclaresItsPermissionOnTheController() {
        assertPermission(TaskHistoryController.class, "findHistory", "PERM_scheduler:read");
    }

    private static void assertPermission(Class<?> controller, String methodName, String permission) {
        PreAuthorize annotation = method(controller, methodName).getAnnotation(PreAuthorize.class);
        assertEquals("hasAuthority('" + permission + "')", annotation.value());
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
