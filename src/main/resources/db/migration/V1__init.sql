create table if not exists universities (
    id uuid primary key,
    code varchar(64) not null unique,
    name varchar(255) not null,
    created_at timestamptz not null default now()
);

create table if not exists students (
    id uuid primary key,
    external_id varchar(128),
    full_name varchar(255) not null,
    created_at timestamptz not null default now()
);

create table if not exists diplomas (
    id uuid primary key,
    university_id uuid not null references universities(id),
    student_id uuid not null references students(id),
    diploma_number varchar(128) not null,
    program_name varchar(255) not null,
    status varchar(32) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (university_id, diploma_number)
);

create table if not exists verification_tokens (
    id uuid primary key,
    diploma_id uuid not null references diplomas(id),
    token varchar(255) not null unique,
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists share_tokens (
    id uuid primary key,
    diploma_id uuid not null references diplomas(id),
    token varchar(255) not null unique,
    expires_at timestamptz not null,
    max_views integer,
    used_views integer not null default 0,
    status varchar(32) not null,
    created_at timestamptz not null default now()
);

create table if not exists revocations (
    id uuid primary key,
    diploma_id uuid not null references diplomas(id),
    reason text,
    created_at timestamptz not null default now()
);

create table if not exists import_jobs (
    id uuid primary key,
    university_id uuid not null references universities(id),
    object_key varchar(512) not null,
    status varchar(32) not null,
    total_rows integer,
    processed_rows integer not null default 0,
    failed_rows integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists import_job_errors (
    id uuid primary key,
    import_job_id uuid not null references import_jobs(id),
    row_number integer not null,
    code varchar(64) not null,
    message text not null,
    created_at timestamptz not null default now()
);

create table if not exists audit_logs (
    id uuid primary key,
    actor_id varchar(255) not null,
    action varchar(128) not null,
    entity_type varchar(128) not null,
    entity_id varchar(255) not null,
    payload jsonb,
    created_at timestamptz not null default now()
);

create table if not exists outbox_events (
    id uuid primary key,
    aggregate_type varchar(128) not null,
    aggregate_id varchar(255) not null,
    event_type varchar(128) not null,
    event_version varchar(16) not null,
    payload jsonb not null,
    published boolean not null default false,
    created_at timestamptz not null default now(),
    published_at timestamptz
);

create index if not exists idx_import_jobs_status on import_jobs(status);
create index if not exists idx_outbox_events_published on outbox_events(published, created_at);
