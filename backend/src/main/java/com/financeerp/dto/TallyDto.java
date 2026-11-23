package com.financeerp.dto;

public final class TallyDto {
	private TallyDto() {
	}

	/**
	 * A Tally import upload reduced to plain values: the file, the "mapping" form field and the optional "mapping"
	 * file part (null when absent).
	 */
	public record ImportUpload(String fileName, byte[] content, String mappingField, byte[] mappingPart) {
	}
}
