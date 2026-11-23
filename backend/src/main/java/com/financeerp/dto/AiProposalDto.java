package com.financeerp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

public final class AiProposalDto {
	private AiProposalDto() {
	}

	public record ExtractRequest(@NotBlank String text) {
	}

	/**
	 * What the model reads: plain text, or PNG page images from an uploaded file. Only the fingerprint (sha-256 and
	 * length of the original input) is stored; notes become proposal warnings.
	 */
	public record ExtractionSource(String text, List<byte[]> images, String sha256, int length, List<String> notes) {
		public static ExtractionSource ofText(String text) {
			return new ExtractionSource(text, List.of(), AiProposalDto.sha256(text.getBytes(StandardCharsets.UTF_8)),
					text.length(), List.of());
		}

		/** original is the uploaded file as received; its hash and byte length are the stored fingerprint. */
		public static ExtractionSource ofImages(List<byte[]> pngImages, byte[] original, List<String> notes) {
			if (pngImages == null || pngImages.isEmpty())
				throw new IllegalArgumentException("No page images were produced from the file.");
			return new ExtractionSource(null, List.copyOf(pngImages), AiProposalDto.sha256(original), original.length,
					notes == null ? List.of() : List.copyOf(notes));
		}

		public boolean isImage() {
			return !images.isEmpty();
		}

		public String noun() {
			return isImage() ? "image" : "text";
		}
	}

	public record JournalRequest(@NotBlank @Size(max = 1000) String instruction) {
	}

	/** billDate is also the invoice date for sales proposals; customerId resolves a missing customer; hsnSac fills bill lines without one. */
	public record AcceptRequest(Long vendorId, @Size(max = 50) String vendorInvoiceNumber, LocalDate billDate,
			Long customerId, @Pattern(regexp = "[0-9]{4}|[0-9]{6}|[0-9]{8}") String hsnSac) {
	}

	public static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
