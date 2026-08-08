package com.mac.scheduler.entities.dto;

import com.mac.scheduler.entities.constant.GroupExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateTaskGroupRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull GroupExecutionMode executionMode,
        @NotEmpty @Size(max = 100) List<@NotNull UUID> taskIds,
        Boolean enabled) {}
