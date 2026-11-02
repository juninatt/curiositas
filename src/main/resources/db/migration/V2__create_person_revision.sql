-- Numbers every change in the dataset, in the order the changes were made. A separate sequence,
-- so that revisions of other kinds of records can share the same numbering later.
create sequence revision_sequence;

-- Every change to a person, kept after the person is deleted, so the application owns the full
-- history of the data. Each revision holds the whole person as it was after the change (before
-- it, for a deletion), in the same form as the data files.
create table person_revision
(
    sequence_number bigint primary key,
    -- Not a foreign key: the history must outlive the person it describes.
    person_id       uuid        not null,
    revision        integer     not null,
    change_type     varchar(10) not null,
    source          varchar(10) not null,
    changed_at      timestamptz not null,
    snapshot        jsonb       not null,

    constraint person_revision_once_per_number unique (person_id, revision),
    constraint person_revision_revision_positive check (revision >= 1),
    constraint person_revision_change_type_valid check (change_type in ('CREATED', 'UPDATED', 'DELETED')),
    constraint person_revision_source_valid check (source in ('API', 'IMPORT', 'WIKIDATA'))
);
