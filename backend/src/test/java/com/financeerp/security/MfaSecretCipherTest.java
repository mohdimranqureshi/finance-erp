package com.financeerp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

class MfaSecretCipherTest {
	private static byte[] key() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	@Test
	void roundTripsWithAFreshIvPerValue() {
		MfaSecretCipher cipher = new MfaSecretCipher(key());
		byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		String first = cipher.encrypt(secret, "user:1");
		String second = cipher.encrypt(secret, "user:1");
		assertThat(first).startsWith("v1:").isNotEqualTo(second);
		assertThat(cipher.decrypt(first, "user:1")).isEqualTo(secret);
		assertThat(cipher.decrypt(second, "user:1")).isEqualTo(secret);
	}

	@Test
	void detectsTamperingWrongKeyAndWrongAssociatedData() {
		byte[] key = key();
		MfaSecretCipher cipher = new MfaSecretCipher(key);
		String sealed = cipher.encrypt(new byte[] { 1, 2, 3, 4 }, "user:1");
		byte[] raw = Base64.getDecoder().decode(sealed.substring(3));
		for (int i : new int[] { 0, 12, raw.length - 1 }) {
			byte[] tampered = raw.clone();
			tampered[i] ^= 0x01;
			String forged = "v1:" + Base64.getEncoder().encodeToString(tampered);
			assertThatThrownBy(() -> cipher.decrypt(forged, "user:1")).isInstanceOf(IllegalStateException.class);
		}
		assertThatThrownBy(() -> cipher.decrypt(sealed, "user:2")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new MfaSecretCipher(key()).decrypt(sealed, "user:1"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt("v1:AAAA", "user:1")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt("plain", "user:1")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void requiresA256BitKey() {
		assertThatThrownBy(() -> new MfaSecretCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
		assertThat(MfaSecretCipher.fromBase64(Base64.getEncoder().encodeToString(key()))).isNotNull();
		assertThatThrownBy(() -> MfaSecretCipher.fromBase64("not base64!"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
