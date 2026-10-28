package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionPreviewToken.TransferBasis;
import com.limingyuan.bookkeeping.common.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class AccountPermanentDeletionImpacts {
    private AccountPermanentDeletionImpacts() { }

    record CounterpartImpact(long accountId, String name, BigDecimal balanceBefore, BigDecimal balanceAfter, BigDecimal change) { }

    static List<CounterpartImpact> counterpartImpacts(
            long deletedAccountId,
            List<TransferBasis> transfers,
            Map<Long, String> accountNames,
            Map<Long, BigDecimal> balanceBefore) {
        if (transfers.isEmpty()) {
            return List.of();
        }
        var counterpartIds = new TreeSet<Long>();
        for (var transfer : transfers) {
            if (transfer.fromAccountId() != deletedAccountId) {
                counterpartIds.add(transfer.fromAccountId());
            }
            if (transfer.toAccountId() != deletedAccountId) {
                counterpartIds.add(transfer.toAccountId());
            }
        }
        var impacts = new ArrayList<CounterpartImpact>();
        for (long counterpartId : counterpartIds) {
            BigDecimal before = balanceBefore.get(counterpartId);
            BigDecimal change = BigDecimal.ZERO;
            for (var transfer : transfers) {
                if (transfer.toAccountId() == counterpartId) {
                    change = change.subtract(transfer.amount());
                }
                if (transfer.fromAccountId() == counterpartId) {
                    change = change.add(transfer.amount());
                }
            }
            BigDecimal after = before.add(change);
            impacts.add(new CounterpartImpact(counterpartId, accountNames.get(counterpartId), before, after, change));
        }
        impacts.sort(Comparator.comparingLong(CounterpartImpact::accountId));
        return impacts;
    }

    static Map<Long, BigDecimal> counterpartBalanceBefore(long deletedAccountId, List<TransferBasis> transfers,
            java.util.function.LongFunction<BigDecimal> balanceLookup) {
        var ids = new TreeSet<Long>();
        for (var transfer : transfers) {
            if (transfer.fromAccountId() != deletedAccountId) {
                ids.add(transfer.fromAccountId());
            }
            if (transfer.toAccountId() != deletedAccountId) {
                ids.add(transfer.toAccountId());
            }
        }
        var balances = new LinkedHashMap<Long, BigDecimal>();
        for (long id : ids) {
            balances.put(id, balanceLookup.apply(id));
        }
        return balances;
    }

    static List<AccountPermanentDeletionService.AffectedAccountView> views(List<CounterpartImpact> impacts) {
        return impacts.stream()
                .map(impact -> new AccountPermanentDeletionService.AffectedAccountView(
                        impact.accountId(),
                        impact.name(),
                        Money.format(impact.balanceBefore()),
                        Money.format(impact.balanceAfter()),
                        Money.format(impact.change())))
                .toList();
    }
}
