alter table post
    add column rendered_html text,
    add column prose_character_count integer;

update post
set rendered_html = '<p>' || replace(replace(replace(replace(replace(
        prose_markdown, '&', '&amp;'), '<', '&lt;'), '>', '&gt;'), '"', '&quot;'), '''', '&#39;') || '</p>',
    prose_character_count = char_length(prose_markdown);

alter table post
    alter column rendered_html set not null,
    alter column prose_character_count set not null,
    add constraint post_prose_character_count_non_negative check (prose_character_count >= 0);
