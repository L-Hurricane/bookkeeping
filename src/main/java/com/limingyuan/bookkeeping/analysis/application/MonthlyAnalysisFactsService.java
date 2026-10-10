package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.UncategorizedFact;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.CategorySlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFactsReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MonthlyAnalysisFactsService {
    private final MonthlyBookkeepingFactsReader facts;

    public MonthlyAnalysisFactsService(MonthlyBookkeepingFactsReader facts) {
        this.facts = facts;
    }

    public MonthlyAnalysisContext build(long accountId, String yearMonth) {
        return context(facts.read(accountId, yearMonth));
    }

    private static MonthlyAnalysisContext context(MonthlyBookkeepingFacts facts) {
        return new MonthlyAnalysisContext(facts.accountId(), facts.yearMonth(), MonthlyAnalysisContext.TIME_ZONE,
                MonthlyAnalysisContext.CURRENCY, facts.netIncome(), facts.netExpense(), facts.surplus(),
                facts.incomeTransactionCount(), facts.expenseTransactionCount(), categories(facts.incomeCategories()),
                categories(facts.expenseCategories()), uncategorized(facts.uncategorizedIncome()),
                uncategorized(facts.uncategorizedExpense()), keyExpenses(facts.keyExpenses()));
    }

    private static List<CategoryFact> categories(List<CategorySlice> slices) {
        var facts = new ArrayList<CategoryFact>();
        for (var slice : slices) facts.add(category(slice));
        return facts;
    }

    private static CategoryFact category(CategorySlice slice) {
        return new CategoryFact(slice.categoryId(), slice.name(), slice.transactionCount(), slice.netAmount(),
                categories(slice.children()));
    }

    private static UncategorizedFact uncategorized(MonthlyBookkeepingFacts.UncategorizedSlice slice) {
        return new UncategorizedFact(slice.transactionCount(), slice.netAmount());
    }

    private static List<KeyExpenseFact> keyExpenses(List<MonthlyBookkeepingFacts.KeyExpenseSlice> slices) {
        var facts = new ArrayList<KeyExpenseFact>();
        for (var slice : slices) {
            facts.add(new KeyExpenseFact(slice.transactionType(), slice.categoryPath(), slice.transactionName(),
                    slice.signedAmount(), slice.businessTime()));
        }
        return facts;
    }
}
