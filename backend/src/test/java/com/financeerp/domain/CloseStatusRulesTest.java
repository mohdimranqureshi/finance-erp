package com.financeerp.domain;

import com.financeerp.domain.CloseStatusRules.ClientMetrics;
import com.financeerp.domain.CloseStatusRules.CloseStatus;
import com.financeerp.domain.CloseStatusRules.Reason;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class CloseStatusRulesTest {
	private static ClientMetrics clean() {
		return new ClientMetrics(false, 0, 0, 0, 0, 0, 0, null, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
				LocalDate.of(2026, 10, 31));
	}

	private static ClientMetrics with(boolean gst, long drafts, long submitted, long approved, long approvable,
			long stale, long bank, String period, long exceptions, String itc, long overdue, String overdueAmount,
			long periods) {
		return new ClientMetrics(gst, drafts, submitted, approved, approvable, stale, bank, period, exceptions,
				new BigDecimal(itc), overdue, new BigDecimal(overdueAmount), periods, null);
	}

	@Test
	void nothingOutstandingIsClean() {
		CloseStatus s = CloseStatusRules.evaluate(clean());
		assertThat(s.status()).isEqualTo("CLEAN");
		assertThat(s.reasons()).isEmpty();
	}

	@Test
	void workInProgressNeedsAttention() {
		CloseStatus s = CloseStatusRules.evaluate(with(false, 1, 3, 2, 2, 0, 4, null, 0, "0", 1, "1234567.5", 0));
		assertThat(s.status()).isEqualTo("ATTENTION");
		assertThat(s.reasons()).extracting(Reason::code).containsExactly("AWAITING_APPROVAL", "APPROVED_NOT_POSTED",
				"DRAFT_JOURNALS", "UNMATCHED_BANK", "OVERDUE_RECEIVABLES");
		assertThat(s.reasons()).allMatch(r -> r.level().equals("ATTENTION"));
		assertThat(s.reasons().get(0).message()).isEqualTo("3 journals awaiting approval (2 by you)");
		assertThat(s.reasons().get(4).message()).contains("1 receivable ").contains("INR 1,234,567.50");
	}

	@Test
	void gstRegisteredClientWithoutAnyGstr2bImportNeedsAttention() {
		CloseStatus s = CloseStatusRules.evaluate(with(true, 0, 0, 0, 0, 0, 0, null, 0, "0", 0, "0", 0));
		assertThat(s.status()).isEqualTo("ATTENTION");
		assertThat(s.reasons()).extracting(Reason::code).containsExactly("NO_GSTR2B");
	}

	@Test
	void gstr2bExceptionsBlockOnlyWhenItcIsAtRisk() {
		CloseStatus noRisk = CloseStatusRules.evaluate(with(true, 0, 0, 0, 0, 0, 0, "102026", 2, "0", 0, "0", 0));
		assertThat(noRisk.status()).isEqualTo("ATTENTION");
		assertThat(noRisk.reasons()).extracting(Reason::code).containsExactly("GSTR2B_EXCEPTIONS");

		CloseStatus risk = CloseStatusRules.evaluate(with(true, 0, 0, 0, 0, 0, 0, "102026", 1, "1800", 0, "0", 0));
		assertThat(risk.status()).isEqualTo("BLOCKED");
		assertThat(risk.reasons().get(0).message())
				.isEqualTo("GSTR-2B 102026: INR 1,800.00 ITC at risk with 1 open exception");

		CloseStatus resolved = CloseStatusRules.evaluate(with(true, 0, 0, 0, 0, 0, 0, "102026", 0, "1800", 0, "0", 0));
		assertThat(resolved.status()).isEqualTo("CLEAN");
	}

	@Test
	void overduePeriodsAndStaleJournalsBlockAndAreListedFirst() {
		CloseStatus s = CloseStatusRules.evaluate(with(false, 2, 0, 0, 0, 2, 1, null, 0, "0", 0, "0", 1));
		assertThat(s.status()).isEqualTo("BLOCKED");
		assertThat(s.reasons()).extracting(Reason::code).containsExactly("PERIOD_NOT_CLOSED", "STALE_JOURNALS",
				"DRAFT_JOURNALS", "UNMATCHED_BANK");
		assertThat(s.reasons()).extracting(Reason::level).containsExactly("BLOCKED", "BLOCKED", "ATTENTION",
				"ATTENTION");
		assertThat(s.reasons().get(0).message())
				.isEqualTo("1 accounting period still open more than 30 days after the period end date");
	}

	@Test
	void severityOrdersBlockedAboveAttentionAboveClean() {
		assertThat(CloseStatusRules.severity("BLOCKED")).isGreaterThan(CloseStatusRules.severity("ATTENTION"));
		assertThat(CloseStatusRules.severity("ATTENTION")).isGreaterThan(CloseStatusRules.severity("CLEAN"));
	}
}
