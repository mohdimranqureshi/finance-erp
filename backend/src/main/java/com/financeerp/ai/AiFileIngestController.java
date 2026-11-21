package com.financeerp.ai;

import com.financeerp.security.CompanyAccess;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an uploaded file into input for the existing bill and invoice extraction, so the same validation and review
 * queue apply. PDFs with selectable text, TXT and CSV are sent as text. When vision is enabled
 * (app.ai.vision-enabled), PNG/JPEG images and scanned PDFs are re-encoded to clean PNG page images and sent to the
 * vision model; otherwise they are refused.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiFileIngestController {
	private static final long MAX_BYTES = 1_000_000;
	private static final int MAX_PAGES = 20;
	private static final String ENABLE_HINT = "An administrator can enable image extraction with app.ai.vision-enabled=true "
			+ "(AI_VISION_ENABLED=true) and a vision-capable model.";
	private final CompanyAccess access;
	private final LlmClient llm;
	private final AiProposalController proposals;
	private final boolean visionEnabled;
	private final int maxImagePages;
	private final long maxImageBytes;

	public AiFileIngestController(CompanyAccess access, LlmClient llm, AiProposalController proposals,
			@Value("${app.ai.vision-enabled:${AI_VISION_ENABLED:false}}") boolean visionEnabled,
			@Value("${app.ai.max-image-pages:${AI_MAX_IMAGE_PAGES:3}}") int maxImagePages,
			@Value("${app.ai.max-image-bytes:${AI_MAX_IMAGE_BYTES:5MB}}") DataSize maxImageBytes) {
		this.access = access;
		this.llm = llm;
		this.proposals = proposals;
		this.visionEnabled = visionEnabled;
		this.maxImagePages = Math.max(1, Math.min(maxImagePages, MAX_PAGES));
		this.maxImageBytes = Math.max(1, maxImageBytes.toBytes());
	}

	@PostMapping(value = "/extract-bill-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> extractBillFile(@PathVariable long companyId, @RequestParam("file") MultipartFile file) {
		return extract(companyId, file, false);
	}

	@PostMapping(value = "/extract-invoice-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> extractInvoiceFile(@PathVariable long companyId, @RequestParam("file") MultipartFile file) {
		return extract(companyId, file, true);
	}

	private Map<String, Object> extract(long companyId, MultipartFile file, boolean sales) {
		access.requireRole(companyId, Set.of("OWNER", "ACCOUNTANT"));
		if (!llm.available())
			throw new AiUnavailableException("AI is not enabled. Set app.ai.enabled=true and configure the provider (see README).");
		if (file.isEmpty())
			throw new IllegalArgumentException("The file is empty.");
		long limit = visionEnabled ? Math.max(MAX_BYTES, maxImageBytes) : MAX_BYTES;
		if (file.getSize() > limit)
			throw new IllegalArgumentException("The file is larger than " + describe(limit) + ".");
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		String lower = name.toLowerCase(Locale.ROOT);
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw new IllegalArgumentException("The file could not be read.");
		}
		DocumentImages.Kind kind = DocumentImages.sniff(bytes);
		AiProposalController.ExtractionSource source;
		if (lower.endsWith(".txt") || lower.endsWith(".csv")) {
			if (bytes.length > MAX_BYTES)
				throw new IllegalArgumentException("The file is larger than 1 MB.");
			source = textSource(plainText(bytes));
		} else if (lower.endsWith(".pdf") || kind == DocumentImages.Kind.PDF)
			source = pdfSource(bytes);
		else if (kind.isImage() || lower.matches(".*\\.(png|jpe?g|jfif|gif|webp|bmp|tiff?|heic|heif|avif|svg)$"))
			source = imageSource(bytes, kind);
		else
			throw new IllegalArgumentException(visionEnabled ? "Unsupported file type. Upload a PDF, PNG, JPEG, TXT or CSV file."
					: "Unsupported file type. Upload a PDF, TXT or CSV file.");
		Map<String, Object> out = new LinkedHashMap<>(
				sales ? proposals.extractInvoice(companyId, source) : proposals.extractBill(companyId, source));
		out.put("sourceFile", safeName(name));
		out.put("inputMode", source.images().isEmpty() ? "TEXT" : "VISION");
		return out;
	}

	private static AiProposalController.ExtractionSource textSource(String text) {
		text = text.strip();
		if (text.isEmpty())
			throw new IllegalArgumentException("No text was found in the file.");
		return AiProposalController.ExtractionSource.ofText(text);
	}

	private AiProposalController.ExtractionSource imageSource(byte[] bytes, DocumentImages.Kind kind) {
		if (!visionEnabled)
			throw new IllegalArgumentException(
					"Images cannot be read because image extraction is turned off. Upload a PDF with selectable text, or paste the text. "
							+ ENABLE_HINT);
		if (kind != DocumentImages.Kind.PNG && kind != DocumentImages.Kind.JPEG)
			throw new IllegalArgumentException("Only PNG and JPEG images are supported"
					+ (kind == DocumentImages.Kind.UNKNOWN ? "; the file content is not a recognised image."
							: "; this file is " + kind + ". Convert it to PNG or JPEG, or upload a PDF."));
		if (bytes.length > maxImageBytes)
			throw new IllegalArgumentException("The image is larger than " + describe(maxImageBytes) + ".");
		byte[] png = DocumentImages.toSafePng(bytes, DocumentImages.MAX_SIDE, DocumentImages.MAX_PIXELS);
		return AiProposalController.ExtractionSource.ofImages(List.of(png), bytes, List.of());
	}

	private AiProposalController.ExtractionSource pdfSource(byte[] bytes) {
		if (DocumentImages.sniff(bytes) != DocumentImages.Kind.PDF)
			throw new IllegalArgumentException("The file is not a valid PDF.");
		try (PDDocument doc = Loader.loadPDF(bytes)) {
			int pages = doc.getNumberOfPages();
			if (pages > MAX_PAGES)
				throw new IllegalArgumentException("The PDF has more than " + MAX_PAGES + " pages.");
			String text = new PDFTextStripper().getText(doc).strip();
			if (!text.isEmpty()) {
				if (bytes.length > MAX_BYTES && !visionEnabled)
					throw new IllegalArgumentException("The file is larger than 1 MB.");
				return AiProposalController.ExtractionSource.ofText(text);
			}
			if (!visionEnabled)
				throw new IllegalArgumentException(
						"No text was found in the file. A scanned PDF is an image and image extraction is turned off. "
								+ ENABLE_HINT);
			if (bytes.length > maxImageBytes)
				throw new IllegalArgumentException("The scanned PDF is larger than " + describe(maxImageBytes) + ".");
			List<byte[]> images = DocumentImages.renderPdfPages(doc, maxImagePages, DocumentImages.MAX_SIDE,
					DocumentImages.MAX_PIXELS);
			List<String> notes = new ArrayList<>();
			notes.add("The PDF had no selectable text, so its pages were read as images.");
			if (pages > maxImagePages)
				notes.add("Only the first " + maxImagePages + " of " + pages
						+ " pages were read. Check the remaining pages manually.");
			return AiProposalController.ExtractionSource.ofImages(images, bytes, notes);
		} catch (IOException e) {
			throw new IllegalArgumentException("The PDF could not be read. It may be damaged or password-protected.");
		}
	}

	private String plainText(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("The text file must be UTF-8.");
		}
	}

	private static String describe(long bytes) {
		if (bytes % (1024 * 1024) == 0)
			return bytes / (1024 * 1024) + " MB";
		if (bytes % 1_000_000 == 0)
			return bytes / 1_000_000 + " MB";
		return bytes + " bytes";
	}

	private static String safeName(String name) {
		String base = name.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
		return base.length() > 100 ? base.substring(0, 100) : base;
	}
}
