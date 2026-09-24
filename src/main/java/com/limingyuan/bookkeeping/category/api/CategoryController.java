package com.limingyuan.bookkeeping.category.api;

import com.limingyuan.bookkeeping.category.application.CategoryService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryService categories;
    public CategoryController(CategoryService categories) { this.categories = categories; }
    public record CategoryList(List<CategoryService.CategoryView> items) { }
    @GetMapping
    public CategoryList list(@RequestParam String type) { return new CategoryList(categories.list(type)); }
}
