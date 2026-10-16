package com.limingyuan.bookkeeping.statistics.application;

/** Read port for one natural month of bookkeeping facts. Analysis must not open the ledger tables itself. */
public interface MonthlyBookkeepingFactsReader {
    MonthlyBookkeepingFacts read(long accountId, String yearMonth);
}
