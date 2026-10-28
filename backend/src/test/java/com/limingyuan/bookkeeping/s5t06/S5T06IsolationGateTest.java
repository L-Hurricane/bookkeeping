package com.limingyuan.bookkeeping.s5t06;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionService;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationStore;
import com.limingyuan.bookkeeping.analysis.application.AnalysisProvenance;
import com.limingyuan.bookkeeping.analysis.application.LedgerAnalysisFactsService;
import com.limingyuan.bookkeeping.analysis.application.LedgerAnalysisService;
import com.limingyuan.bookkeeping.analysis.application.LedgerGenerationPermit;
import com.limingyuan.bookkeeping.analysis.application.LedgerPermitAcquisition;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisReport;
import com.limingyuan.bookkeeping.analysis.application.PermitAcquisition;
import com.limingyuan.bookkeeping.analysis.application.SuccessSettlement;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.CategoryNode;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.Report;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.TagNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S5-T06 isolation gate. Runs only against bookkeeping_migration_test.
 * Flyway is disabled. The datasource URL is fixed before the context starts.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "S5_T06_RUN", matches = "1")
class S5T06IsolationGateTest {
    private static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/bookkeeping_migration_test";
    private static final String SCHEMA = "bookkeeping_migration_test";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired LedgerStatisticsService statistics;
    @Autowired LedgerAnalysisFactsService factsService;
    @Autowired AnalysisGenerationStore store;
    @Autowired AccountPermanentDeletionService deletion;

    @DynamicPropertySource
    static void forceMigrationSchema(DynamicPropertyRegistry registry) {
        if (!JDBC_URL.endsWith("/" + SCHEMA)) {
            throw new IllegalStateException("ABORT jdbc url schema");
        }
        var admin = AdminClientFile.read();
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> admin.user());
        registry.add("spring.datasource.password", () -> admin.password());
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("bookkeeping.analysis.dashscope.api-key", () -> "");
        registry.add("bookkeeping.analysis.dashscope.base-url", () -> "");
        registry.add("bookkeeping.identity.bootstrap.enabled", () -> "false");
        registry.add("bookkeeping.acceptance-data.enabled", () -> "false");
    }

    @Test
    void isolationGate() throws Exception {
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key != null && !key.isBlank()) {
            throw new IllegalStateException("ABORT dashscope key is set");
        }
        try (var connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            System.out.println("S5T06_CATALOG=" + catalog);
            if (!SCHEMA.equals(catalog)) {
                throw new IllegalStateException("ABORT wrong catalog " + catalog);
            }
        }
        requireDb();
        String flyway = jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                .query(String.class).single();
        int fk = jdbc.sql("SELECT @@foreign_key_checks").query(Integer.class).single();
        System.out.println("S5T06_FLYWAY=" + flyway + " FK=" + fk);
        if (!"18".equals(flyway) || fk != 1) {
            throw new IllegalStateException("ABORT flyway or foreign key checks");
        }
        if (!"v18-drill".equals(username(1L))) {
            throw new IllegalStateException("ABORT drill user changed before scenarios");
        }

        List<String> failures = new ArrayList<>();
        check(failures, "statistics", this::statistics);
        check(failures, "facts-and-period", this::factsAndPeriod);
        check(failures, "v18", this::v18);
        check(failures, "quota", this::quota);
        check(failures, "service-generation", this::serviceGeneration);
        check(failures, "fencing", this::fencing);
        check(failures, "scheme-b", this::schemeB);
        check(failures, "rollback", this::rollback);
        check(failures, "drill-untouched", this::drillUntouched);
        int fkAfter = jdbc.sql("SELECT @@foreign_key_checks").query(Integer.class).single();
        System.out.println("S5T06_FK_AFTER=" + fkAfter);
        if (fkAfter != 1) failures.add("foreign_key_checks ended at " + fkAfter);
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n", failures));
        }
    }

    private void statistics() {
        long user = user("s5t06-stats");
        long active = account(user, "ACCTNAME-s5t06-alpha", "100.00", "ACTIVE");
        long archived = account(user, "ACCTNAME-s5t06-archived", "-20.00", "ARCHIVED");
        long salary = category(user, "INCOME", "工资", null);
        long food = category(user, "EXPENSE", "餐饮", null);
        long breakfast = category(user, "EXPENSE", "早餐", food);
        long travel = tag(user, "出行", null);
        long metro = tag(user, "地铁", travel);
        tx(user, active, "INCOME", "6.00", "2025-12-31 23:59:59", salary, null, null, null);
        tx(user, active, "INCOME", "7.00", "2026-01-01 00:00:00", salary, null, null, null);
        tx(user, active, "INCOME", "1.00", "2026-09-30 23:59:59", salary, null, null, null);
        tx(user, active, "INCOME", "2.00", "2026-10-01 00:00:00", null, null, null, null);
        tx(user, active, "INCOME", "80.00", "2026-10-05 12:00:00", salary, null, "TXNAME-s5t06-secret", "NOTE-s5t06-secret tok_s5t06_secret");
        long expense = tx(user, active, "EXPENSE", "-30.00", "2026-10-06 12:00:00", breakfast, null, null, null);
        tagTx(expense, metro);
        tx(user, active, "EXPENSE", "10.00", "2026-10-07 12:00:00", null, null, null, null);
        tx(user, archived, "INCOME", "5.00", "2026-10-08 12:00:00", null, null, null, null);
        tx(user, active, "INCOME", "3.00", "2026-10-31 23:59:59", null, null, null, null);
        tx(user, active, "INCOME", "4.00", "2026-11-01 00:00:00", salary, null, null, null);
        tx(user, active, "INCOME", "8.00", "2026-12-31 23:59:59", salary, null, null, null);
        tx(user, active, "INCOME", "9.00", "2027-01-01 00:00:00", salary, null, null, null);
        adjust(user, active, "-7.50", "2026-10-15 12:00:00");
        transfer(user, active, archived, "20.00", "2026-10-20 12:00:00", "2026-11-03 12:00:00");

        asUser(user);
        Report octoberAll = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null);
        Report octoberActive = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ACTIVE", null);
        Report octoberSelected = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "SELECTED", Long.toString(active));
        Report novemberAll = statistics.report("CUSTOM", "2026-11-01", "2026-11-30", "ALL", null);
        Report yearAll = statistics.report("CUSTOM", "2026-01-01", "2026-12-31", "ALL", null);
        Report september = statistics.report("CUSTOM", "2026-09-01", "2026-09-30", "ALL", null);
        Report beforeYear = statistics.report("CUSTOM", "2025-12-01", "2025-12-31", "ALL", null);
        Report afterYear = statistics.report("CUSTOM", "2027-01-01", "2027-01-31", "ALL", null);
        LocalDate beijingToday = LocalDate.now(Clock.systemUTC().withZone(BEIJING));
        Report monthPreset = statistics.report("MONTH", null, null, "ALL", null);
        Report yearPreset = statistics.report("YEAR", null, null, "ALL", null);
        System.out.println("S5T06_OBS stats octoberAll income=" + octoberAll.income().net()
                + " expense=" + octoberAll.expense().net()
                + " open=" + octoberAll.netWorth().opening()
                + " close=" + octoberAll.netWorth().closing()
                + " change=" + octoberAll.netWorth().change()
                + " activeIncome=" + octoberActive.income().net()
                + " novOpen=" + novemberAll.netWorth().opening()
                + " novClose=" + novemberAll.netWorth().closing()
                + " yearIncome=" + yearAll.income().net()
                + " monthPreset=" + monthPreset.from() + ".." + monthPreset.to()
                + " yearPreset=" + yearPreset.from() + ".." + yearPreset.to()
                + " beijingToday=" + beijingToday);

        eq(octoberAll.from(), "2026-10-01");
        eq(octoberAll.to(), "2026-10-31");
        eq(octoberAll.income().net(), "90.00");
        eq(octoberAll.expense().net(), "-20.00");
        eq(octoberAll.netWorth().opening(), "94.00");
        eq(octoberAll.netWorth().closing(), "176.50");
        eq(octoberAll.netWorth().change(), "82.50");
        eq(octoberActive.income().net(), "85.00");
        eq(octoberActive.expense().net(), "-20.00");
        eq(octoberActive.netWorth().opening(), "114.00");
        eq(octoberActive.netWorth().closing(), "191.50");
        eq(octoberSelected.income().net(), "85.00");
        eq(octoberSelected.netWorth().closing(), "191.50");
        eq(novemberAll.netWorth().opening(), "176.50");
        eq(novemberAll.netWorth().closing(), "200.50");
        eq(novemberAll.income().net(), "4.00");
        eq(yearAll.income().net(), "110.00");
        eq(yearAll.expense().net(), "-20.00");
        eq(yearAll.netWorth().opening(), "86.00");
        eq(yearAll.netWorth().closing(), "208.50");
        eq(september.income().net(), "1.00");
        eq(beforeYear.income().net(), "6.00");
        eq(afterYear.income().net(), "9.00");
        eq(monthPreset.from(), beijingToday.withDayOfMonth(1).toString());
        eq(monthPreset.to(), beijingToday.withDayOfMonth(beijingToday.lengthOfMonth()).toString());
        eq(yearPreset.from(), beijingToday.withDayOfYear(1).toString());
        eq(yearPreset.to(), LocalDate.of(beijingToday.getYear(), 12, 31).toString());
        if (beijingToday.getYear() == 2026 && beijingToday.getMonthValue() == 10) {
            eq(monthPreset.income().net(), "90.00");
            eq(yearPreset.income().net(), "110.00");
        }

        CategoryNode foodNode = categoryNamed(octoberAll.expense().categories(), "餐饮");
        CategoryNode breakfastNode = categoryNamed(foodNode.children(), "早餐");
        eq(foodNode.amount(), "-30.00");
        eq(breakfastNode.amount(), "-30.00");
        eq(String.valueOf(octoberAll.expense().uncategorized().transactionCount()), "1");
        eq(octoberAll.expense().uncategorized().amount(), "10.00");
        TagNode travelNode = tagNamed(octoberAll.expense().tags(), "出行");
        TagNode metroNode = tagNamed(travelNode.children(), "地铁");
        eq(travelNode.amount(), "-30.00");
        eq(metroNode.amount(), "-30.00");
        eq(octoberAll.expense().untagged().amount(), "10.00");
        CategoryNode salaryNode = categoryNamed(octoberAll.income().categories(), "工资");
        eq(salaryNode.amount(), "80.00");
        eq(octoberAll.income().uncategorized().amount(), "10.00");

        long emptyUser = user("s5t06-empty");
        asUser(emptyUser);
        Report empty = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null);
        eq(String.valueOf(empty.accounts().size()), "0");
        eq(empty.income().net(), "0.00");
        eq(empty.expense().net(), "0.00");
        eq(empty.netWorth().opening(), "0.00");
        eq(empty.netWorth().closing(), "0.00");

        long negativeUser = user("s5t06-negative");
        account(negativeUser, "s5t06-negative-acct", "-80.00", "ACTIVE");
        asUser(negativeUser);
        Report negative = statistics.report("CUSTOM", "2026-01-01", "2026-01-31", "ALL", null);
        eq(negative.netWorth().opening(), "-80.00");
        eq(negative.netWorth().closing(), "-80.00");
        eq(negative.income().net(), "0.00");
        eq(negative.expense().net(), "0.00");

        long other = user("s5t06-reader");
        long otherAccount = account(other, "s5t06-reader-acct", "1.00", "ACTIVE");
        tx(other, otherAccount, "INCOME", "3.00", "2026-10-04 08:00:00", null, null, null, null);
        asUser(other);
        Report otherOctober = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null);
        eq(otherOctober.income().net(), "3.00");
        eq(String.valueOf(otherOctober.accounts().size()), "1");
        asUser(user);
        Report stillOwn = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null);
        eq(stillOwn.income().net(), "90.00");
    }

    private void factsAndPeriod() {
        long userId = idOf("s5t06-stats");
        asUser(userId);
        var facts = factsService.build("MONTH", "2026-10");
        String json = facts.json();
        System.out.println("S5T06_OBS facts income=" + facts.incomeNet() + " expense=" + facts.expenseNet()
                + " open=" + facts.opening() + " close=" + facts.closing()
                + " noteAbsent=" + !json.contains("NOTE-s5t06-secret")
                + " nameAbsent=" + !json.contains("TXNAME-s5t06-secret")
                + " accountAbsent=" + !json.contains("ACCTNAME-s5t06")
                + " tokenAbsent=" + !json.contains("tok_s5t06_secret"));
        eq(facts.incomeNet(), "90.00");
        eq(facts.expenseNet(), "-20.00");
        eq(facts.opening(), "94.00");
        eq(facts.closing(), "176.50");
        eq(facts.change(), "82.50");
        eq(facts.priorPeriod(), "2026-09");
        eq(facts.priorIncomeNet(), "1.00");
        if (!json.contains("早餐")) throw new AssertionError("category aggregate missing from facts json");
        if (json.contains("NOTE-s5t06-secret") || json.contains("TXNAME-s5t06-secret")
                || json.contains("ACCTNAME-s5t06") || json.contains("tok_s5t06_secret")) {
            throw new AssertionError("facts json contains note, account name, or token text");
        }
        var year = factsService.build("YEAR", "2026");
        eq(year.incomeNet(), "110.00");
        eq(year.expenseNet(), "-20.00");
        eq(year.priorPeriod(), "2025");
        eq(year.priorIncomeNet(), "6.00");
        expectApi(400, "VALIDATION_FAILED", () -> factsService.build("QUARTER", "2026-10"));
        expectApi(400, "VALIDATION_FAILED", () -> factsService.build("MONTH", ""));
        expectApi(400, "VALIDATION_FAILED", () -> factsService.build("MONTH", "   "));
        expectApi(400, "VALIDATION_FAILED", () -> factsService.build("YEAR", "2026-10"));
        expectApi(400, "VALIDATION_FAILED", () -> factsService.build("CUSTOM", "2026-01-01"));
        expectApi(400, "VALIDATION_FAILED", () -> factsService.build(null, null));
        var service = stubService(new AtomicInteger());
        expectApi(400, "VALIDATION_FAILED", () -> service.get("QUARTER", "2026"));
        expectApi(400, "VALIDATION_FAILED", () -> service.generate("YEAR", "", "fingerprint"));
    }

    private void v18() {
        long user = user("s5t06-v18");
        long account = account(user, "s5t06-v18-acct", "0.00", "ACTIVE");
        var month = (LedgerPermitAcquisition.Granted) store.acquireLedger(user, "MONTH", "2026-04", "fp-v18-month");
        var first = (SuccessSettlement.Applied) store.completeLedgerSuccess(month.permit(),
                report("SNAPSHOT-V1-TEXT"), provenance());
        int reopened = jdbc.sql("""
                UPDATE analysis_quota_days
                SET reserved_count = reserved_count + 1
                WHERE user_id = :user AND permit_date = :date AND reserved_count + consumed_count < 3
                """).param("user", user).param("date", month.permit().permitDate()).update();
        eq(String.valueOf(reopened), "1");
        int reset = jdbc.sql("""
                UPDATE analysis_generations
                SET state = 'IN_PROGRESS', quota_reserved = 1, lease_expires_at_utc = :lease
                WHERE id = :id AND state = 'SUCCEEDED' AND report_scope = 'LEDGER' AND account_id IS NULL
                """).param("lease", Timestamp.from(Instant.now().plus(Duration.ofMinutes(5))))
                .param("id", month.permit().generationId()).update();
        eq(String.valueOf(reset), "1");
        long token = jdbc.sql("SELECT fencing_token FROM analysis_generations WHERE id = :id")
                .param("id", month.permit().generationId()).query(Long.class).single();
        var again = new LedgerGenerationPermit(month.permit().generationId(), token, user, "MONTH", "2026-04",
                "fp-v18-month", month.permit().permitDate(), Instant.now().plusSeconds(60));
        var second = (SuccessSettlement.Applied) store.completeLedgerSuccess(again,
                report("SNAPSHOT-V2-TEXT"), provenance());
        var latest = store.findLatestLedger(user, "MONTH", "2026-04").orElseThrow();
        eq(latest.report().monthlyOverview(), "SNAPSHOT-V2-TEXT");
        eq(String.valueOf(latest.id()), String.valueOf(second.snapshot().id()));
        eq(textOf(first.snapshot().id()), "SNAPSHOT-V1-TEXT");
        eq(String.valueOf(count("""
                SELECT COUNT(*) FROM analysis_generations
                WHERE user_id = :user AND report_scope = 'LEDGER' AND period_kind = 'MONTH' AND analysis_month = '2026-04'
                """, user)), "1");
        String scope = jdbc.sql("""
                SELECT CONCAT(report_scope, ':', IFNULL(account_id, 'NULL'), ':', period_kind, ':', scope_account_key)
                FROM analysis_generations WHERE id = :id
                """).param("id", month.permit().generationId()).query(String.class).single();
        eq(scope, "LEDGER:NULL:MONTH:0");
        expectSql(1062, () -> insertGeneration(user, null, "LEDGER", "MONTH", "2026-04", "fp-v18-month", "FAILED"));

        var year = (LedgerPermitAcquisition.Granted) store.acquireLedger(user, "YEAR", "2025", "fp-v18-year");
        store.completeLedgerSuccess(year.permit(), report("YEAR-SNAPSHOT"), provenance());
        String yearRow = jdbc.sql("""
                SELECT CONCAT(report_scope, ':', IFNULL(account_id, 'NULL'), ':', period_kind, ':', analysis_month)
                FROM analysis_snapshots WHERE user_id = :user AND context_fingerprint = 'fp-v18-year'
                """).param("user", user).query(String.class).single();
        eq(yearRow, "LEDGER:NULL:YEAR:2025");

        long accountUser = user("s5t06-v18-account");
        long accountOwned = account(accountUser, "s5t06-v18-account-acct", "0.00", "ACTIVE");
        var accountPermit = (PermitAcquisition.Granted) store.acquire(accountUser, accountOwned, "2026-08", "fp-v18-account");
        store.completeSuccess(accountPermit.permit(), report("ACCOUNT-SNAPSHOT"), provenance());
        String accountRow = jdbc.sql("""
                SELECT CONCAT(report_scope, ':', account_id, ':', period_kind)
                FROM analysis_snapshots WHERE user_id = :user AND context_fingerprint = 'fp-v18-account'
                """).param("user", accountUser).query(String.class).single();
        eq(accountRow, "ACCOUNT:" + accountOwned + ":MONTH");
        if (store.findLatest(user, account, "2026-04").isPresent()) {
            throw new AssertionError("account history returned a null-account ledger row");
        }
        if (store.findSuccessful(user, account, "2026-04", "fp-v18-month").isPresent()) {
            throw new AssertionError("account fingerprint lookup treated a ledger row as account history");
        }

        insertSnapshot(user, null, "LEGACY", "MONTH", "2023-04", "fp-v18-legacy", "LEGACY-ONLY");
        if (store.findLatestLedger(user, "MONTH", "2023-04").isPresent()) {
            throw new AssertionError("null account LEGACY row was returned as LEDGER");
        }
        eq(jdbc.sql("""
                SELECT monthly_overview FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'LEGACY' AND account_id IS NULL AND analysis_month = '2023-04'
                """).param("user", user).query(String.class).single(), "LEGACY-ONLY");
        int nullRows = count("""
                SELECT COUNT(*) FROM analysis_snapshots
                WHERE user_id = :user AND account_id IS NULL
                """, user);
        int ledgerRows = count("""
                SELECT COUNT(*) FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'LEDGER' AND account_id IS NULL
                """, user);
        if (nullRows <= ledgerRows) throw new AssertionError("NULL account rows collapsed into LEDGER");

        expectSql(3819, () -> insertSnapshot(user, null, "ACCOUNT", "MONTH", "2026-05", "bad-account-null", "nope"));
        expectSql(3819, () -> insertSnapshot(user, account, "LEDGER", "MONTH", "2026-05", "bad-ledger-account", "nope"));
        expectSql(3819, () -> insertSnapshot(user, null, "LEDGER", "YEAR", "2026-01", "bad-year-len7", "nope"));
        expectSql(3819, () -> insertSnapshot(user, null, "LEDGER", "MONTH", "2026", "bad-month-len4", "nope"));
        expectSql(3819, () -> insertGeneration(user, null, "ACCOUNT", "MONTH", "2026-06", "bad-gen-account-null", "FAILED"));
        expectSql(3819, () -> insertGeneration(user, account, "LEDGER", "MONTH", "2026-06", "bad-gen-ledger-account", "FAILED"));
        System.out.println("S5T06_OBS v18 latest=" + latest.report().monthlyOverview()
                + " older=" + textOf(first.snapshot().id()) + " scope=" + scope);
    }

    private void quota() {
        long user = user("s5t06-quota");
        long account = account(user, "s5t06-quota-acct", "0.00", "ACTIVE");
        completeAccount(user, account, "2026-01", "fp-q-a1");
        completeLedger(user, "MONTH", "2026-02", "fp-q-l1");
        completeAccount(user, account, "2026-03", "fp-q-a2");
        int consumed = quota(user, "consumed_count");
        int reserved = quota(user, "reserved_count");
        System.out.println("S5T06_OBS quota after3 consumed=" + consumed + " reserved=" + reserved);
        eq(String.valueOf(consumed), "3");
        eq(String.valueOf(reserved), "0");
        store.findLatestLedger(user, "MONTH", "2026-02");
        store.findLatest(user, account, "2026-01");
        eq(String.valueOf(quota(user, "consumed_count")), "3");
        if (!(store.acquireLedger(user, "YEAR", "2026", "fp-q-y") instanceof LedgerPermitAcquisition.QuotaExceeded)) {
            throw new AssertionError("4th ledger generation was not rejected");
        }
        if (!(store.acquire(user, account, "2026-04", "fp-q-a3") instanceof PermitAcquisition.QuotaExceeded)) {
            throw new AssertionError("4th account generation was not rejected");
        }
        eq(String.valueOf(quota(user, "consumed_count")), "3");
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :user", user)), "3");
    }

    private void serviceGeneration() {
        long user = user("s5t06-svc");
        long account = account(user, "s5t06-svc-acct", "10.00", "ACTIVE");
        tx(user, account, "INCOME", "15.00", "2026-10-02 09:00:00", null, null, null, null);
        tx(user, account, "INCOME", "4.00", "2026-09-02 09:00:00", null, null, null, null);
        tx(user, account, "INCOME", "6.00", "2026-08-02 09:00:00", null, null, null, null);
        asUser(user);
        var calls = new AtomicInteger();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service = stubService(calls, fail);
        expectApi(409, "EMPTY_PERIOD", () -> service.generate("MONTH", "2024-02",
                factsService.build("MONTH", "2024-02").fingerprint()));
        eq(String.valueOf(quota(user, "consumed_count")), "0");
        var failedFacts = factsService.build("MONTH", "2026-08");
        expectApi(502, "GENERATION_FAILED", () -> service.generate("MONTH", "2026-08", failedFacts.fingerprint()));
        eq(String.valueOf(count("""
                SELECT COUNT(*) FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'LEDGER' AND analysis_month = '2026-08'
                """, user)), "0");
        eq(String.valueOf(quota(user, "consumed_count")), "0");
        String failedState = jdbc.sql("""
                SELECT state FROM analysis_generations
                WHERE user_id = :user AND report_scope = 'LEDGER' AND analysis_month = '2026-08'
                """).param("user", user).query(String.class).single();
        eq(failedState, "FAILED");
        fail.set(false);
        var october = factsService.build("MONTH", "2026-10");
        var success = service.generate("MONTH", "2026-10", october.fingerprint());
        eq(success.outcome(), "SUCCESS");
        eq(String.valueOf(quota(user, "consumed_count")), "1");
        int beforeGet = quota(user, "consumed_count");
        var loaded = service.get("MONTH", "2026-10");
        eq(loaded.viewState(), "CURRENT");
        eq(String.valueOf(quota(user, "consumed_count")), String.valueOf(beforeGet));
        var reused = service.generate("MONTH", "2026-10", october.fingerprint());
        eq(reused.outcome(), "REUSED");
        eq(String.valueOf(quota(user, "consumed_count")), "1");
        var september = factsService.build("MONTH", "2026-09");
        service.generate("MONTH", "2026-09", september.fingerprint());
        var year = factsService.build("YEAR", "2026");
        service.generate("YEAR", "2026", year.fingerprint());
        eq(String.valueOf(quota(user, "consumed_count")), "3");
        var august = factsService.build("MONTH", "2026-08");
        expectApi(409, "QUOTA_EXCEEDED", () -> service.generate("MONTH", "2026-08", august.fingerprint()));
        eq(String.valueOf(quota(user, "consumed_count")), "3");
        System.out.println("S5T06_OBS service calls=" + calls.get() + " consumed=" + quota(user, "consumed_count"));
    }

    private void fencing() throws Exception {
        long user = user("s5t06-fence");
        var first = (LedgerPermitAcquisition.Granted) store.acquireLedger(user, "YEAR", "2023", "fp-fence");
        var second = store.acquireLedger(user, "YEAR", "2023", "fp-fence");
        if (!(second instanceof LedgerPermitAcquisition.Busy)) {
            throw new AssertionError("in-progress lease did not return busy: " + second.getClass().getSimpleName());
        }
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :user", user)), "0");
        jdbc.sql("""
                UPDATE analysis_generations
                SET lease_expires_at_utc = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE)
                WHERE id = :id AND fencing_token = :token
                """).param("id", first.permit().generationId()).param("token", first.permit().fencingToken()).update();
        var takeover = (LedgerPermitAcquisition.Granted) store.acquireLedger(user, "YEAR", "2023", "fp-fence");
        var stale = store.completeLedgerSuccess(first.permit(), report("STALE-MUST-NOT-LAND"), provenance());
        if (!(stale instanceof SuccessSettlement.StalePermit)) {
            throw new AssertionError("stale fencing token completed a snapshot");
        }
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :user", user)), "0");
        var applied = store.completeLedgerSuccess(takeover.permit(), report("FENCE-ONCE"), provenance());
        if (!(applied instanceof SuccessSettlement.Applied)) {
            throw new AssertionError("current fencing token did not complete");
        }
        var repeat = store.completeLedgerSuccess(takeover.permit(), report("FENCE-TWICE"), provenance());
        if (!(repeat instanceof SuccessSettlement.StalePermit)) {
            throw new AssertionError("reused fencing token wrote a second snapshot");
        }
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :user", user)), "1");
        eq(textOf(((SuccessSettlement.Applied) applied).snapshot().id()), "FENCE-ONCE");

        long raceUser = user("s5t06-race");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<LedgerPermitAcquisition> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(store.acquireLedger(raceUser, "YEAR", "2024", "fp-race"));
                } catch (Throwable ex) {
                    errors.add(ex);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(20, TimeUnit.SECONDS)) throw new AssertionError("concurrent acquire timed out");
        if (!errors.isEmpty()) throw new AssertionError("concurrent acquire failed", errors.get(0));
        long granted = results.stream().filter(item -> item instanceof LedgerPermitAcquisition.Granted).count();
        long busy = results.stream().filter(item -> item instanceof LedgerPermitAcquisition.Busy).count();
        System.out.println("S5T06_OBS race granted=" + granted + " busy=" + busy + " reserved=" + quota(raceUser, "reserved_count"));
        eq(String.valueOf(granted), "1");
        eq(String.valueOf(busy), "1");
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_generations WHERE user_id = :user", raceUser)), "1");
        eq(String.valueOf(quota(raceUser, "reserved_count")), "1");
    }

    private void schemeB() {
        long user = user("s5t06-del");
        long victim = account(user, "s5t06-victim", "0.00", "ARCHIVED");
        long kept = account(user, "s5t06-kept", "0.00", "ACTIVE");
        tx(user, victim, "INCOME", "50.00", "2026-10-03 10:00:00", null, null, null, null);
        tx(user, kept, "INCOME", "12.00", "2026-10-02 10:00:00", null, null, null, null);
        insertSnapshot(user, victim, "ACCOUNT", "MONTH", "2026-05", "fp-del-victim", "VICTIM-ACCOUNT");
        insertSnapshot(user, kept, "ACCOUNT", "MONTH", "2026-05", "fp-del-kept", "KEPT-ACCOUNT");
        completeLedger(user, "MONTH", "2026-10", "fp-del-month");
        completeLedger(user, "YEAR", "2026", "fp-del-year");
        insertSnapshot(user, null, "LEGACY", "MONTH", "2022-02", "fp-del-legacy", "LEGACY-STAYS");
        int consumed = quota(user, "consumed_count");
        long other = idOf("s5t06-reader");
        long otherAccount = jdbc.sql("SELECT id FROM accounts WHERE user_id = :user")
                .param("user", other).query(Long.class).single();
        completeAccount(other, otherAccount, "2026-06", "fp-other-account");
        completeLedger(other, "MONTH", "2026-06", "fp-other-ledger");

        asUser(user);
        Report before = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null);
        eq(before.income().net(), "62.00");
        var preview = deletion.preview(victim);
        System.out.println("S5T06_OBS preview ledgerReports=" + preview.ledgerReportCount()
                + " accountSnapshots=" + preview.analysisSnapshotCount()
                + " consumed=" + consumed);
        eq(String.valueOf(preview.ledgerReportCount()), "2");
        eq(String.valueOf(preview.analysisSnapshotCount()), "1");
        asUser(other);
        expectApi(404, "RESOURCE_NOT_FOUND", () -> deletion.preview(victim));
        expectApi(404, "RESOURCE_NOT_FOUND", () -> deletion.execute(victim, preview.previewToken()));
        asUser(user);
        if (store.findLatestLedger(other, "MONTH", "2026-06").isEmpty()) {
            throw new AssertionError("other user ledger missing before delete");
        }
        deletion.execute(victim, preview.previewToken());

        eq(String.valueOf(count("SELECT COUNT(*) FROM accounts WHERE id = :user", victim)), "0");
        eq(String.valueOf(count("""
                SELECT COUNT(*) FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'LEDGER'
                """, user)), "0");
        eq(String.valueOf(count("""
                SELECT COUNT(*) FROM analysis_generations
                WHERE user_id = :user AND report_scope = 'LEDGER'
                """, user)), "0");
        eq(jdbc.sql("""
                SELECT monthly_overview FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'LEGACY' AND account_id IS NULL
                """).param("user", user).query(String.class).single(), "LEGACY-STAYS");
        eq(jdbc.sql("""
                SELECT monthly_overview FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'ACCOUNT' AND account_id = :account
                """).param("user", user).param("account", kept).query(String.class).single(), "KEPT-ACCOUNT");
        eq(store.findLatestLedger(other, "MONTH", "2026-06").orElseThrow().report().monthlyOverview(), "LEDGER-SNAPSHOT");
        eq(String.valueOf(quota(user, "consumed_count")), String.valueOf(consumed));
        Report after = statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null);
        eq(after.income().net(), "12.00");
        if (after.accounts().stream().anyMatch(item -> item.id() == victim)) {
            throw new AssertionError("deleted account still in statistics");
        }
        asUser(other);
        eq(statistics.report("CUSTOM", "2026-10-01", "2026-10-31", "ALL", null).income().net(), "3.00");
        if (store.findLatestLedger(user, "MONTH", "2026-10").isPresent()
                || store.findLatestLedger(user, "YEAR", "2026").isPresent()) {
            throw new AssertionError("deleted user's ledger history still readable");
        }
        System.out.println("S5T06_OBS schemeB afterIncome=" + after.income().net()
                + " quota=" + quota(user, "consumed_count"));
    }

    private void rollback() {
        long user = user("s5t06-rollback");
        long victim = account(user, "s5t06-rollback-victim", "0.00", "ARCHIVED");
        tx(user, victim, "INCOME", "8.00", "2026-10-04 10:00:00", null, null, null, null);
        completeLedger(user, "MONTH", "2026-03", "fp-rollback-month");
        long snapshotId = jdbc.sql("""
                SELECT id FROM analysis_snapshots
                WHERE user_id = :user AND report_scope = 'LEDGER' AND analysis_month = '2026-03'
                """).param("user", user).query(Long.class).single();
        long other = idOf("s5t06-reader");
        long otherAccount = jdbc.sql("SELECT id FROM accounts WHERE user_id = :user")
                .param("user", other).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO analysis_generations
                    (user_id, account_id, report_scope, period_kind, analysis_month, context_fingerprint, state,
                     fencing_token, permit_date, lease_expires_at_utc, quota_reserved, snapshot_id, created_at_utc, updated_at_utc)
                VALUES
                    (:user, :account, 'ACCOUNT', 'MONTH', '2026-07', 'fp-rollback-fk', 'FAILED',
                     1, '2026-10-26', UTC_TIMESTAMP(6), 0, :snapshot, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """).param("user", other).param("account", otherAccount).param("snapshot", snapshotId).update();
        asUser(user);
        var preview = deletion.preview(victim);
        RuntimeException thrown = null;
        try {
            deletion.execute(victim, preview.previewToken());
        } catch (RuntimeException ex) {
            thrown = ex;
        }
        if (thrown == null) throw new AssertionError("delete succeeded while a foreign snapshot reference existed");
        System.out.println("S5T06_OBS rollback " + thrown.getClass().getSimpleName()
                + (thrown instanceof ApiFailure failure ? " " + failure.code() : ""));
        eq(String.valueOf(count("SELECT COUNT(*) FROM accounts WHERE id = :user", victim)), "1");
        eq(textOf(snapshotId), "LEDGER-SNAPSHOT");
        eq(String.valueOf(count("""
                SELECT COUNT(*) FROM analysis_generations
                WHERE user_id = :user AND report_scope = 'LEDGER'
                """, user)), "1");
        eq(String.valueOf(count("SELECT COUNT(*) FROM transactions WHERE account_id = :user", victim)), "1");
    }

    private void drillUntouched() {
        eq(username(1L), "v18-drill");
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :user", 1L)), "2");
        eq(String.valueOf(count("SELECT COUNT(*) FROM analysis_generations WHERE user_id = :user", 1L)), "2");
        eq(String.valueOf(count("SELECT COUNT(*) FROM accounts WHERE user_id = :user", 1L)), "1");
    }

    private LedgerAnalysisService stubService(AtomicInteger calls) {
        return stubService(calls, new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    private LedgerAnalysisService stubService(AtomicInteger calls, java.util.concurrent.atomic.AtomicBoolean fail) {
        MonthlyAnalysisGenerator stub = new MonthlyAnalysisGenerator() {
            @Override
            public MonthlyAnalysisGeneration generate(MonthlyAnalysisModelInput input, String systemPrompt) {
                throw new IllegalStateException("refusing object model input");
            }

            @Override
            public MonthlyAnalysisGeneration generateJson(String userJson, String systemPrompt) {
                calls.incrementAndGet();
                if (userJson == null || systemPrompt == null) throw new IllegalArgumentException("payload required");
                if (fail.get()) throw new AnalysisGenerationFailed(AnalysisGenerationFailed.Kind.PROVIDER_ERROR, null);
                return new MonthlyAnalysisGeneration("local-mock", "s5t06-stub",
                        "{\"surplusReasons\":\"stub-surplus\",\"pointsToWatch\":\"stub-points\"}",
                        null, null, null, 1L, null);
            }
        };
        return new LedgerAnalysisService(factsService, store, stub, () -> { },
                new TrustedUserContext(), Clock.systemUTC());
    }

    private void completeAccount(long user, long account, String month, String fingerprint) {
        var granted = (PermitAcquisition.Granted) store.acquire(user, account, month, fingerprint);
        var applied = store.completeSuccess(granted.permit(), report("ACCOUNT-SNAPSHOT"), provenance());
        if (!(applied instanceof SuccessSettlement.Applied)) throw new AssertionError("account success was not applied");
    }

    private void completeLedger(long user, String kind, String period, String fingerprint) {
        var granted = (LedgerPermitAcquisition.Granted) store.acquireLedger(user, kind, period, fingerprint);
        var applied = store.completeLedgerSuccess(granted.permit(), report("LEDGER-SNAPSHOT"), provenance());
        if (!(applied instanceof SuccessSettlement.Applied)) throw new AssertionError("ledger success was not applied");
    }

    private static MonthlyAnalysisReport report(String overview) {
        return new MonthlyAnalysisReport(overview, "结构", "对比", "关注");
    }

    private static AnalysisProvenance provenance() {
        return new AnalysisProvenance("local-mock", "s5t06-stub", "ledger-v1", null, null, null, 1L, null);
    }

    private void insertSnapshot(long user, Long account, String scope, String kind, String period, String fingerprint, String overview) {
        requireDb();
        jdbc.sql("""
                INSERT INTO analysis_snapshots
                    (user_id, account_id, report_scope, period_kind, analysis_month, context_fingerprint,
                     monthly_overview, expense_structure, surplus_reasons, points_to_watch, provider, model,
                     prompt_version, latency_millis, permit_date, generated_at_utc)
                VALUES
                    (:user, :account, :scope, :kind, :period, :fingerprint, :overview, '结构', '对比', '关注',
                     'local-mock', 's5t06-stub', 'ledger-v1', 1, '2026-10-26', UTC_TIMESTAMP(6))
                """).param("user", user).param("account", account).param("scope", scope).param("kind", kind)
                .param("period", period).param("fingerprint", fingerprint).param("overview", overview).update();
    }

    private void insertGeneration(long user, Long account, String scope, String kind, String period, String fingerprint, String state) {
        requireDb();
        jdbc.sql("""
                INSERT INTO analysis_generations
                    (user_id, account_id, report_scope, period_kind, analysis_month, context_fingerprint, state,
                     fencing_token, permit_date, lease_expires_at_utc, quota_reserved, created_at_utc, updated_at_utc)
                VALUES
                    (:user, :account, :scope, :kind, :period, :fingerprint, :state, 1, '2026-10-26',
                     UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """).param("user", user).param("account", account).param("scope", scope).param("kind", kind)
                .param("period", period).param("fingerprint", fingerprint).param("state", state).update();
    }

    private long user(String username) {
        requireDb();
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO users (username, password_hash, created_at_utc, updated_at_utc)
                VALUES (:name, 's5t06-hash', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """).param("name", username).update(keys);
        return keys.getKey().longValue();
    }

    private long account(long user, String name, String opening, String lifecycle) {
        requireDb();
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO accounts
                    (user_id, name, kind, currency, opening_balance, created_time, last_first_save_order,
                     updated_at_utc, lifecycle)
                VALUES
                    (:user, :name, 'NORMAL', 'CNY', :opening, '2020-01-01 00:00:00', 0, UTC_TIMESTAMP(6), :lifecycle)
                """).param("user", user).param("name", name).param("opening", opening).param("lifecycle", lifecycle)
                .update(keys);
        return keys.getKey().longValue();
    }

    private long category(long user, String type, String name, Long parent) {
        requireDb();
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO categories (user_id, type, name, parent_id, created_at_utc)
                VALUES (:user, :type, :name, :parent, UTC_TIMESTAMP(6))
                """).param("user", user).param("type", type).param("name", name).param("parent", parent).update(keys);
        return keys.getKey().longValue();
    }

    private long tag(long user, String name, Long parent) {
        requireDb();
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO tags (user_id, name, parent_id, created_at_utc)
                VALUES (:user, :name, :parent, UTC_TIMESTAMP(6))
                """).param("user", user).param("name", name).param("parent", parent).update(keys);
        return keys.getKey().longValue();
    }

    private final java.util.Map<Long, Integer> orders = new java.util.HashMap<>();

    private long tx(long user, long account, String type, String amount, String businessTime, Long category,
            Long ignoredTag, String name, String remark) {
        requireDb();
        int order = orders.merge(account, 1, Integer::sum);
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO transactions
                    (user_id, account_id, type, amount, name, category_id, business_time, first_saved_at_utc,
                     first_save_order, updated_at_utc, remark)
                VALUES
                    (:user, :account, :type, :amount, :name, :category, :businessTime, UTC_TIMESTAMP(6),
                     :orderNo, UTC_TIMESTAMP(6), :remark)
                """).param("user", user).param("account", account).param("type", type).param("amount", amount)
                .param("name", name).param("category", category).param("businessTime", businessTime)
                .param("orderNo", order).param("remark", remark).update(keys);
        return keys.getKey().longValue();
    }

    private void tagTx(long transaction, long tag) {
        requireDb();
        jdbc.sql("INSERT INTO transaction_tags (transaction_id, tag_id) VALUES (:tx, :tag)")
                .param("tx", transaction).param("tag", tag).update();
    }

    private void adjust(long user, long account, String amount, String businessTime) {
        requireDb();
        int order = orders.merge(account, 1, Integer::sum);
        jdbc.sql("""
                INSERT INTO balance_adjustments
                    (user_id, account_id, adjustment_amount, business_time, first_saved_at_utc, first_save_order, remark)
                VALUES (:user, :account, :amount, :businessTime, UTC_TIMESTAMP(6), :orderNo, NULL)
                """).param("user", user).param("account", account).param("amount", amount)
                .param("businessTime", businessTime).param("orderNo", order).update();
    }

    private void transfer(long user, long from, long to, String amount, String fromTime, String toTime) {
        requireDb();
        int order = orders.merge(from, 1, Integer::sum);
        jdbc.sql("""
                INSERT INTO transfers
                    (user_id, from_account_id, to_account_id, amount, from_business_time, to_business_time,
                     first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES
                    (:user, :fromId, :toId, :amount, :fromTime, :toTime, UTC_TIMESTAMP(6), :orderNo, UTC_TIMESTAMP(6))
                """).param("user", user).param("fromId", from).param("toId", to).param("amount", amount)
                .param("fromTime", fromTime).param("toTime", toTime).param("orderNo", order).update();
    }

    private int quota(long user, String column) {
        Integer value = jdbc.sql("SELECT " + column + " FROM analysis_quota_days WHERE user_id = :user")
                .param("user", user).query(Integer.class).optional().orElse(0);
        return value;
    }

    private int count(String sql, long user) {
        return jdbc.sql(sql).param("user", user).query(Integer.class).single();
    }

    private String textOf(long snapshotId) {
        return jdbc.sql("SELECT monthly_overview FROM analysis_snapshots WHERE id = :id")
                .param("id", snapshotId).query(String.class).single();
    }

    private String username(long id) {
        return jdbc.sql("SELECT username FROM users WHERE id = :id").param("id", id).query(String.class).single();
    }

    private long idOf(String username) {
        return jdbc.sql("SELECT id FROM users WHERE username = :name").param("name", username).query(Long.class).single();
    }

    private void requireDb() {
        String database = jdbc.sql("SELECT DATABASE()").query(String.class).single();
        if (!SCHEMA.equals(database)) throw new IllegalStateException("ABORT DATABASE()=" + database);
    }

    private void asUser(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(userId, 1L), null, List.of()));
    }

    private void expectApi(int status, String code, Runnable action) {
        try {
            action.run();
        } catch (ApiFailure ex) {
            if (ex.status() == status && code.equals(ex.code())) return;
            throw new AssertionError("expected " + status + " " + code + " but was " + ex.status() + " " + ex.code(), ex);
        }
        throw new AssertionError("expected API failure " + code);
    }

    private void expectSql(int code, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException ex) {
            int actual = sqlCode(ex);
            if (actual != code) throw new AssertionError("expected SQL " + code + " but was " + actual, ex);
            return;
        }
        throw new AssertionError("expected SQL " + code);
    }

    private static int sqlCode(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sql) return sql.getErrorCode();
            current = current.getCause();
        }
        return -1;
    }

    private static CategoryNode categoryNamed(List<CategoryNode> nodes, String name) {
        for (CategoryNode node : nodes) {
            if (name.equals(node.name())) return node;
            CategoryNode child = categoryNamed(node.children(), name);
            if (child != null) return child;
        }
        throw new AssertionError("missing category " + name);
    }

    private static TagNode tagNamed(List<TagNode> nodes, String name) {
        for (TagNode node : nodes) {
            if (name.equals(node.name())) return node;
            TagNode child = tagNamed(node.children(), name);
            if (child != null) return child;
        }
        throw new AssertionError("missing tag " + name);
    }

    private static void eq(String actual, String expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but was " + actual);
    }

    private static void check(List<String> failures, String name, Section section) {
        try {
            section.run();
            System.out.println("S5T06_CHECK_PASS " + name);
        } catch (Throwable ex) {
            failures.add(name + ": " + ex.getMessage());
            System.out.println("S5T06_CHECK_FAIL " + name + " " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface Section {
        void run() throws Exception;
    }

    private record AdminClientFile(String user, String password) {
        static AdminClientFile read() {
            try {
                Path path = Path.of(System.getProperty("user.home"), ".config", "bookkeeping", "mysql-admin.cnf");
                String user = null;
                String password = null;
                for (String line : Files.readString(path).split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("user=")) user = unquote(trimmed.substring("user=".length()).trim());
                    else if (trimmed.startsWith("password=")) password = unquote(trimmed.substring("password=".length()).trim());
                }
                if (user == null || user.isBlank() || password == null || password.isBlank()) {
                    throw new IllegalStateException("admin client file is incomplete");
                }
                return new AdminClientFile(user, password);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("admin client file is unreadable", ex);
            }
        }

        private static String unquote(String value) {
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }
    }
}
