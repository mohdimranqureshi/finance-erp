package com.financeerp.controller;

import com.financeerp.dto.TallyDto;
import com.financeerp.service.TallyService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * Tally XML interchange. Import creates missing accounts and parties and one DRAFT journal per balanced voucher (never
 * approved or posted here); export writes POSTED journals as Tally "Journal" vouchers.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/tally")
public class TallyController {
	private final TallyService service;

	public TallyController(TallyService service) {
		this.service = service;
	}

	@PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> preview(@PathVariable long companyId, @RequestParam("file") MultipartFile file,
			MultipartHttpServletRequest request) {
		return service.preview(companyId, upload(file, request));
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> importFile(@PathVariable long companyId, @RequestParam("file") MultipartFile file,
			MultipartHttpServletRequest request) {
		return service.importFile(companyId, upload(file, request));
	}

	@GetMapping("/export")
	public ResponseEntity<String> export(@PathVariable long companyId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		String xml = service.export(companyId, from, to);
		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("tally-vouchers-" + from + "-to-" + to + ".xml").build()
								.toString())
				.body(xml);
	}

	/** Reduces the multipart request to plain values; the service validates the file and the mapping. */
	private static TallyDto.ImportUpload upload(MultipartFile file, MultipartHttpServletRequest request) {
		byte[] content;
		try {
			content = file == null || file.isEmpty() ? new byte[0] : file.getBytes();
		} catch (IOException e) {
			throw new IllegalArgumentException("The file could not be read.");
		}
		String field = request.getParameter("mapping");
		MultipartFile part = request.getFile("mapping");
		byte[] mapping = null;
		if ((field == null || field.isBlank()) && part != null && !part.isEmpty()) {
			try {
				mapping = part.getBytes();
			} catch (IOException e) {
				throw new IllegalArgumentException("The mapping could not be read.");
			}
		}
		return new TallyDto.ImportUpload(file == null ? null : file.getOriginalFilename(), content, field, mapping);
	}
}
