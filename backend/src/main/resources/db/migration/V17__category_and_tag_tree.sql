ALTER TABLE tags
    ADD UNIQUE INDEX uk_tags_id_user (id, user_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags
    ADD COLUMN parent_id BIGINT NULL,
    ALGORITHM=INSTANT;

ALTER TABLE tags
    ADD INDEX idx_tags_parent_user (parent_id, user_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags
    ADD CONSTRAINT fk_tags_parent
        FOREIGN KEY (parent_id, user_id) REFERENCES tags (id, user_id),
    ALGORITHM=COPY, LOCK=SHARED;

UPDATE categories AS child
    INNER JOIN categories AS parent
        ON parent.id = child.parent_id
       AND parent.user_id = child.user_id
       AND parent.type = 'EXPENSE'
       AND parent.name = '交通'
SET child.name = '其他交通'
WHERE child.type = 'EXPENSE'
  AND child.name = '其他';

ALTER TABLE categories
    DROP INDEX uk_categories_scope_name,
    ADD CONSTRAINT uk_categories_user_type_name UNIQUE (user_id, type, name),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags DROP INDEX idx_tags_user_type;

ALTER TABLE tags DROP CHECK ck_tags_type;

ALTER TABLE tags
    DROP COLUMN type,
    ALGORITHM=INSTANT;
