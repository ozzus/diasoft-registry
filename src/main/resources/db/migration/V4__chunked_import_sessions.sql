create table if not exists upload_sessions (
    id uuid primary key,
    university_id uuid not null references universities(id),
    status varchar(32) not null,
    expected_file_count integer not null,
    max_file_size_bytes bigint not null,
    max_rows_per_file integer not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    completed_at timestamptz
);

create table if not exists upload_session_files (
    id uuid primary key,
    session_id uuid not null references upload_sessions(id) on delete cascade,
    file_name varchar(255) not null,
    content_type varchar(255),
    file_size_bytes bigint not null,
    file_sha256 varchar(64),
    object_key varchar(512) not null unique,
    status varchar(32) not null,
    created_at timestamptz not null default now(),
    uploaded_at timestamptz
);

alter table import_jobs
    add column if not exists upload_session_id uuid references upload_sessions(id),
    add column if not exists source_format varchar(32),
    add column if not exists file_count integer not null default 1,
    add column if not exists total_chunks integer not null default 0,
    add column if not exists completed_chunks integer not null default 0,
    add column if not exists normalized_prefix varchar(512),
    add column if not exists file_size_bytes bigint,
    add column if not exists file_sha256 varchar(64);

alter table import_job_errors
    add column if not exists source_file_name varchar(255);

create table if not exists import_chunks (
    id uuid primary key,
    import_job_id uuid not null references import_jobs(id) on delete cascade,
    source_file_name varchar(255) not null,
    chunk_index integer not null,
    object_key varchar(512) not null unique,
    row_from integer not null,
    row_to integer not null,
    row_count integer not null,
    status varchar(32) not null,
    processed_rows integer not null default 0,
    failed_rows integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (import_job_id, chunk_index)
);

create index if not exists idx_upload_sessions_university_status
    on upload_sessions(university_id, status, created_at desc);

create index if not exists idx_upload_session_files_session_status
    on upload_session_files(session_id, status, created_at asc);

create index if not exists idx_import_chunks_status_created
    on import_chunks(status, created_at asc);

create index if not exists idx_import_chunks_job_status
    on import_chunks(import_job_id, status, chunk_index asc);

create index if not exists idx_import_jobs_status_created
    on import_jobs(status, created_at asc);
