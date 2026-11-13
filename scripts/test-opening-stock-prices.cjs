const fs=require('node:fs'),assert=require('node:assert/strict');
const {PGlite}=require('@electric-sql/pglite');
(async()=>{
 const db=new PGlite(),a='00000000-0000-4000-8000-000000000001',b='00000000-0000-4000-8000-000000000002';
 await db.exec(`create role anon;create role authenticated;
 create table providers(id uuid primary key);insert into providers values('${a}'),('${b}');
 create table provider_kitchen_reviews(provider_id uuid,month date,opening_stock_paise bigint,revision int default 1,primary key(provider_id,month));
 create function kitchen_book_provider() returns uuid language plpgsql as $$begin
 if current_setting('test.owner',true) not in('${a}','${b}') or current_setting('test.owner',true) is null then raise exception 'Owner required';end if;return current_setting('test.owner')::uuid;end$$;
 create function provider_kitchen_statement(m date) returns jsonb language sql as $$select jsonb_build_object('review',to_jsonb(r)) from provider_kitchen_reviews r where provider_id=kitchen_book_provider() and month=m$$;
 create function provider_kitchen_review_save(payload jsonb) returns jsonb language plpgsql security definer as $$declare rev int;begin
 select revision into rev from provider_kitchen_reviews where provider_id=kitchen_book_provider() and month=(payload->>'month')::date;
 if coalesce(rev,0)<>(payload->>'revision')::int then raise exception 'Revision mismatch';end if;
 insert into provider_kitchen_reviews(provider_id,month,opening_stock_paise) values(kitchen_book_provider(),(payload->>'month')::date,(payload->>'opening_stock_paise')::bigint)
 on conflict(provider_id,month) do update set opening_stock_paise=excluded.opening_stock_paise,revision=provider_kitchen_reviews.revision+1;
 return provider_kitchen_statement((payload->>'month')::date);end$$;`);
 await db.exec(fs.readFileSync('supabase/migrations/202611100001_opening_stock_prices.sql','utf8'));
 await db.exec(`set role authenticated; select set_config('test.owner','${a}',false);`);
 const rows=[{item:'rice',quantity:'5',unit:'kg',amount:'250'}];
 const base={month:'2026-11-01',opening_stock_paise:25000,revision:0,opening_rows:rows};
 const call=async p=>(await db.query('select provider_kitchen_review_save($1::jsonb) result',[JSON.stringify(p)])).rows[0].result;
 assert.deepEqual((await call(base)).review.opening_rows,rows);
 await assert.rejects(()=>call(base)); // legacy optimistic locking retained
 await assert.rejects(()=>call({...base,revision:1,opening_stock_paise:100}));
 await assert.rejects(()=>call({...base,revision:1,opening_rows:[...rows,...rows]}));
 await assert.rejects(()=>call({...base,revision:1,opening_rows:[{...rows[0],quantity:'0'}]}));
 await assert.rejects(()=>call({...base,revision:1,opening_rows:[{...rows[0],unit:'bag'}]}));
 const legacy={...base,revision:1};delete legacy.opening_rows;
 assert.deepEqual((await call(legacy)).review.opening_rows,rows); // unchanged total retains detail
 assert.deepEqual((await call({...legacy,revision:2,opening_stock_paise:30000})).review.opening_rows,[]);
 await db.exec(`select set_config('test.owner','${b}',false)`);
 assert.deepEqual((await call(base)).review.opening_rows,rows); // tenant has independent revision
 await assert.rejects(()=>db.query('select provider_kitchen_review_save_before_prices($1::jsonb)',[JSON.stringify(base)]));
 await db.exec('reset role;set role anon');await assert.rejects(()=>call(base));
 await db.exec("reset role;set role authenticated;select set_config('test.owner','customer',false)");await assert.rejects(()=>call(base));
 console.log('Opening-stock price migration: 12 assertions passed with fixture membership and legacy-save functions.');await db.close();
})().catch(e=>{console.error(e);process.exitCode=1});
