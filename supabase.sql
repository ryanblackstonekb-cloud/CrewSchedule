create table if not exists public.crew_schedule (
  id text primary key,
  payload jsonb not null,
  updated_at timestamptz not null default now()
);

alter table public.crew_schedule enable row level security;

create policy "crew schedule read"
on public.crew_schedule for select to anon using (true);

create policy "crew schedule insert"
on public.crew_schedule for insert to anon with check (true);

create policy "crew schedule update"
on public.crew_schedule for update to anon using (true) with check (true);
