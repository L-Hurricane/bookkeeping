package com.limingyuan.bookkeeping.ledger.application;

/**
 * Shared ledger / transaction list ordering.
 *
 * <p>Primary keys stay Business Time DESC then First Save Order DESC. Cross-account
 * reassignment keeps the original First Save Order, so two rows on one account may
 * share the same business second and order; {@code first_saved_at_utc} and {@code id}
 * (plus {@code kind} in the mixed ledger) break those ties without rewriting history.
 */
public final class LedgerOrdering {
    /** Transaction-only lists and name search. */
    public static final String TRANSACTION_LIST =
            "t.business_time DESC, t.first_save_order DESC, t.first_saved_at_utc DESC, t.id DESC";

    /** Mixed Transaction, Transfer, and BalanceAdjustment ledger. */
    public static final String LEDGER =
            "business_time DESC, first_save_order DESC, first_saved_at_utc DESC, kind DESC, id DESC";

    /** Key-expense ranking after amount magnitude. */
    public static final String KEY_EXPENSE_TIEBREAK =
            "t.business_time DESC, t.first_save_order DESC, t.first_saved_at_utc DESC, t.id DESC";

    private LedgerOrdering() { }
}
