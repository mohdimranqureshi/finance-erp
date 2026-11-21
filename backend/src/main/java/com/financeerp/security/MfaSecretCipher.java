package com.financeerp.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for TOTP secrets at rest. Output is {@code v1:base64(iv || ciphertext || tag)} with a
 * random 12-byte IV per value. The associated data binds a ciphertext to its owner row so it cannot be copied to
 * another account.
 */
public final class MfaSecretCipher {
	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public MfaSecretCipher(byte[] keyBytes) {
		if (keyBytes == null || keyBytes.length != 32)
			throw new IllegalArgumentException("The MFA encryption key must be exactly 32 bytes (AES-256).");
		this.key = new SecretKeySpec(keyBytes.clone(), "AES");
	}

	public static MfaSecretCipher fromBase64(String base64Key) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64Key.trim());
		} catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("The MFA encryption key is not valid base64.");
		}
		return new MfaSecretCipher(bytes);
	}

	public String encrypt(byte[] plaintext, String associatedData) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
			byte[] sealed = cipher.doFinal(plaintext);
			return PREFIX + Base64.getEncoder()
					.encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not encrypt the MFA secret.", ex);
		}
	}

	/** @throws IllegalStateException if the value is malformed, was tampered with, or was encrypted with another key */
	public byte[] decrypt(String value, String associatedData) {
		if (value == null || !value.startsWith(PREFIX))
			throw new IllegalStateException("Unsupported MFA secret format.");
		try {
			byte[] raw = Base64.getDecoder().decode(value.substring(PREFIX.length()));
			if (raw.length <= IV_BYTES + TAG_BITS / 8)
				throw new IllegalStateException("MFA secret ciphertext is truncated.");
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
			cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
			return cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalStateException("MFA secret could not be decrypted (wrong key or tampered value).", ex);
		}
	}
}
