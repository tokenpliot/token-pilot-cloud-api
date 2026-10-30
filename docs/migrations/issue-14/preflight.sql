-- Read-only. Resolve each result before importing the affected scope.
select organization_id, project_id, environment, count(*) as duplicate_count
from monthly_budget_settings
where project_id is not null
group by organization_id, project_id, environment
having count(*) > 1;

select b.id, b.organization_id, b.project_id, b.environment
from monthly_budget_settings b
left join projects p on p.id = b.project_id
where b.project_id is not null
  and (p.id is null or p.organization_id <> b.organization_id or b.limit_usd < 0 or b.currency <> 'USD');

select b.id, b.project_id, b.environment
from monthly_budget_settings b
where b.project_id is not null and b.environment is not null
  and not exists (select 1 from project_environments e
                  where e.project_id = b.project_id and e.organization_id = b.organization_id
                    and binary e.environment = binary b.environment);

select p.organization_id, count(*) as projects_without_admin
from projects p
where not exists (select 1 from organization_members m
                  join users u on u.id = m.user_id
                  where m.organization_id = p.organization_id and upper(m.role) in ('OWNER', 'ADMIN'))
group by p.organization_id;
