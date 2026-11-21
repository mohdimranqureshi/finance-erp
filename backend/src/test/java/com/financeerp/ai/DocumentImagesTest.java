package com.financeerp.ai;

import com.financeerp.security.CompanyAccess;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentImagesTest {

	@Test
	void sniffsFileTypesFromMagicBytesNotNames() throws IOException {
		assertThat(DocumentImages.sniff(png(10, 10))).isEqualTo(DocumentImages.Kind.PNG);
		assertThat(DocumentImages.sniff(jpeg(10, 10))).isEqualTo(DocumentImages.Kind.JPEG);
		assertThat(DocumentImages.sniff(ascii("%PDF-1.7\n"))).isEqualTo(DocumentImages.Kind.PDF);
		assertThat(DocumentImages.sniff(ascii("GIF89a\u0001\u0000"))).isEqualTo(DocumentImages.Kind.GIF);
		assertThat(DocumentImages.sniff(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 "))).isEqualTo(DocumentImages.Kind.WEBP);
		assertThat(DocumentImages.sniff(ascii("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000"))).isEqualTo(DocumentImages.Kind.HEIC);
		assertThat(DocumentImages.sniff(ascii("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
				.isEqualTo(DocumentImages.Kind.SVG);
		assertThat(DocumentImages.sniff(ascii("Invoice,Amount\n1,100\n"))).isEqualTo(DocumentImages.Kind.UNKNOWN);
		assertThat(DocumentImages.sniff(new byte[] { 1, 2 })).isEqualTo(DocumentImages.Kind.UNKNOWN);
	}

	@Test
	void reencodesJpegToPngAndDownscalesToTheMaximumSide() throws IOException {
		byte[] out = DocumentImages.toSafePng(jpeg(4500, 1500), DocumentImages.MAX_SIDE, DocumentImages.MAX_PIXELS);
		assertThat(DocumentImages.sniff(out)).isEqualTo(DocumentImages.Kind.PNG);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
		assertThat(decoded.getWidth()).isEqualTo(2000);
		assertThat(decoded.getHeight()).isBetween(666, 667);
	}

	@Test
	void keepsSmallImagesAtTheirSizeAndFlattensTransparency() throws IOException {
		BufferedImage argb = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
		byte[] out = DocumentImages.toSafePng(DocumentImages.encodePng(argb), 2000, DocumentImages.MAX_PIXELS);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out));
		assertThat(decoded.getWidth()).isEqualTo(40);
		assertThat(decoded.getHeight()).isEqualTo(30);
		assertThat(decoded.getColorModel().hasAlpha()).isFalse();
		assertThat(decoded.getRGB(5, 5) & 0xFFFFFF).isEqualTo(0xFFFFFF);
	}

	@Test
	void refusesImagesAboveThePixelCapBeforeDecoding() {
		assertThatThrownBy(() -> DocumentImages.toSafePng(png(100, 100), 2000, 5_000))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
		// A tiny PNG whose header claims 30000 x 30000 pixels is rejected from the header alone (no 3.6 GB decode).
		byte[] bomb = withPngDimensions(png(8, 8), 30_000, 30_000);
		assertThat(bomb.length).isLessThan(200);
		assertThatThrownBy(() -> DocumentImages.toSafePng(bomb, DocumentImages.MAX_SIDE, DocumentImages.MAX_PIXELS))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("30000 x 30000");
	}

	@Test
	void refusesMalformedAndUnsupportedImages() {
		byte[] truncated = Arrays.copyOf(png(50, 50), 40);
		assertThatThrownBy(() -> DocumentImages.toSafePng(truncated, 2000, DocumentImages.MAX_PIXELS))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("could not be read");
		assertThatThrownBy(() -> DocumentImages.toSafePng(ascii("GIF89a\u0001\u0000\u0001\u0000"), 2000, DocumentImages.MAX_PIXELS))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PNG and JPEG");
	}

	@Test
	void rendersScannedPdfPagesWithinLimits() throws IOException {
		try (PDDocument doc = Loader.loadPDF(scannedPdf(3, PDRectangle.A4))) {
			assertThat(new PDFTextStripper().getText(doc).strip()).isEmpty();
			List<byte[]> pages = DocumentImages.renderPdfPages(doc, 2, DocumentImages.MAX_SIDE, DocumentImages.MAX_PIXELS);
			assertThat(pages).hasSize(2);
			assertThat(DocumentImages.sniff(pages.get(0))).isEqualTo(DocumentImages.Kind.PNG);
			BufferedImage first = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
			// A4 at 170 DPI is about 1405 x 1988 pixels.
			assertThat(first.getHeight()).isBetween(1900, 2000);
			assertThat(first.getWidth()).isBetween(1350, 1450);
			assertThat(first.getRGB(first.getWidth() / 2, first.getHeight() / 2) & 0xFFFFFF).isEqualTo(0x000000);
		}
		// A page box of 200 x 200 inches still renders no larger than the maximum side.
		try (PDDocument doc = Loader.loadPDF(scannedPdf(1, new PDRectangle(14_400, 14_400)))) {
			BufferedImage page = ImageIO.read(new ByteArrayInputStream(
					DocumentImages.renderPdfPages(doc, 3, DocumentImages.MAX_SIDE, DocumentImages.MAX_PIXELS).get(0)));
			assertThat(Math.max(page.getWidth(), page.getHeight())).isLessThanOrEqualTo(DocumentImages.MAX_SIDE);
		}
	}

	@Test
	void ingestRefusesImagesAndScannedPdfsWhenVisionIsDisabled() throws IOException {
		LlmClient llm = mock(LlmClient.class);
		when(llm.available()).thenReturn(true);
		AiProposalController proposals = mock(AiProposalController.class);
		AiFileIngestController ingest = new AiFileIngestController(mock(CompanyAccess.class), llm, proposals, false, 3,
				DataSize.ofMegabytes(5));
		MockMultipartFile image = new MockMultipartFile("file", "bill.png", "image/png", png(20, 20));
		assertThatThrownBy(() -> ingest.extractBillFile(1, image)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("AI_VISION_ENABLED");
		MockMultipartFile scan = new MockMultipartFile("file", "scan.pdf", "application/pdf", scannedPdf(1, PDRectangle.A6));
		assertThatThrownBy(() -> ingest.extractBillFile(1, scan)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("scanned PDF").hasMessageContaining("AI_VISION_ENABLED");
		verifyNoInteractions(proposals);
	}

	@Test
	void ingestSendsCleanPngForImagesAndRefusesDisguisedFilesWhenVisionIsEnabled() throws IOException {
		LlmClient llm = mock(LlmClient.class);
		when(llm.available()).thenReturn(true);
		AiProposalController proposals = mock(AiProposalController.class);
		byte[] jpeg = jpeg(30, 30);
		when(proposals.extractInvoice(anyLong(), any(AiProposalController.ExtractionSource.class))).thenAnswer(inv -> {
			AiProposalController.ExtractionSource src = inv.getArgument(1);
			assertThat(src.images()).hasSize(1);
			assertThat(DocumentImages.sniff(src.images().get(0))).isEqualTo(DocumentImages.Kind.PNG);
			assertThat(src.length()).isEqualTo(jpeg.length);
			assertThat(src.text()).isNull();
			return Map.of("id", 1);
		});
		AiFileIngestController ingest = new AiFileIngestController(mock(CompanyAccess.class), llm, proposals, true, 3,
				DataSize.ofMegabytes(5));
		assertThat(ingest.extractInvoiceFile(1, new MockMultipartFile("file", "invoice.jpg", "image/jpeg", jpeg)))
				.containsEntry("inputMode", "VISION").containsEntry("sourceFile", "invoice.jpg");
		byte[] svg = ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
		MockMultipartFile disguised = new MockMultipartFile("file", "bill.png", "image/png", svg);
		assertThatThrownBy(() -> ingest.extractBillFile(1, disguised)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PNG and JPEG").hasMessageContaining("SVG");
		MockMultipartFile gif = new MockMultipartFile("file", "bill.gif", "image/gif", ascii("GIF89a\u0001\u0000\u0001\u0000"));
		assertThatThrownBy(() -> ingest.extractBillFile(1, gif)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("GIF");
	}

	private static byte[] png(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, w, h);
		g.dispose();
		return DocumentImages.encodePng(img);
	}

	private static byte[] jpeg(int w, int h) throws IOException {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		ImageIO.setUseCache(false);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThat(ImageIO.write(img, "jpeg", out)).isTrue();
		return out.toByteArray();
	}

	/** Rewrites the IHDR width and height (and the chunk CRC) of a PNG. */
	private static byte[] withPngDimensions(byte[] png, int width, int height) {
		byte[] out = png.clone();
		ByteBuffer buf = ByteBuffer.wrap(out);
		buf.putInt(16, width).putInt(20, height);
		CRC32 crc = new CRC32();
		crc.update(out, 12, 17);
		buf.putInt(29, (int) crc.getValue());
		return out;
	}

	/** A PDF whose pages contain only a black image and no text, like a scanner's output. */
	private static byte[] scannedPdf(int pages, PDRectangle size) throws IOException {
		try (PDDocument doc = new PDDocument()) {
			PDImageXObject image = LosslessFactory.createFromImage(doc, new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB));
			for (int i = 0; i < pages; i++) {
				PDPage page = new PDPage(size);
				doc.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
					content.drawImage(image, 0, 0, size.getWidth(), size.getHeight());
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}
}
