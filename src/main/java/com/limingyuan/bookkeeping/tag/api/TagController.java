package com.limingyuan.bookkeeping.tag.api;

import com.limingyuan.bookkeeping.common.api.StrictStringDeserializer;
import com.limingyuan.bookkeeping.tag.application.TagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {
    private final TagService tags;

    public TagController(TagService tags) { this.tags = tags; }

    public record TagList(List<TagService.TagView> items) { }

    public record CreateRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String type,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name) { }

    @GetMapping
    public TagList list(@RequestParam String type) {
        return new TagList(tags.list(type));
    }

    @PostMapping
    public ResponseEntity<TagService.TagView> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(tags.create(request.type(), request.name()));
    }
}
