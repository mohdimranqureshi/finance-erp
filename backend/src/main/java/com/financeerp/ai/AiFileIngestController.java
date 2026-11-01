package com.financeerp.ai;

import com.financeerp.security.CompanyAccess;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns an uploaded PDF or text file into text and hands it to the existing bill extraction, so the same validation
 * and review queue apply. Images and scanned PDFs are refused: the configured model is text-only and no OCR is bundled.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiFileIngestController {
	private static final long MAX_BYTES = 1_000_000;
	private static final int MAX_PAGES = 20;
	private final CompanyAccess access;
	private final LlmClient llm;
	private final AiProposalController proposals;

	public AiFileIngestController(CompanyAccess access, LlmClient llm, AiProposalController proposals) {
		this.access = access;
		this.llm = llm;
		this.proposals = proposals;
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
		if (file.getSize() > MAX_BYTES)
			throw new IllegalArgumentException("The file is larger than 1 MB.");
		String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		String lower = name.toLowerCase(Locale.ROOT);
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw new IllegalArgumentException("The file could not be read.");
		}
		String text;
		if (lower.endsWith(".pdf"))
			text = pdfText(bytes);
		else if (lower.endsWith(".txt") || lower.endsWith(".csv"))
			text = plainText(bytes);
		else if (lower.matches(".*\\.(png|jpe?g|gif|webp|bmp|tiff?)$"))
			throw new IllegalArgumentException(
					"Images cannot be read: the AI model is text-only and OCR is not installed. Upload a PDF with selectable text, or paste the text.");
		else
			throw new IllegalArgumentException("Unsupported file type. Upload a PDF, TXT or CSV file.");
		text = text.strip();
		if (text.isEmpty())
			throw new IllegalArgumentException(
					"No text was found in the file. A scanned PDF is an image and needs OCR, which is not available.");
		AiProposalController.ExtractRequest req = new AiProposalController.ExtractRequest(text);
		Map<String, Object> out = new LinkedHashMap<>(
				sales ? proposals.extractInvoice(companyId, req) : proposals.extractBill(companyId, req));
		out.put("sourceFile", safeName(name));
		return out;
	}

	private String pdfText(byte[] bytes) {
		if (bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)))
			throw new IllegalArgumentException("The file is not a valid PDF.");
		try (PDDocument doc = Loader.loadPDF(bytes)) {
			if (doc.getNumberOfPages() > MAX_PAGES)
				throw new IllegalArgumentException("The PDF has more than " + MAX_PAGES + " pages.");
			return new PDFTextStripper().getText(doc);
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

	private static String safeName(String name) {
		String base = name.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
		return base.length() > 100 ? base.substring(0, 100) : base;
	}
}
