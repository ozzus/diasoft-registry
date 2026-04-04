insert into universities (id, code, name, created_at)
values
    ('11111111-1111-1111-1111-111111111111', 'ITMO', 'ITMO University', now()),
    ('22222222-2222-2222-2222-222222222222', 'BMSTU', 'Bauman Moscow State Technical University', now()),
    ('33333333-3333-3333-3333-333333333333', 'MSU', 'Lomonosov Moscow State University', now())
on conflict (code) do nothing;
