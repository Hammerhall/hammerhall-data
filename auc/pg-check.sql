-- Контрольные запросы набора «Дом Одина» для tools/pg-check.sh: выпуск загружен в Postgres, каждый запрос печатает
-- одну строку «что: значение». Деньги (M1–M7, контрольные примеры) пересчитываются в SQL по fee_tiers и дате торгов — своими
-- формулами, не генератора; «нарушений 0» — ожидаемое, заложенные отклонения считаются отдельно. В конце —
-- запросы торгов (сетка шагов, T10, доля проданных). Срез — переменная psql cut (по умолчанию «сейчас» выпуска):
--   psql -v cut="'2027-03-31 21:00+00'" … -f auc/pg-check.sql
\if :{?cut}
\else
\set cut '2027-09-01 00:00:00+00'
\endif
\set QUIET on
set client_min_messages = warning;

-- ---------------------------------------------------------------- функции пересчёта (временные)
-- сумма по ступеням: ставка ступени (минус скидка, п. п.) — на часть суммы от начала ступени (не включая) до следующей
create function pg_temp.tiered(p_schedule integer, p_amount numeric, p_discount numeric default 0) returns numeric
language sql stable as $$
  select coalesce(sum(greatest(t.rate_pct - p_discount, 0)
                      * greatest(0, least(p_amount, coalesce(t.next_from, p_amount)) - t.from_amount)) / 100, 0)
  from (select rate_pct, from_amount, lead(from_amount) over (order by tier_no) as next_from
        from fee_tiers where schedule_id = p_schedule) t
$$;
-- премия покупателя по шкале (M1): ступени или единая ставка, до копейки
create function pg_temp.premium(p_schedule integer, p_price numeric) returns numeric language sql stable as $$
  select round(case when exists (select 1 from fee_tiers where schedule_id = p_schedule)
                    then pg_temp.tiered(p_schedule, p_price)
                    else p_price * (select rate_pct from fee_schedules where id = p_schedule) / 100 end, 2)
$$;
-- НДС на дату торгов: 20 % по 31.12.2025, 22 % с 01.01.2026
create function pg_temp.vat_pct(d date) returns numeric language sql immutable as $$
  select case when d < date '2026-01-01' then 20 else 22 end::numeric
$$;
create function pg_temp.msk(t timestamptz) returns date language sql immutable as $$
  select (t at time zone 'Europe/Moscow')::date
$$;
-- комиссия продавца (M4): единая ставка договора или ступени SC-2024 (у торговца — на 5 п. п. ниже)
create function pg_temp.commission(p_price numeric, p_rate numeric, p_dealer boolean) returns numeric
language sql stable as $$
  select round(coalesce(p_price * p_rate / 100,
               pg_temp.tiered((select id from fee_schedules where code = 'SC-2024'), p_price,
                              case when p_dealer then 5 else 0 end)), 2)
$$;
-- n-й рабочий день после даты: выходные — сб, вс (праздники в SQL не учитываются — проверка «не раньше»)
create function pg_temp.workdays_after(d date, n integer) returns date language sql immutable as $$
  select max(x) from (select x from generate_series(d + 1, d + 3 * n + 14, interval '1 day') g(x0)
                      cross join lateral (select x0::date as x) y
                      where extract(isodow from x) < 6 order by x limit n) z
$$;

-- ---------------------------------------------------------------- контрольные примеры
select 'контрольные примеры в SQL (премия, сбор, НДС, итог строки): совпало ' || count(*) filter (where ok) || ' из ' || count(*)
from (select e.*, (p = premium and f = fee and v = vat and p + f + v + h = total) as ok
      from (values ('BP-2024', date '2025-11-20', 250000::numeric, false, 57500.00, 0.00, 11500.00, 319000.00),
                   ('BP-2024', date '2026-03-14', 250000, false, 57500.00, 0.00, 12650.00, 320150.00),
                   ('BP-2026', date '2026-11-19', 18000, false, 4500.00, 0.00, 990.00, 23490.00),
                   ('BP-2026', date '2026-11-19', 3000000, false, 700000.00, 0.00, 154000.00, 3854000.00),
                   ('BP-2026', date '2026-11-19', 25000000, false, 4700000.00, 0.00, 1034000.00, 30734000.00),
                   ('BP-COIN', date '2026-05-12', 7300, false, 1241.00, 0.00, 273.02, 8814.02),
                   ('BP-2026', date '2026-11-19', 120000, true, 30000.00, 4200.00, 7524.00, 161724.00))
             e(code, d, h, plat, premium, fee, vat, total)
      cross join lateral (select pg_temp.premium((select id from fee_schedules s where s.code = e.code), e.h) as p) pp
      cross join lateral (select case when e.plat then round(e.h * 3.5 / 100, 2) else 0 end as f) ff
      cross join lateral (select round((pp.p + ff.f) * pg_temp.vat_pct(e.d) / 100, 2) as v) vv) x;
select 'контрольный пример комитенту (1 000 000 ₽, ноябрь 2026): комиссия ' || c || ', торговцу ' || cd || ', к выплате '
       || (1000000 - c - 15000 - 400 - round((c + 15000 + 400) * 0.22, 2)) || ' (ждём 130500.00, 80500.00, 822002.00)'
from (select pg_temp.commission(1000000, null, false) c, pg_temp.commission(1000000, null, true) cd) x;

-- ---------------------------------------------------------------- счета (M1–M3)
select 'M1 шкала торгов по дате (монеты — BP-COIN, до 01.09.2026 — BP-2024, с 01.09.2026 — BP-2026): торгов '
       || count(*) || ', не по правилу ' || count(*) filter (where f.code <> case when d.code = 'coins' then 'BP-COIN'
                                         when pg_temp.msk(s.starts_at) < date '2026-09-01' then 'BP-2024' else 'BP-2026' end)
from sales s join departments d on d.id = s.department_id join fee_schedules f on f.id = s.buyer_premium_schedule_id;

with x as (
  select l.*, i.kind, i.issued_at, s.buyer_premium_schedule_id as sched, s.format,
         case when i.kind = 'shortfall' then 0 else pg_temp.premium(s.buyer_premium_schedule_id, l.price) end as want
  from invoice_lines l join invoices i on i.id = l.invoice_id join sales s on s.id = i.sale_id)
select 'M1 премия по шкале торгов: строк ' || count(*) || ', расходится ' || count(*) filter (where bad)
       || ' (из них заложено — счета 01–03.09.2026 со старой шкалой: ' || count(*) filter (where bad and old) || ')'
from (select (premium <> want or premium_schedule_id <> sched) as bad,
             kind = 'sale' and pg_temp.msk(issued_at) between date '2026-09-01' and date '2026-09-03' as old from x) y;

select 'M2 НДС строки = (премия + сбор) × ставка на дату торгов: строк ' || count(*) || ', расходится '
       || count(*) filter (where l.vat_rate <> pg_temp.vat_pct(pg_temp.msk(s.starts_at))
                          or l.vat <> round((l.premium + l.platform_fee) * pg_temp.vat_pct(pg_temp.msk(s.starts_at)) / 100, 2))
from invoice_lines l join invoices i on i.id = l.invoice_id join sales s on s.id = i.sale_id;

select 'сбор партнёрской площадки 3,5 % — только живые торги: строк со сбором ' || count(*) filter (where l.platform_fee > 0)
       || ', не 3,5 % или не живые ' || count(*) filter (where l.platform_fee > 0
                and (s.format <> 'live' or l.platform_fee <> round(l.price * 3.5 / 100, 2)))
from invoice_lines l join invoices i on i.id = l.invoice_id join sales s on s.id = i.sale_id;

select 'M3 итог строки = цена + премия + сбор + НДС: расходится ' || count(*) filter (where line_total <> price + premium + platform_fee + vat)
       || ' из ' || count(*) from invoice_lines;

select 'M3 итоги счёта = суммы строк: счетов ' || count(*) || ', расходится ' || count(*) filter (where
         i.hammer_total <> l.h or i.premium_total <> l.p or i.platform_fee_total <> l.f or i.vat_total <> l.v or i.total <> l.t)
from invoices i join (select invoice_id, sum(price) h, sum(premium) p, sum(platform_fee) f, sum(vat) v, sum(line_total) t
                      from invoice_lines group by invoice_id) l on l.invoice_id = i.id;

-- ---------------------------------------------------------------- деньги ↔ лоты
select 'цена строки ↔ лот: sale/reissue = молоток ' || count(*) filter (where i.kind in ('sale', 'reissue') and l.price <> o.hammer_price)
       || ', after_sale = цена после торгов ' || count(*) filter (where i.kind = 'after_sale' and l.price is distinct from o.after_sale_price)
       || ', second_chance ≥ резерва ' || count(*) filter (where i.kind = 'second_chance' and l.price < coalesce(o.reserve, 0))
       || ' — расхождений; счёт до закрытия лота ' || count(*) filter (where i.issued_at < coalesce(o.closed_at, o.after_sale_at))
from invoice_lines l join invoices i on i.id = l.invoice_id join lots o on o.id = l.lot_id;

select 'недостача (shortfall) = молоток неплательщика − молоток повторной продажи: счетов ' || count(*)
       || ', расходится ' || count(*) filter (where l.price <> o.hammer_price - coalesce(n.hammer_price, -1))
from invoice_lines l join invoices i on i.id = l.invoice_id and i.kind = 'shortfall'
join lots o on o.id = l.lot_id left join lots n on n.previous_lot_id = o.id and n.result = 'sold';

-- ---------------------------------------------------------------- оплата (M6)
select 'срок оплаты — 23:59:59 по Москве дня «выставлен + 10»: расходится ' || count(*) filter (where
         pg_temp.msk(i.due_at) <> pg_temp.msk(i.issued_at) + s.payment_due_days
         or (i.due_at at time zone 'Europe/Moscow')::time <> time '23:59:59') || ' из ' || count(*)
from invoices i join sales s on s.id = i.sale_id;

with p as (
  select invoice_id,
         sum(amount) filter (where status = 'succeeded' and kind in ('invoice', 'deposit_applied'))
           - coalesce(sum(amount) filter (where status = 'succeeded' and kind = 'refund'), 0) as paid,
         max(at) filter (where status = 'succeeded' and kind in ('invoice', 'deposit_applied')) as last_at
  from payments where invoice_id is not null group by invoice_id),
d as (select distinct invoice_id from payments where provider_ref in
        (select provider_ref from payments where provider_ref is not null group by provider_ref having count(*) > 1))
select 'M6 оплачено = Σ платежей + депозит − возвраты: счетов ' || count(*) || ', расходится '
       || count(*) filter (where i.paid_total <> coalesce(p.paid, 0))
       || ' (из них с дублем платежа провайдера — заложено: '
       || count(*) filter (where i.paid_total <> coalesce(p.paid, 0) and d.invoice_id is not null)
       || '); оплачен ⇔ оплачено всё — нарушений '
       || count(*) filter (where (i.status = 'paid') <> (i.paid_total = i.total + i.late_fee))
       || '; paid_at — последний платёж — нарушений '
       || count(*) filter (where i.status = 'paid' and i.paid_at <> p.last_at and d.invoice_id is null)
from invoices i left join p on p.invoice_id = i.id left join d on d.invoice_id = i.id;

select 'M6 пени = 0,1 % суммы × дней после срока: расходится ' || count(*) filter (where late_fee <> round(total * 0.001 *
         greatest(0, pg_temp.msk(coalesce(paid_at, cancelled_at, :'cut'::timestamptz)) - pg_temp.msk(due_at)), 2))
       || ' из ' || count(*) || '; с пенями ' || count(*) filter (where late_fee > 0)
from invoices;

select 'статусы счетов на срез: ' || string_agg(status || ' ' || n, ', ' order by status) from
  (select status, count(*) n from invoices group by status) x;
select 'статус на срез ↔ моменты: нарушений ' || count(*) filter (where status <> case
         when cancelled_at is not null then 'cancelled' when paid_at is not null then 'paid'
         when due_at < :'cut'::timestamptz then 'overdue' when paid_total > 0 then 'part_paid' else 'issued' end)
from invoices;
select 'отмена non_payment — на 31-й день после выставления: отмен ' || count(*) || ', не на 31-й '
       || count(*) filter (where pg_temp.msk(cancelled_at) - pg_temp.msk(issued_at) <> 31)
from invoices where cancel_reason = 'non_payment';
select 'платёж после выставления счёта: нарушений ' || count(*) filter (where p.at <= i.issued_at) || ' из ' || count(*)
from payments p join invoices i on i.id = p.invoice_id where p.kind in ('invoice', 'deposit_applied', 'refund');
select 'номер провайдера повторяется (дубль платежа — заложено duplicate_payment): номеров ' || count(*)
from (select provider_ref from payments where provider_ref is not null group by provider_ref having count(*) > 1) x;

select 'переизданные счета (заложенная старая шкала): reissue ' || count(*) filter (where kind = 'reissue')
       || ', заменённых (house_error) ' || count(*) filter (where cancel_reason = 'house_error')
       || ', связь reissue → заменённый нарушена ' || count(*) filter (where kind = 'reissue' and not exists
          (select 1 from invoices o where o.id = invoices.related_invoice_id and o.cancel_reason = 'house_error'))
from invoices;

-- коридоры — счета с исходом до среза (выставлены раньше среза − 32 суток)
select 'день полной оплаты, среди платящих (цели / 0,96 — 36,5 / 72,9 / 93,8 / 100 %): к 2-му ' || round(100.0 * avg((d <= 2)::int), 1)
       || ' %, к 5-му ' || round(100.0 * avg((d <= 5)::int), 1) || ' %, к 10-му ' || round(100.0 * avg((d <= 10)::int), 1)
       || ' %, к 30-му ' || round(100.0 * avg((d <= 30)::int), 1) || ' % (счетов ' || count(*) || ')'
from (select pg_temp.msk(paid_at) - pg_temp.msk(issued_at) d from invoices
      where status = 'paid' and kind <> 'shortfall' and issued_at < :'cut'::timestamptz - interval '32 days') x;
select 'окончательно не платят (отмена non_payment; цели — живые 1,5 %, онлайн 3 %, новые клиенты 8 %): живые '
       || round(100.0 * avg(coalesce(i.cancel_reason = 'non_payment', false)::int) filter (where s.format = 'live'), 2) || ' %, онлайн '
       || round(100.0 * avg(coalesce(i.cancel_reason = 'non_payment', false)::int) filter (where s.format = 'timed'), 2) || ' %, всего '
       || round(100.0 * avg(coalesce(i.cancel_reason = 'non_payment', false)::int), 2) || ' % (счетов ' || count(*) || ')'
from invoices i join sales s on s.id = i.sale_id
where i.kind in ('sale', 'after_sale', 'second_chance', 'reissue') and coalesce(i.cancel_reason, '') <> 'house_error'
  and i.issued_at < :'cut'::timestamptz - interval '32 days';
select 'второй шанс: отказов по счетам торгов ' || count(*) filter (where kind in ('sale', 'reissue') and cancel_reason = 'non_payment')
       || ', счетов second_chance ' || count(*) filter (where kind = 'second_chance')
       || ' (цель — четверть отказов предложена, половина согласна: ≈ 12 % лотов отказов)'
from invoices;

-- ---------------------------------------------------------------- депозиты
with d as (select registration_id, at from payments where kind = 'deposit'),
f as (select registration_id, count(*) n from payments where kind in ('deposit_applied', 'deposit_refund', 'deposit_forfeit')
      group by registration_id)
select 'депозит — ровно одна судьба (в зачёт, возврат, удержан): депозитов ' || count(*) || ', судеб больше одной '
       || count(*) filter (where f.n > 1) || ', без судьбы (внесён раньше среза − 100 суток) '
       || count(*) filter (where f.n is null and d.at < :'cut'::timestamptz - interval '100 days')
       || '; судьба без депозита ' || (select count(*) from f where registration_id not in (select registration_id from d))
from d left join f using (registration_id);
select 'депозиты: ' || string_agg(kind || ' ' || n, ', ' order by kind)
from (select kind, count(*) n from payments where kind like 'deposit%' group by kind) x;

-- ---------------------------------------------------------------- отчёты комитентам (M4, M5, M7)
with x as (
  select sl.*, s.format, pg_temp.vat_pct(pg_temp.msk(s.starts_at)) vp, c.commission_rate, c.dealer_discount, o.low_estimate,
         o.withdrawn_by, sl.outcome in ('sold', 'sold_after_sale', 'second_chance') as sold,
         case when s.format = 'live' then 400.00 else 150.00 end as cat
  from settlement_lines sl join settlements st on st.id = sl.settlement_id join sales s on s.id = st.sale_id
  join lots o on o.id = sl.lot_id join consignments c on c.id = o.consignment_id)
select 'M4–M5 строки отчёта: строк ' || count(*)
       || ', комиссия расходится ' || count(*) filter (where commission <> case when sold
            then pg_temp.commission(price, commission_rate, dealer_discount) else 0 end)
       || ', страхование ' || count(*) filter (where insurance_fee <> case when sold then round(price * 1.5 / 100, 2) else 0 end)
       || ', каталог ' || count(*) filter (where catalogue_fee <> case when sold or outcome = 'bought_in'
            or (outcome = 'withdrawn' and withdrawn_by is distinct from 'house') then cat else 0 end)
       || ', снятие ' || count(*) filter (where withdrawal_fee <> case when outcome = 'withdrawn'
            and withdrawn_by is distinct from 'house' then round(low_estimate * 12 / 100, 2) else 0 end)
       || ', НДС ' || count(*) filter (where vat <> round((commission + insurance_fee + catalogue_fee + withdrawal_fee) * vp / 100, 2))
       || ', к выплате ' || count(*) filter (where net <> coalesce(price, 0) - commission - insurance_fee - catalogue_fee
            - withdrawal_fee - vat)
from x;
select 'итоги отчёта = суммы строк: отчётов ' || count(*) || ', расходится ' || count(*) filter (where
         s.hammer_total <> l.h or s.commission_total <> l.c or s.fees_total <> l.f or s.vat_total <> l.v or s.net_total <> l.n)
from settlements s join (select settlement_id, coalesce(sum(price), 0) h, sum(commission) c,
                                sum(insurance_fee + catalogue_fee + withdrawal_fee) f, sum(vat) v, sum(net) n
                         from settlement_lines group by settlement_id) l on l.settlement_id = s.id;

-- оплата лота покупателем: по виду строки отчёта — счёт того же лота нужного вида
with paid as (
  select l.lot_id, case when i.kind = 'reissue' then 'sale' else i.kind end as kind, i.paid_at
  from invoice_lines l join invoices i on i.id = l.invoice_id where i.paid_at is not null),
sl as (
  select sl.*, st.sale_id, case sl.outcome when 'sold' then 'sale' when 'sold_after_sale' then 'after_sale'
                              when 'second_chance' then 'second_chance' end as kind
  from settlement_lines sl join settlements st on st.id = sl.settlement_id)
select 'M7 выплата — не раньше полной оплаты покупателем: выплаченных строк ' || count(*) filter (where sl.paid_at is not null and sl.net > 0)
       || ', раньше оплаты ' || count(*) filter (where sl.paid_at is not null and sl.net > 0 and sl.kind is not null
            and (p.paid_at is null or sl.paid_at < p.paid_at)) || ' (заложено payout_before_payment — десятки в full)'
       || '; положена раньше max(торги + 35 сут., оплата + 5 раб. дней) ' || count(*) filter (where sl.payable_at is not null
            and pg_temp.msk(sl.payable_at) < greatest(pg_temp.msk(s.ends_at) + 35, pg_temp.workdays_after(pg_temp.msk(p.paid_at), 5)))
       || '; положена без оплаты ' || count(*) filter (where sl.payable_at is not null and p.paid_at is null)
from sl join sales s on s.id = sl.sale_id left join paid p on p.lot_id = sl.lot_id and p.kind = sl.kind;
select 'выплаты комитентам = Σ выплаченных строк (по отчёту): отчётов с выплатами ' || count(*) filter (where p.s is not null)
       || ', расходится ' || count(*) filter (where coalesce(p.s, 0) <> coalesce(l.s, 0) or coalesce(p.s, 0) <> st.paid_total)
from settlements st
left join (select settlement_id, sum(amount) s from payments where kind = 'payout' group by settlement_id) p on p.settlement_id = st.id
left join (select settlement_id, sum(net) s from settlement_lines where paid_at is not null group by settlement_id) l
       on l.settlement_id = st.id;
select 'отчёты: ' || string_agg(status || ' ' || n, ', ' order by status) || '; строк: '
       || (select string_agg(outcome || ' ' || n, ', ' order by outcome) from
            (select outcome, count(*) n from settlement_lines group by outcome) y)
from (select status, count(*) n from settlements group by status) x;
select 'отчёт — на следующий рабочий день после торгов: отчётов ' || count(*) || ', не так '
       || count(*) filter (where pg_temp.msk(st.created_at) <> pg_temp.workdays_after(pg_temp.msk(s.ends_at), 1)
                           and extract(isodow from pg_temp.msk(st.created_at)) < 6
                           and pg_temp.msk(st.created_at) < pg_temp.workdays_after(pg_temp.msk(s.ends_at), 1))
       || ' (праздники SQL не знает — считаются только отчёты раньше следующего будня)'
from settlements st join sales s on s.id = st.sale_id;

-- ---------------------------------------------------------------- торги
select 'доля проданных по отделам и формату (живые / онлайн, в среднем ≈ 76 %): '
       || string_agg(code || ' ' || format || ' ' || share || ' %', ', ' order by code, format)
from (select d.code, s.format, round(100.0 * avg((l.result in ('sold', 'sold_after_sale'))::int), 1) share
      from lots l join sales s on s.id = l.sale_id join departments d on d.id = s.department_id
      where l.result in ('sold', 'sold_after_sale', 'bought_in') group by d.code, s.format) x;
-- ---------------------------------------------------------------- торги: сетка шагов, T2, T10, T14, T16
-- ---------------------------------------------------------------- торги: сетка шагов и T10
-- Своя арифметика по increment_bands (не генератора). Ожидаемое — «нарушений 0»; заложенное (double_bid,
-- missed_extension, early_close — десятки в full, единицы в small) печатается отдельно. Время ставок — мс:
-- placed_at (секунды) × 1000 + placed_ms.
create function pg_temp.auc_step(a numeric) returns numeric language sql stable as $$
  select step from increment_bands where from_amount <= a order by from_amount desc limit 1
$$;
create function pg_temp.auc_ms(t timestamptz, ms smallint) returns bigint language sql immutable as $$
  select (extract(epoch from t))::bigint * 1000 + coalesce(ms, 0)
$$;

-- T1: сетка — эстимейт, стартовая, резерв, максимумы поручений, принятые ставки онлайн-лотов
select 'T1 сетка шагов: лотов ' || (select count(*) from lots)
       || ', не на сетке — эстимейт ' || (select count(*) from lots where low_estimate % pg_temp.auc_step(low_estimate) <> 0
            or high_estimate % pg_temp.auc_step(high_estimate) <> 0)
       || ', стартовая ' || (select count(*) from lots where starting_price % pg_temp.auc_step(starting_price) <> 0)
       || ', резерв ' || (select count(*) from lots where reserve is not null and reserve % pg_temp.auc_step(reserve) <> 0)
       || ', максимумы поручений ' || (select count(*) from bid_orders where max_amount is not null
            and max_amount % pg_temp.auc_step(max_amount) <> 0)
       || ', принятые онлайн ' || (select count(*) from bids b join lots l on l.id = b.lot_id join sales s on s.id = l.sale_id
            where b.accepted and s.format = 'timed' and b.amount % pg_temp.auc_step(b.amount) <> 0);

-- T1а, T2: принятые ставки растут на шаг; живые — полшага (от 100 000 ₽), после полшага сетка идёт от суммы
with acc as (
  select b.lot_id, b.seq, b.amount, s.format,
         lag(b.amount) over (partition by b.lot_id order by b.seq) as prev
  from bids b join lots l on l.id = b.lot_id join sales s on s.id = l.sale_id where b.accepted),
x as (
  select *, prev is not null and format = 'live' and prev >= 100000 and amount = prev + pg_temp.auc_step(prev) / 2 as half
  from acc),
h as (select lot_id, min(seq) as first_half from x where half group by lot_id)
select 'T2 рост ставок: принятых ' || count(*)
       || ', меньше шага ' || count(*) filter (where prev is not null and not half and amount < prev + pg_temp.auc_step(prev))
       || ', первая ниже стартовой ' || count(*) filter (where prev is null and amount < (select starting_price from lots where id = x.lot_id))
       || ', живые не на сетке до полшага ' || count(*) filter (where format = 'live' and seq < coalesce(h.first_half, 2147483647)
            and amount % pg_temp.auc_step(amount) <> 0)
       || ', полшага ' || count(*) filter (where half)
from x left join h using (lot_id);

-- T10: конец онлайн-лота по журналу ставок. C — текущий конец (сначала план P); принятая ставка t с C − окно ≤ t < C
-- сдвигает конец: C := min(max(C, t + продление), P + потолок). Сверка: флаг extended_close, события extended и
-- extension_capped (новый конец), closed_at + closed_ms, lots.extensions; принятых после конца — нет.
with recursive acc as (
  select b.lot_id, row_number() over (partition by b.lot_id order by b.seq) as n,
         pg_temp.auc_ms(b.placed_at, b.placed_ms) as t, b.extended_close as ext
  from bids b join lots l on l.id = b.lot_id join sales s on s.id = l.sale_id
  where b.accepted and s.format = 'timed'),
lot0 as (
  select l.id as lot_id, (extract(epoch from l.closes_at_planned))::bigint * 1000 as p,
         s.extension_window_s * 1000 as w, s.extension_s * 1000 as e, s.extension_cap_s * 1000 as cap,
         case when l.closed_at is null then null else pg_temp.auc_ms(l.closed_at, l.closed_ms) end as closed,
         l.extensions
  from lots l join sales s on s.id = l.sale_id where s.format = 'timed' and l.result <> 'withdrawn'),
r as (
  select lot_id, 0::bigint as n, p as c, 0 as bad_ext, 0 as missed, 0 as after_close, 0 as ext_n from lot0
  union all
  select r.lot_id, r.n + 1,
         case when a.ext then z.nc else r.c end,
         r.bad_ext + (a.ext and z.nc = r.c)::int,
         r.missed + (not a.ext and z.nc <> r.c)::int,
         r.after_close + (a.t >= r.c)::int,
         r.ext_n + a.ext::int
  from r join acc a on a.lot_id = r.lot_id and a.n = r.n + 1 join lot0 o on o.lot_id = r.lot_id
  cross join lateral (select case when a.t >= r.c - o.w and a.t < r.c
                                  then least(greatest(r.c, a.t + o.e), o.p + o.cap) else r.c end as nc) z),
fin as (select distinct on (lot_id) * from r order by lot_id, n desc),
ev as (select lot_id, count(*) as n, count(*) filter (where kind = 'extension_capped') as capped,
              max(pg_temp.auc_ms(new_close_at, new_close_ms)) as last_close
       from lot_events where kind in ('extended', 'extension_capped') group by lot_id)
select 'T10 конец онлайн-лота: лотов ' || count(*)
       || ', продлённых ' || count(*) filter (where f.ext_n > 0)
       || ', конец ≠ журналу ' || count(*) filter (where o.closed is not null and o.closed <> f.c
            and not (o.closed < f.c and f.c - o.closed <= 3000 and f.missed = 0))
       || ', ранний конец на 1–3 с (заложено early_close) ' || count(*) filter (where o.closed is not null and o.closed < f.c
            and f.c - o.closed <= 3000 and f.missed = 0)
       || ', продление вне правила ' || sum(f.bad_ext)
       || ', пропущено продлений (заложено missed_extension) ' || count(*) filter (where f.missed > 0)
       || ', принято после конца ' || sum(f.after_close)
       || ', конец позже плана + потолок ' || count(*) filter (where f.c > o.p + o.cap)
       || ', lots.extensions ≠ ставкам ' || count(*) filter (where o.extensions <> f.ext_n)
       || ', событий продления ≠ ставкам ' || count(*) filter (where coalesce(ev.n, 0) <> f.ext_n)
       || ', последний новый конец ≠ журналу ' || count(*) filter (where ev.n > 0 and ev.last_close <> f.c)
       || ', упёрлось в потолок ' || coalesce(sum(ev.capped), 0)
from fin f join lot0 o using (lot_id) left join ev using (lot_id);

-- T14, T16: повтор запроса и итог лота по ставкам
select 'T14 повтор запроса: ключей ' || count(*) || ', с повтором ' || count(*) filter (where n > 1)
       || ', принят дважды (заложено double_bid) ' || count(*) filter (where acc > 1)
       || ', повтор без duplicate ' || sum(n - 1 - dup - (acc - 1)) filter (where n > 1)
from (select request_key, count(*) n, count(*) filter (where accepted) acc,
             count(*) filter (where reject_reason = 'duplicate') dup
      from bids where request_key is not null group by request_key) k;
with last_acc as (
  select distinct on (lot_id) lot_id, id, registration_id, amount, kind from bids where accepted order by lot_id, seq desc)
select 'T16 итог лота: закрытых ' || count(*)
       || ', продан ≠ правилу ' || count(*) filter (where (l.result = 'sold') <> (a.kind is not null and a.kind <> 'vendor'
            and (l.reserve is null or a.amount >= l.reserve)))
       || ', молоток или победитель ≠ последней принятой ' || count(*) filter (where l.result = 'sold'
            and (l.hammer_price <> a.amount or l.winning_bid_id <> a.id or l.buyer_registration_id <> a.registration_id))
       || ', bid_count ≠ принятым ' || count(*) filter (where l.bid_count <> (select count(*) from bids b where b.lot_id = l.id and b.accepted))
from lots l left join last_acc a on a.lot_id = l.id where l.closed_at is not null;
