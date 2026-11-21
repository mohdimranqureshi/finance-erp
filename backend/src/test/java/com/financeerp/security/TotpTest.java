package com.financeerp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

class TotpTest {
	private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	@Test
	void matchesRfc6238Sha1Vectors() {
		assertThat(Totp.generate(RFC_KEY, Totp.timeStep(59), 8)).isEqualTo("94287082");
		assertThat(Totp.generate(RFC_KEY, Totp.timeStep(59), 6)).isEqualTo("287082");
		assertThat(Totp.generate(RFC_KEY, Totp.timeStep(1111111109), 8)).isEqualTo("07081804");
		assertThat(Totp.generate(RFC_KEY, Totp.timeStep(1111111109), 6)).isEqualTo("081804");
		assertThat(Totp.generate(RFC_KEY, Totp.timeStep(1234567890), 8)).isEqualTo("89005924");
		assertThat(Totp.generate(RFC_KEY, Totp.timeStep(20000000000L), 8)).isEqualTo("65353130");
	}

	@Test
	void acceptsOneStepOfDriftEitherWayButNotTwo() {
		long now = 1111111109;
		long step = Totp.timeStep(now);
		assertThat(Totp.verify(RFC_KEY, "081804", now, null)).isEqualTo(OptionalLong.of(step));
		assertThat(Totp.verify(RFC_KEY, Totp.generate(RFC_KEY, step - 1, 6), now, null))
				.isEqualTo(OptionalLong.of(step - 1));
		assertThat(Totp.verify(RFC_KEY, Totp.generate(RFC_KEY, step + 1, 6), now, null))
				.isEqualTo(OptionalLong.of(step + 1));
		assertThat(Totp.verify(RFC_KEY, Totp.generate(RFC_KEY, step - 2, 6), now, null)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, Totp.generate(RFC_KEY, step + 2, 6), now, null)).isEmpty();
	}

	@Test
	void rejectsReplayOfUsedOrOlderSteps() {
		long now = 1111111109;
		long step = Totp.timeStep(now);
		assertThat(Totp.verify(RFC_KEY, "081804", now, step)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, Totp.generate(RFC_KEY, step - 1, 6), now, step)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, Totp.generate(RFC_KEY, step + 1, 6), now, step))
				.isEqualTo(OptionalLong.of(step + 1));
	}

	@Test
	void rejectsMalformedCodes() {
		long now = 1111111109;
		assertThat(Totp.verify(RFC_KEY, null, now, null)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, "81804", now, null)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, "07081804", now, null)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, "08180a", now, null)).isEmpty();
		assertThat(Totp.verify(RFC_KEY, "081 804", now, null)).isPresent();
	}
}
