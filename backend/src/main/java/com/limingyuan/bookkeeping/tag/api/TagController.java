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
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name,
            Long parentId) { }
    public record RenameRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String type,
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String name) { }
    public record MoveRequest(
            @NotNull @JsonDeserialize(using = StrictStringDeserializer.class) String type,
            Long parentId) { }
    public record DeletionRequest(@NotNull Integer directTransactionCount) { }

    @GetMapping
    public TagList list(@RequestParam String type) {
        return new TagList(tags.list(type));
    }

    @PostMapping
    public ResponseEntity<TagService.TagView> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(tags.create(request.type(), request.name(), request.parentId()));
    }

    @PostMapping("/{tagId}/rename")
    public TagService.TagView rename(@PathVariable long tagId, @Valid @RequestBody RenameRequest request) {
        return tags.rename(tagId, request.type(), request.name());
    }

    @PostMapping("/{tagId}/move")
    public TagService.TagView move(@PathVariable long tagId, @Valid @RequestBody MoveRequest request) {
        return tags.move(tagId, request.type(), request.parentId());
    }

    @PostMapping("/{tagId}/deletion-preview")
    public TagService.DeletionPreview deletionPreview(@PathVariable long tagId) {
        return tags.deletionPreview(tagId);
    }

    @PostMapping("/{tagId}/deletion")
    public ResponseEntity<Void> delete(@PathVariable long tagId, @Valid @RequestBody DeletionRequest request) {
        tags.delete(tagId, request.directTransactionCount());
        return ResponseEntity.noContent().build();
    }
}
