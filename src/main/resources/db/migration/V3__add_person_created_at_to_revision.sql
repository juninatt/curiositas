-- When the person was first stored, kept with every revision so a change can show the person
-- exactly as the API does, creation time included, also after the person is deleted.
alter table person_revision add column person_created_at timestamptz;

-- Existing revisions take the time from the person, or, for a deleted person, from its first
-- revision, which is the closest record of when it was stored.
update person_revision revision
set person_created_at = person.created_at
from person
where person.id = revision.person_id;

update person_revision revision
set person_created_at = first.changed_at
from person_revision first
where revision.person_created_at is null
  and first.person_id = revision.person_id
  and first.revision = 1;

alter table person_revision alter column person_created_at set not null;
