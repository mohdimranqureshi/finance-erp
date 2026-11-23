package com.financeerp.dto;

import java.io.IOException;

public final class AiFileIngestDto {
	private AiFileIngestDto() {
	}

	/** Lazily reads an uploaded file so the service can authorize and size-check before touching the content. */
	@FunctionalInterface
	public interface FileContent {
		byte[] read() throws IOException;
	}
}
