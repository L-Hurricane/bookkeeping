package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import java.util.Collection;
import java.util.List;

/** Ascending account-row locks for permanent deletion writes. */
public interface AccountDeletionLocks {
    List<AccountEntity> lockOwnedInIdOrder(Collection<Long> accountIds);
}
