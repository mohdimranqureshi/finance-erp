package com.financeerp.domain;

import java.math.BigDecimal;

/** One line of a journal that is about to be created. */
public record LedgerLine(long accountId, String description, BigDecimal debit, BigDecimal credit) {
}
