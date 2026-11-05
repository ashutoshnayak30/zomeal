const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { PGlite } = require('@electric-sql/pglite');

(async () => {
  const db = new PGlite();
  const names = ['provider_daily_dashboard_commission_v1', 'provider_auto_assign_routes'];
  await db.exec("create type public.meal_slot as enum ('LUNCH','DINNER'); create role authenticated;");
  // Isolated cutoff fixtures: no live customer or payment data.
  for (const name of names) {
    await db.exec(`create function public.${name}(target_slot public.meal_slot,target_date date default null)
      returns timestamptz language plpgsql security definer set search_path=public as $$
      begin return ((target_date + case target_slot when 'LUNCH' then time '07:00' else time '16:00' end)
      at time zone 'Asia/Kolkata'); end; $$;
      revoke all on function public.${name}(public.meal_slot,date) from public;
      grant execute on function public.${name}(public.meal_slot,date) to authenticated;`);
  }
  const migration = fs.readFileSync(path.join(__dirname, '../supabase/migrations/202609120001_provider_operations_cutoff.sql'), 'utf8');
  await db.exec(migration);
  for (const name of names) {
    const { rows } = await db.query(`select
      public.${name}('LUNCH','2026-09-12') = timestamptz '2026-09-12 02:30:00+00' lunch_ok,
      public.${name}('DINNER','2026-09-12') = timestamptz '2026-09-12 10:30:00+00' dinner_ok,
      has_function_privilege('authenticated','public.${name}(public.meal_slot,date)','EXECUTE') acl_ok`);
    assert.deepEqual(rows[0], { lunch_ok: true, dinner_ok: true, acl_ok: true });
    const definition = (await db.query(`select pg_get_functiondef('public.${name}(public.meal_slot,date)'::regprocedure) definition`)).rows[0].definition;
    assert.match(definition, /SECURITY DEFINER/);
    assert.match(definition, /search_path/);
  }
  await assert.rejects(db.exec(migration), /Expected provider cutoff not found/);
  await db.close();
  console.log('PASS: lunch 08:00 IST, dinner unchanged at 16:00 IST, ACL/security preserved, unexpected definitions fail closed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
