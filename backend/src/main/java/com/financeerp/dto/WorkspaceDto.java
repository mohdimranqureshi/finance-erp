package com.financeerp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class WorkspaceDto {
	public static final int MAX_BULK = 100;

	private WorkspaceDto() {
	}

	public record BulkItem(@NotNull Long companyId, @NotNull Long journalId) {
	}

	public record BulkApproveRequest(@NotEmpty @Size(max = MAX_BULK) List<@Valid @NotNull BulkItem> items) {
	}

	public record BulkResult(long companyId, long journalId, boolean ok, String error) {
	}

	public record ClientSettingsRequest(Boolean pinned, @Size(max = 80) String label) {
	}
}
