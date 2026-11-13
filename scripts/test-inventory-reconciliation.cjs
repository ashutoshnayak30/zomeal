const fs=require('node:fs');
const assert=require('node:assert/strict');
const {PGlite}=require('@electric-sql/pglite');
(async()=>{
 const db=new PGlite();
 const a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
 await db.exec(`create role anon; create role authenticated;
 create table provider_inventory_observations(provider_id uuid,report_date date,meal_slot text,rows jsonb,created_at timestamptz default now());
 create table provider_kitchen_entries(id int,provider_id uuid,entry_date date,kind text,item text,quantity numeric,unit text,voided_at timestamptz);
 -- Fixture for existing membership helper: only authenticated owner IDs resolve.
 create function kitchen_book_provider() returns uuid language plpgsql as $$begin
 if current_setting('test.owner',true) not in ('${a}','${b}') or current_setting('test.owner',true) is null then raise exception 'Owner required'; end if;
 return current_setting('test.owner')::uuid; end$$;
 revoke all on function kitchen_book_provider() from public;
 `);
 await db.exec(fs.readFileSync('supabase/migrations/202611060001_inventory_reconciliation.sql','utf8'));
 await db.exec(`insert into provider_inventory_observations values
 ('${a}','2026-01-01','DINNER','[{"item":"Rice","quantity":10,"unit":"kg"}]','2026-01-01'),
 ('${b}','2026-01-01','DINNER','[{"item":"Rice","quantity":999,"unit":"kg"}]','2026-01-01'),
 ('${a}','2026-01-03','DINNER','[{"item":"Rice","quantity":888,"unit":"kg"}]','2026-01-03');
 insert into provider_kitchen_entries values
 (1,'${a}','2026-01-02','EXPENSE','rice',500,'g',null),
 (2,'${a}','2026-01-03','EXPENSE','rice',2,'kg',null),
 (3,'${a}','2026-01-01','EXPENSE','rice',1,'kg',null),
 (4,'${a}','2026-01-02','EXPENSE','rice',null,'kg',null),
 (5,'${a}','2026-01-02','EXPENSE','rice',1,'litre',null),
 (6,'${b}','2026-01-02','EXPENSE','rice',500,'kg',null),
 (7,'${a}','2026-01-02','SALE','rice',500,'kg',null),
 (8,'${a}','2026-01-02','EXPENSE','rice',500,'kg',now());
 set role authenticated; select set_config('test.owner','${a}',false);`);
 const payload=(rows=[{item:'Rice',quantity:8,unit:'kg'}],date='2026-01-03',slot='DINNER')=>({date,slot,rows});
 const call=async p=>(await db.query('select provider_inventory_reconcile($1::jsonb) result',[JSON.stringify(p)])).rows[0].result;
 const r=(await call(payload())).items[0];
 assert.equal(r.opening,10); // same-slot correction excluded, other tenant excluded
 assert.equal(r.known_purchases,0.5); // g/kg conversion; voids/sales excluded
 assert.equal(r.purchase_review_count,4); // both boundaries, missing quantity, incompatible unit
 assert.equal(r.purchases.length,5);
 assert.equal(r.days,2);
 assert.equal((await call(payload([{item:'Rice',quantity:8000,unit:'g'}]))).items[0].opening,10000);
 assert.equal((await call(payload([{item:'Rice',quantity:1,unit:'litre'}]))).items[0].opening,null);
 assert.equal((await call(payload([{item:'Onion',quantity:0,unit:'kg'}]))).items[0].opening,null);
 await assert.rejects(()=>call(payload([{item:'Rice',quantity:-1,unit:'kg'}])));
 await assert.rejects(()=>call(payload([{item:'Rice',quantity:1,unit:'kg'},{item:' rice ',quantity:1,unit:'kg'}])));
 await assert.rejects(()=>call(payload(undefined,'2099-01-01')));
 await assert.rejects(()=>call(payload(undefined,'2026-01-03','BREAKFAST')));
 await db.exec(`select set_config('test.owner','${b}',false)`);
 assert.equal((await call(payload())).items[0].opening,999);
 await db.exec("select set_config('test.owner','customer',false)");
 await assert.rejects(()=>call(payload()));
 await db.exec('reset role; set role anon');
 await assert.rejects(()=>call(payload()));
 await db.exec(`reset role; select set_config('test.owner','${a}',false);
 insert into provider_inventory_observations values ('${a}','2026-01-03','LUNCH','[{"item":"Rice","quantity":7,"unit":"kg"}]',now());`);
 const sameDay=(await call(payload())).items[0];
 assert.equal(sameDay.opening,7);assert.equal(sameDay.days,0);assert.equal(sameDay.known_purchases,0);assert.equal(sameDay.purchase_review_count,1);
 const counts=(await db.query('select (select count(*) from provider_inventory_observations) observations,(select count(*) from provider_kitchen_entries) entries')).rows[0];
 assert.equal(counts.observations,4);assert.equal(counts.entries,8);
 console.log('Inventory reconciliation: 21 assertions passed (including fixture auth, tenant isolation, dates, units, boundaries and read-only checks).');
 await db.close();
})().catch(e=>{console.error(e);process.exitCode=1});
