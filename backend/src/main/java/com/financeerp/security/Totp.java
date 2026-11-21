package com.financeerp.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.OptionalLong;

/** RFC 6238 TOTP with HMAC-SHA1, 30-second steps and 6 digits; accepts one step of clock drift either way. */
public final class Totp {
	public static final int PERIOD_SECONDS = 30;
	public static final int DIGITS = 6;
	public static final int WINDOW = 1;
	private static final int[] POW10 = { 1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000 };

	private Totp() {
	}

	public static long timeStep(long epochSeconds) {
		return Math.floorDiv(epochSeconds, PERIOD_SECONDS);
	}

	public static String generate(byte[] key, long step, int digits) {
		if (digits < 1 || digits > 8)
			throw new IllegalArgumentException("digits must be 1-8");
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = hash[hash.length - 1] & 0x0F;
			int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
					| ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
			String otp = Integer.toString(binary % POW10[digits]);
			return "0".repeat(digits - otp.length()) + otp;
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA1 is unavailable.", ex);
		}
	}

	/**
	 * Returns the matched time step if {@code code} is valid within ±{@link #WINDOW} steps of {@code epochSeconds}
	 * and strictly newer than {@code lastUsedStep}; otherwise empty. The caller must persist the returned step.
	 */
	public static OptionalLong verify(byte[] key, String code, long epochSeconds, Long lastUsedStep) {
		if (code == null)
			return OptionalLong.empty();
		String candidate = code.replace(" ", "").trim();
		if (!candidate.matches("\\d{" + DIGITS + "}"))
			return OptionalLong.empty();
		byte[] given = candidate.getBytes(StandardCharsets.US_ASCII);
		long now = timeStep(epochSeconds);
		long matched = Long.MIN_VALUE;
		for (long step = now - WINDOW; step <= now + WINDOW; step++) {
			boolean equal = MessageDigest.isEqual(generate(key, step, DIGITS).getBytes(StandardCharsets.US_ASCII),
					given);
			if (equal && (lastUsedStep == null || step > lastUsedStep))
				matched = step;
		}
		return matched == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(matched);
	}
}
