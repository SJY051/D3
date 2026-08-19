-- Comments carry user markdown; store server-sanitized HTML once at write, mirroring post rendering.
-- The comment interaction was never activated, so the table is empty and the column can be added NOT NULL.
alter table comment add column rendered_html text not null default '';
