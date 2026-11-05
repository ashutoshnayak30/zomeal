const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { PGlite } = require('@electric-sql/pglite');

(async () => {
 const db = new PGlite();
 try {
  await db.exec(`create role anon; create role authenticated; create schema auth;
   create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
   create function auth.jwt() returns jsonb language sql stable as $$select coalesce(nullif(current_setting('request.jwt.claims',true),''),'{}')::jsonb$$;
   grant usage on schema public,auth to anon,authenticated;
   create table public.providers(id uuid primary key,status text);
   create table public.provider_members(provider_id uuid,user_id uuid,is_active boolean default true,member_role text default 'OWNER',created_at timestamptz default now());`);
  const root = path.join(__dirname, '..');
  const book = fs.readFileSync(path.join(root, 'supabase/migrations/202610050002_provider_kitchen_costs.sql'), 'utf8');
  const helper = book.match(/create function public\.kitchen_book_provider\(\)[\s\S]*?\$\$;/)[0];
  await db.exec(helper);
  await db.exec(fs.readFileSync(path.join(root, 'supabase/migrations/202610310001_provider_voice_pilot.sql'), 'utf8'));
  const owner = randomUUID(), manager = randomUUID(), customer = randomUUID(), kitchen = randomUUID();
  await db.query("insert into providers values($1,'ACTIVE')", [kitchen]);
  await db.query("insert into provider_members(provider_id,user_id,member_role) values($1,$2,'OWNER'),($1,$3,'MANAGER')", [kitchen,owner,manager]);
  const as = async (id, anonymous = false) => {
   await db.query("select set_config('request.jwt.claim.sub',$1,false)", [id || '']);
   await db.query("select set_config('request.jwt.claims',$1,false)", [JSON.stringify({is_anonymous:anonymous})]);
  };
  const reserve = async (seconds=30) => (await db.query('select public.provider_voice_reserve($1,$2) result',[randomUUID(),seconds])).rows[0].result;
  await as(null); assert.equal((await reserve()).allowed,false);
  await as(customer); assert.equal((await reserve()).allowed,false);
  await as(owner); assert.equal((await reserve()).allowed,false); // no implicit pilot
  await db.query("insert into provider_voice_pilot values($1,true,now()+interval '7 days',2,60)",[kitchen]);
  await as(owner,true); assert.equal((await reserve()).allowed,false);
  await as(owner);
  await assert.rejects(()=>reserve(61),/Invalid voice/);
  await assert.rejects(()=>reserve(0),/Invalid voice/);
  await db.exec('set role authenticated');
  assert.equal((await reserve()).remaining_requests,1);
  await assert.rejects(()=>db.query('update provider_voice_usage set requests=0'),/permission denied/);
  await assert.rejects(()=>db.query('update provider_voice_pilot set enabled=true'),/permission denied/);
  await db.exec('reset role');
  await as(manager); assert.equal((await reserve()).remaining_requests,0);
  assert.equal((await reserve()).reason,'LIMIT');
  await db.query('delete from provider_voice_usage');
  await db.query('update provider_voice_pilot set daily_requests=10,daily_seconds=45');
  assert.equal((await reserve(30)).allowed,true); assert.equal((await reserve(30)).reason,'LIMIT');
  await db.query('update provider_voice_usage set usage_day=usage_day-1');
  assert.equal((await reserve(15)).allowed,true); // yesterday does not consume today's quota
  await db.query("update providers set status='SUSPENDED'"); assert.equal((await reserve()).reason,'ACCESS');
  await db.query("update providers set status='ACTIVE'");
  await db.query("update provider_members set is_active=false where user_id=$1",[manager]); assert.equal((await reserve()).allowed,false);
  await as(owner); await db.query("update provider_voice_pilot set expires_at=now()-interval '1 second'"); assert.equal((await reserve()).allowed,false);
  await db.query("update provider_voice_pilot set expires_at=now()+interval '1 day',enabled=false"); assert.equal((await reserve()).allowed,false);
  await db.exec('set role anon'); await assert.rejects(()=>reserve(),/permission denied/); await db.exec('reset role');
  console.log('PASS: actual migration on isolated PGlite; owner/manager, customer/anonymous, table permissions, opt-in, expiry, shared quota, duration, previous-day usage and suspension.');
  console.log('Concurrent transactions and live Supabase/Auth integration still require staging verification.');
 } finally { await db.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
