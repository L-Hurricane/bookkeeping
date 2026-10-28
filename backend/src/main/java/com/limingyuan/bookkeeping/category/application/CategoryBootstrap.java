package com.limingyuan.bookkeeping.category.application;

import com.limingyuan.bookkeeping.category.domain.*;
import com.limingyuan.bookkeeping.category.persistence.*;
import com.limingyuan.bookkeeping.identity.application.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(100)
public class CategoryBootstrap implements UserPreparation, ApplicationRunner {
    private final CategoryRepository categories;
    private final UserDirectory users;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public CategoryBootstrap(CategoryRepository categories, UserDirectory users, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.categories = categories; this.users = users; this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }
    @Override
    public void run(ApplicationArguments args) {
        // Upgrade existing Phase 1 users even when initial-password bootstrap is disabled.
        users.existingIds().forEach(this::prepare);
    }
    @Override
    public void prepare(long userId) {
        transaction.executeWithoutResult(status -> {
            users.lockForPreparation(userId);
            var existing = new ArrayList<>(categories.findAllByUserIdOrderById(userId));
            if (!existing.isEmpty()) return;
            var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            for (var root : FixedCategories.ROOTS) {
                var parent = ensure(existing, userId, root.type(), root.name(), null, now);
                for (String child : root.children()) {
                    ensure(existing, userId, root.type(), child, parent.getId(), now);
                }
            }
        });
    }
    private CategoryEntity ensure(List<CategoryEntity> existing, long userId, CategoryType type,
            String name, Long parentId, LocalDateTime now) {
        var found = existing.stream().filter(c -> Objects.equals(c.getParentId(), parentId) && c.getName().equals(name)).findFirst();
        if (found.isPresent()) {
            if (found.get().getType() != type) throw new IllegalStateException("Stored category type differs from the fixed baseline");
            return found.get();
        }
        var created = categories.saveAndFlush(new CategoryEntity(userId, type, name, parentId, now));
        existing.add(created);
        return created;
    }
}
