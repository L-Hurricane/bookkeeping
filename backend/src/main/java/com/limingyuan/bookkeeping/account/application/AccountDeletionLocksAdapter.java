package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AccountDeletionLocksAdapter implements AccountDeletionLocks {
    private final AccountService accounts;

    public AccountDeletionLocksAdapter(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public List<AccountEntity> lockOwnedInIdOrder(Collection<Long> accountIds) {
        return accounts.lockOwnedInIdOrder(accountIds);
    }
}
