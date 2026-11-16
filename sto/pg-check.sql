-- Контрольные запросы набора «Кременъ» для tools/pg-check.sh: выпуск загружен в Postgres, каждый запрос печатает
-- одну строку «что: значение».
select 'цена на момент — ровно одна: нарушений ' || count(*) from (select product_id, valid_from, lead(valid_from) over w nf, valid_to from prices window w as (partition by product_id order by valid_from)) x where nf is not null and nf <> valid_to;
select 'НДС-ступенька: средняя цена 31.12.2025 → 01.01.2026: ' || round(avg(b.price/a.price),4) from prices a join prices b on a.product_id=b.product_id and b.valid_from=a.valid_to where b.valid_from = '2026-01-01 00:00+03';
select 'смартфонов в продаже на «сейчас»: ' || count(distinct p.id) from products p join categories c on c.id=p.category_id join prices pr on pr.product_id=p.id where c.name_en ilike 'smartphones%' and pr.valid_from <= '2027-09-01' and (pr.valid_to is null or pr.valid_to > '2027-09-01');
