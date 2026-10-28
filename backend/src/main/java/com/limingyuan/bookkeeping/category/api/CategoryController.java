package com.limingyuan.bookkeeping.category.api;

import com.limingyuan.bookkeeping.category.application.CategoryService;
import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryService categories;
    public CategoryController(CategoryService categories) { this.categories = categories; }
    public record CategoryList(List<CategoryService.CategoryView> items) { }
    public record CreateRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String type,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name,
            Long parentId) { }
    public record RenameRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name) { }
    public record MoveRequest(Long parentId) { }
    public record DeletionRequest(@NotNull Integer directTransactionCount) { }

    @GetMapping
    public CategoryList list(@RequestParam String type) { return new CategoryList(categories.list(type)); }

    @PostMapping
    public ResponseEntity<CategoryService.CategoryView> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(categories.create(request.type(), request.name(), request.parentId()));
    }

    @PostMapping("/{categoryId}/rename")
    public CategoryService.CategoryView rename(@PathVariable long categoryId, @Valid @RequestBody RenameRequest request) {
        return categories.rename(categoryId, request.name());
    }

    @PostMapping("/{categoryId}/move")
    public CategoryService.CategoryView move(@PathVariable long categoryId, @Valid @RequestBody MoveRequest request) {
        return categories.move(categoryId, request.parentId());
    }

    @PostMapping("/{categoryId}/deletion-preview")
    public CategoryService.DeletionPreview deletionPreview(@PathVariable long categoryId) {
        return categories.deletionPreview(categoryId);
    }

    @PostMapping("/{categoryId}/deletion")
    public ResponseEntity<Void> delete(@PathVariable long categoryId, @Valid @RequestBody DeletionRequest request) {
        categories.delete(categoryId, request.directTransactionCount());
        return ResponseEntity.noContent().build();
    }
}
