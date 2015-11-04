--name: user-projects
select * from workday_project p, workday_client c where c.workday_user_id = (:user_id)::uuid and p.workday_client_id = c.id

--name: user-project
select * from workday_project p, workday_client c where c.workday_user_id = (:user_id)::uuid and p.workday_client_id = c.id and p.id = (:project_id)::uuid

--name: by-name
select * from workday_project p, workday_client c where c.workday_user_id = (:user_id)::uuid and p.workday_client_id = c.id and p.name = :name

--name: user-client-projects
select * from workday_project p, workday_client c where c.workday_user_id = (:user_id)::uuid and p.workday_client_id = c.id and c.id = (:client_id)::uuid

--name: add<!
insert into workday_project (name, workday_client_id, color) values (:name, (:client_id)::uuid, :color)

--name: update!
update workday_project set name = :name, color = :color where id = (:project_id)::uuid
