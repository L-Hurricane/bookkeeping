package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.account.domain.AccountLifecycle;
import com.limingyuan.bookkeeping.account.persistence.AccountEntity;
import com.limingyuan.bookkeeping.account.persistence.AccountGroupEntity;
import com.limingyuan.bookkeeping.account.persistence.AccountGroupRepository;
import com.limingyuan.bookkeeping.account.persistence.AccountRepository;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountGroupService {
    static final String UNGROUPED = "未分组";
    private final AccountGroupRepository groups;
    private final AccountRepository accounts;
    private final CurrentBalance balances;
    private final TrustedUserContext context;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AccountGroupService(AccountGroupRepository groups, AccountRepository accounts, CurrentBalance balances,
            TrustedUserContext context, Clock clock, PlatformTransactionManager transactionManager) {
        this.groups = groups;
        this.accounts = accounts;
        this.balances = balances;
        this.context = context;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record GroupView(long id, String name, long displayOrder, String subtotal, int activeCount, int archivedCount) { }
    public record GroupList(List<GroupView> items) { }

    public GroupList list() {
        long userId = context.userId();
        var items = groups.findAllByUserIdOrderByDisplayOrderAscIdAsc(userId).stream().map(group -> view(group, userId)).toList();
        return new GroupList(items);
    }

    public GroupView create(String rawName) {
        long userId = context.userId();
        String name = canonicalName(rawName);
        try {
            return transaction.execute(status -> {
                if (groups.existsByUserIdAndName(userId, name)) throw nameConflict();
                long order = groups.maxDisplayOrder(userId) + 1;
                var saved = groups.saveAndFlush(new AccountGroupEntity(userId, name, order));
                return view(saved, userId);
            });
        } catch (DataIntegrityViolationException ex) {
            if (groups.existsByUserIdAndName(userId, name)) throw nameConflict();
            throw ex;
        }
    }

    public GroupView rename(long groupId, String rawName) {
        long userId = context.userId();
        String name = canonicalName(rawName);
        try {
            return transaction.execute(status -> {
                var group = groups.lockByIdAndUserId(groupId, userId).orElseThrow(AccountGroupService::missing);
                if (!group.getName().equals(name) && groups.existsByUserIdAndNameAndIdNot(userId, name, groupId)) {
                    throw nameConflict();
                }
                group.rename(name);
                return view(groups.saveAndFlush(group), userId);
            });
        } catch (DataIntegrityViolationException ex) {
            if (groups.existsByUserIdAndNameAndIdNot(userId, name, groupId)) throw nameConflict();
            throw ex;
        }
    }

    public void delete(long groupId) {
        long userId = context.userId();
        try {
            transaction.executeWithoutResult(status -> {
                var group = groups.lockByIdAndUserId(groupId, userId).orElseThrow(AccountGroupService::missing);
                accounts.clearGroup(userId, group.getId(), utcNow());
                groups.delete(group);
                groups.flush();
            });
        } catch (DataIntegrityViolationException ex) {
            throw new ApiFailure(409, "GROUP_DELETE_CONFLICT", "分组仍有账户，未能删除");
        }
    }

    public GroupList reorder(List<Long> ids) {
        long userId = context.userId();
        requireDistinct(ids, "排序必须包含全部自定义分组");
        transaction.executeWithoutResult(status -> {
            var locked = groups.lockAllByUserId(userId);
            var expected = new HashSet<Long>();
            Map<Long, AccountGroupEntity> byId = new LinkedHashMap<>();
            for (var group : locked) {
                expected.add(group.getId());
                byId.put(group.getId(), group);
            }
            if (!expected.equals(new HashSet<>(ids))) {
                throw new ApiFailure(400, "VALIDATION_FAILED", "排序必须包含全部自定义分组");
            }
            long order = 1;
            for (long id : ids) byId.get(id).setDisplayOrder(order++);
            groups.saveAllAndFlush(locked);
        });
        return list();
    }

    /** Move one account and persist the destination partition order in the same transaction. */
    public void place(long accountId, Long groupId, List<Long> orderedIds) {
        long userId = context.userId();
        requireDistinct(orderedIds, "排序必须包含该分组的全部账户");
        if (!orderedIds.contains(accountId)) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "排序必须包含该分组的全部账户");
        }
        transaction.executeWithoutResult(status -> {
            if (groupId != null) {
                groups.lockByIdAndUserId(groupId, userId).orElseThrow(AccountGroupService::missing);
            }
            var locked = groupId == null
                    ? accounts.lockUngroupedPartition(userId, accountId)
                    : accounts.lockPartition(userId, accountId, groupId);
            var expected = new HashSet<Long>();
            Map<Long, AccountEntity> byId = new LinkedHashMap<>();
            boolean containsAccount = false;
            for (var account : locked) {
                expected.add(account.getId());
                byId.put(account.getId(), account);
                if (account.getId() == accountId) containsAccount = true;
            }
            if (!containsAccount) throw missing();
            if (!expected.equals(new HashSet<>(orderedIds))) {
                throw new ApiFailure(400, "VALIDATION_FAILED", "排序必须包含该分组的全部账户");
            }
            var now = utcNow();
            long order = 1;
            for (long id : orderedIds) byId.get(id).place(groupId, order++, now);
            accounts.saveAllAndFlush(byId.values());
        });
    }

    private GroupView view(AccountGroupEntity group, long userId) {
        var members = accounts.findAllByUserIdOrderByIdAsc(userId).stream()
                .filter(account -> group.getId().equals(account.getGroupId()))
                .toList();
        int active = 0;
        int archived = 0;
        BigDecimal subtotal = BigDecimal.ZERO.setScale(2);
        for (var member : members) {
            if (member.getLifecycle() == AccountLifecycle.ARCHIVED) {
                archived++;
            } else {
                active++;
                subtotal = subtotal.add(balances.of(member.getId()));
            }
        }
        return new GroupView(group.getId(), group.getName(), group.getDisplayOrder(), Money.format(subtotal), active, archived);
    }

    private static String canonicalName(String rawName) {
        if (rawName == null) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "分组名称去除首尾空白后不能为空，且最长 64 个字符");
        }
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "分组名称去除首尾空白后不能为空，且最长 64 个字符");
        }
        if (UNGROUPED.equals(name)) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "分组名称不能为未分组");
        }
        return name;
    }

    private static void requireDistinct(List<Long> ids, String message) {
        if (ids == null || ids.isEmpty() || ids.contains(null) || new HashSet<>(ids).size() != ids.size()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", message);
        }
    }

    private static ApiFailure nameConflict() {
        return new ApiFailure(409, "GROUP_NAME_CONFLICT", "分组名称已存在");
    }

    private static ApiFailure missing() {
        return new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
