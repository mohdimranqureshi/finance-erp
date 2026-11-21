package com.financeerp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

class Base32Test {
	@Test
	void encodesRfc4648Vectors() {
		String[][] vectors = { { "", "" }, { "f", "MY" }, { "fo", "MZXQ" }, { "foo", "MZXW6" }, { "foob", "MZXW6YQ" },
				{ "fooba", "MZXW6YTB" }, { "foobar", "MZXW6YTBOI" } };
		for (String[] v : vectors) {
			assertThat(Base32.encode(v[0].getBytes(StandardCharsets.US_ASCII))).isEqualTo(v[1]);
			assertThat(new String(Base32.decode(v[1]), StandardCharsets.US_ASCII)).isEqualTo(v[0]);
		}
		assertThat(Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII)))
				.isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
	}

	@Test
	void roundTripsRandomBytesAndToleratesPaddingCaseAndSpaces() {
		SecureRandom random = new SecureRandom();
		for (int len = 0; len < 64; len++) {
			byte[] data = new byte[len];
			random.nextBytes(data);
			assertThat(Base32.decode(Base32.encode(data))).isEqualTo(data);
		}
		assertThat(Base32.decode("mzxw 6ytb oi======")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
	}

	@Test
	void rejectsCharactersOutsideTheAlphabet() {
		assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
	}
}
