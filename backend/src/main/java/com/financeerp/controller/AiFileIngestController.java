package com.financeerp.controller;

import com.financeerp.service.AiFileIngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/companies/{companyId}/ai")
public class AiFileIngestController {
	private final AiFileIngestService service;

	public AiFileIngestController(AiFileIngestService service) {
		this.service = service;
	}

	@PostMapping(value = "/extract-bill-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> extractBillFile(@PathVariable long companyId, @RequestParam("file") MultipartFile file) {
		return service.extractBillFile(companyId, file.getSize(), file.getOriginalFilename(), file::getBytes);
	}

	@PostMapping(value = "/extract-invoice-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> extractInvoiceFile(@PathVariable long companyId, @RequestParam("file") MultipartFile file) {
		return service.extractInvoiceFile(companyId, file.getSize(), file.getOriginalFilename(), file::getBytes);
	}
}
