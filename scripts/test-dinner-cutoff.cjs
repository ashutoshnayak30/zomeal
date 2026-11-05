const fs=require('node:fs'); const assert=require('node:assert/strict'); const {PGlite}=require('@electric-sql/pglite');
(async()=>{const db=new PGlite();
 await db.exec("create type meal_slot as enum ('LUNCH','DINNER'); create role authenticated;");
 const defs=[['customer_update_delivery_address','jsonb'],['provider_daily_dashboard_commission_v1','meal_slot,date'],['customer_select_daily_meal','uuid,uuid'],['customer_switch_provider','uuid,uuid,uuid,jsonb'],['customer_pause_subscription_meals','uuid,date[],text'],['credit_captured_payment_to_wallet','uuid,uuid,text']];
 for(const [name,args] of defs) await db.exec(`create function ${name}(${args}) returns text language sql security definer set search_path=public as $$select '08:00 / 16:00 / 4:00 PM'::text$$; revoke all on function ${name}(${args}) from public; grant execute on function ${name}(${args}) to authenticated;`);
 const sql=fs.readFileSync('supabase/migrations/202609150001_dinner_cutoff_1800.sql','utf8'); await db.exec(sql);
 for(const [name,args] of defs){const {rows}=await db.query(`select pg_get_functiondef('${name}(${args})'::regprocedure) def,has_function_privilege('authenticated','${name}(${args})','EXECUTE') allowed`);assert.match(rows[0].def,/08:00 \/ 18:00 \/ 6:00 PM/);assert.equal(rows[0].allowed,true);}
 const {rows}=await db.query("select timestamp '2026-09-15 17:59:59' < timestamp '2026-09-15 18:00:00' before_open, timestamp '2026-09-15 18:00:00' < timestamp '2026-09-15 18:00:00' at_open"); assert.deepEqual(rows[0],{before_open:true,at_open:false});
 await assert.rejects(db.exec(sql),/Expected old dinner cutoff/); await db.close(); console.log('PASS: six cutoff replacements, unchanged lunch and grants, 6pm boundary, unexpected definitions fail closed');
})().catch(e=>{console.error(e);process.exitCode=1});
