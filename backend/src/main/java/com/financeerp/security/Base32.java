package com.financeerp.security;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** RFC 4648 Base32 (upper-case alphabet, no padding on output; padding, spaces and case are tolerated on input). */
public final class Base32 {
	private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

	private Base32() {
	}

	public static String encode(byte[] data) {
		StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
		int buffer = 0;
		int bits = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xFF);
			bits += 8;
			while (bits >= 5) {
				out.append(ALPHABET[(buffer >>> (bits - 5)) & 31]);
				bits -= 5;
			}
			buffer &= (1 << bits) - 1;
		}
		if (bits > 0)
			out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
		return out.toString();
	}

	public static byte[] decode(String text) {
		if (text == null)
			throw new IllegalArgumentException("Base32 value is missing.");
		String clean = text.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
		int end = clean.length();
		while (end > 0 && clean.charAt(end - 1) == '=')
			end--;
		ByteArrayOutputStream out = new ByteArrayOutputStream(end * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (int i = 0; i < end; i++) {
			char c = clean.charAt(i);
			int value;
			if (c >= 'A' && c <= 'Z')
				value = c - 'A';
			else if (c >= '2' && c <= '7')
				value = c - '2' + 26;
			else
				throw new IllegalArgumentException("Invalid Base32 character.");
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >>> (bits - 8)) & 0xFF);
				bits -= 8;
			}
			buffer &= (1 << bits) - 1;
		}
		return out.toByteArray();
	}
}
