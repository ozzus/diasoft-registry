alter table diplomas
    add column if not exists graduation_year integer,
    add column if not exists record_hash varchar(64),
    add column if not exists revoked_at timestamptz,
    add column if not exists revoke_reason text;

create index if not exists idx_diplomas_student_id on diplomas(student_id);
create index if not exists idx_students_external_id on students(external_id);

insert into students (id, external_id, full_name, created_at)
values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'ITMO:D-2026-0001', 'Иван Иванов', now())
on conflict (id) do nothing;

insert into diplomas (
    id,
    university_id,
    student_id,
    diploma_number,
    program_name,
    graduation_year,
    record_hash,
    status,
    created_at,
    updated_at
)
values (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    '11111111-1111-1111-1111-111111111111',
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'D-2026-0001',
    'Software Engineering',
    2026,
    'd46ed54723c438a62b1e9e95b9d08ac165edd9e7a3ab0fff2e05651887e7e8ee',
    'valid',
    now(),
    now()
)
on conflict (id) do nothing;

insert into verification_tokens (id, diploma_id, token, is_active, created_at)
values (
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    'itmo-demo-verify-token',
    true,
    now()
)
on conflict (token) do nothing;
