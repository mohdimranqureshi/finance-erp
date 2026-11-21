package com.financeerp.ai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Safe image handling for vision extraction. Uploaded images are identified by magic bytes, their dimensions are
 * checked from the header before any pixels are decoded, and every image is re-encoded to a fresh PNG (no EXIF or
 * other metadata) no larger than {@link #MAX_SIDE} pixels on its longest side. All streams stay in memory.
 */
public final class DocumentImages {
	public static final int MAX_SIDE = 2000;
	/** Upper bound on decoded pixels; protects against decompression bombs (a tiny file declaring a huge canvas). */
	public static final long MAX_PIXELS = 40_000_000L;
	public static final float PDF_DPI = 170f;

	public enum Kind {
		PDF, PNG, JPEG, GIF, WEBP, BMP, TIFF, HEIC, AVIF, SVG, UNKNOWN;

		public boolean isImage() {
			return this != PDF && this != UNKNOWN;
		}
	}

	private DocumentImages() {
	}

	/** Identifies the file by its leading bytes, ignoring the name and the declared content type. */
	public static Kind sniff(byte[] b) {
		if (b == null || b.length < 4)
			return Kind.UNKNOWN;
		if (startsWith(b, 0, "%PDF-".getBytes(StandardCharsets.ISO_8859_1)))
			return Kind.PDF;
		if (startsWith(b, 0, new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' }))
			return Kind.PNG;
		if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF)
			return Kind.JPEG;
		if (startsWith(b, 0, ascii("GIF87a")) || startsWith(b, 0, ascii("GIF89a")))
			return Kind.GIF;
		if (startsWith(b, 0, ascii("RIFF")) && startsWith(b, 8, ascii("WEBP")))
			return Kind.WEBP;
		if (startsWith(b, 0, ascii("BM")))
			return Kind.BMP;
		if (startsWith(b, 0, ascii("II*\0")) || startsWith(b, 0, ascii("MM\0*")))
			return Kind.TIFF;
		if (startsWith(b, 4, ascii("ftyp")) && b.length >= 12) {
			String brand = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
			if (brand.startsWith("avi"))
				return Kind.AVIF;
			if (brand.startsWith("hei") || brand.startsWith("hev") || brand.startsWith("mif") || brand.startsWith("msf"))
				return Kind.HEIC;
		}
		String head = new String(b, 0, Math.min(b.length, 1024), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
		if (head.contains("<svg"))
			return Kind.SVG;
		return Kind.UNKNOWN;
	}

	/**
	 * Decodes a PNG or JPEG and returns a clean PNG whose longest side is at most maxSide. Dimensions are read from the
	 * header first and anything above maxPixels is refused before pixel data is decoded.
	 */
	public static byte[] toSafePng(byte[] bytes, int maxSide, long maxPixels) {
		Kind kind = sniff(bytes);
		if (kind != Kind.PNG && kind != Kind.JPEG)
			throw new IllegalArgumentException("Only PNG and JPEG images are supported.");
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(kind == Kind.PNG ? "png" : "jpeg");
		if (!readers.hasNext())
			throw new IllegalStateException("No image decoder is available for " + kind + ".");
		ImageReader reader = readers.next();
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			int width, height;
			try {
				reader.setInput(in, true, true);
				width = reader.getWidth(0);
				height = reader.getHeight(0);
			} catch (IOException | RuntimeException e) {
				throw unreadable();
			}
			if (width <= 0 || height <= 0)
				throw new IllegalArgumentException("The image has invalid dimensions.");
			if ((long) width * height > maxPixels)
				throw new IllegalArgumentException("The image is too large (" + width + " x " + height + " pixels). The limit is "
						+ String.format(Locale.ROOT, "%.1f", maxPixels / 1_000_000.0) + " megapixels.");
			ImageReadParam param = reader.getDefaultReadParam();
			int step = Math.max(1, Math.max(width, height) / maxSide);
			if (step > 1)
				param.setSourceSubsampling(step, step, 0, 0);
			BufferedImage decoded;
			try {
				decoded = reader.read(0, param);
			} catch (IOException | RuntimeException e) {
				throw unreadable();
			}
			return encodePng(fit(decoded, maxSide));
		} catch (IOException e) {
			throw unreadable();
		} finally {
			reader.dispose();
		}
	}

	private static IllegalArgumentException unreadable() {
		return new IllegalArgumentException(
				"The image could not be read. It may be damaged or use an unsupported format (for example a CMYK JPEG).");
	}

	/**
	 * Renders the first maxPages pages of a PDF to PNG at about {@link #PDF_DPI} DPI. The scale is reduced for very
	 * large pages so no rendered page exceeds maxSide pixels or maxPixels pixels.
	 */
	public static List<byte[]> renderPdfPages(PDDocument doc, int maxPages, int maxSide, long maxPixels) {
		PDFRenderer renderer = new PDFRenderer(doc);
		renderer.setSubsamplingAllowed(true);
		List<byte[]> pages = new ArrayList<>();
		int count = Math.min(doc.getNumberOfPages(), maxPages);
		for (int i = 0; i < count; i++) {
			PDRectangle box;
			try {
				box = doc.getPage(i).getCropBox();
			} catch (RuntimeException e) {
				throw unrenderable();
			}
			double w = Math.abs(box.getWidth()), h = Math.abs(box.getHeight());
			if (!(w >= 1 && h >= 1))
				throw new IllegalArgumentException("Page " + (i + 1) + " of the PDF has no printable area.");
			// The canvas size is fixed here, before rendering, so a huge page box cannot allocate a huge image.
			double scale = Math.min(PDF_DPI / 72.0, maxSide / Math.max(w, h));
			scale = Math.min(scale, Math.sqrt(maxPixels / (w * h)));
			BufferedImage image;
			try {
				image = renderer.renderImage(i, (float) scale, ImageType.RGB);
			} catch (IOException | RuntimeException e) {
				throw unrenderable();
			}
			pages.add(encodePng(fit(image, maxSide)));
		}
		return pages;
	}

	private static IllegalArgumentException unrenderable() {
		return new IllegalArgumentException("The scanned PDF could not be rendered. It may be damaged.");
	}

	/** Copies onto an opaque RGB canvas (white background), downscaling so the longest side is at most maxSide. */
	static BufferedImage fit(BufferedImage src, int maxSide) {
		int w = src.getWidth(), h = src.getHeight();
		double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
		int tw = Math.max(1, (int) Math.round(w * scale)), th = Math.max(1, (int) Math.round(h * scale));
		tw = Math.min(tw, maxSide);
		th = Math.min(th, maxSide);
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, tw, th);
			g.drawImage(src, 0, 0, tw, th, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	/** Encodes as PNG in memory without any metadata (ImageIO's default writer would use a temp-file cache). */
	static byte[] encodePng(BufferedImage image) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), null);
		} catch (IOException e) {
			throw new IllegalStateException("PNG encoding failed.", e);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static boolean startsWith(byte[] b, int offset, byte[] prefix) {
		if (b.length < offset + prefix.length)
			return false;
		for (int i = 0; i < prefix.length; i++)
			if (b[offset + i] != prefix[i])
				return false;
		return true;
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}
}
