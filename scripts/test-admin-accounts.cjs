// Runs entirely in an in-memory PostgreSQL instance. Never connects to Supabase.
const { PGlite } = require(process.env.PGLITE_MODULE || '@electric-sql/pglite');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const db = new PGlite();
const migrations = path.join(__dirname, '../supabase/migrations');
async function main() {
  await db.exec(`create role anon; create role authenticated; create role service_role;
    create schema auth; create schema storage; create schema extensions;
    create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
    create function auth.role() returns text language sql stable as $$select 'authenticated'::text$$;
    create function auth.jwt() returns jsonb language sql stable as $$select '{}'::jsonb$$;
    create table auth.users(id uuid primary key,phone text,email text,raw_user_meta_data jsonb default '{}',raw_app_meta_data jsonb default '{}',created_at timestamptz default now(),last_sign_in_at timestamptz,phone_confirmed_at timestamptz);
    create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
    create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text,owner uuid,owner_id text,metadata jsonb);
    create function storage.foldername(text) returns text[] language sql as $$select string_to_array($1,'/')$$;`);
  for(const file of fs.readdirSync(migrations).filter(f=>f.endsWith('.sql')).sort()) {
    if(/seed_five|seeded_provider_test/.test(file)) continue; // Data fixtures, not schema.
    // Legacy pg_get_functiondef text surgery depends on server pretty-printing.
    // It changes onboarding only and is superseded by later function definitions.
    if(file==='202608140007_fix_mobile_submission_block.sql') continue;
    const sql=fs.readFileSync(path.join(migrations,file),'utf8').replace(/create extension if not exists pgcrypto;/gi,'');
    try { await db.exec(sql); } catch(e) {throw new Error(`Migration ${file}: ${e.message}`, {cause:e});}
  }
  const superId='10000000-0000-4000-8000-000000000001';
  const adminId='10000000-0000-4000-8000-000000000002';
  const userId='10000000-0000-4000-8000-000000000003';
  const providerId='20000000-0000-4000-8000-000000000001';
  await db.exec(`insert into auth.users(id,phone) values('${superId}','919999999991'),('${adminId}','919999999992'),('${userId}','919999999993');
    insert into user_roles(user_id,role) values('${superId}','ADMIN'),('${adminId}','ADMIN');
    insert into admin_staff_profiles(user_id,staff_role) values('${superId}','SUPER_ADMIN'),('${adminId}','ADMINISTRATOR');
    insert into providers(id,legal_name,display_name,slug,dietary_type,support_phone) values('${providerId}','Test','Test kitchen','test-kitchen','VEG','9999999993');
    insert into provider_members(provider_id,user_id) values('${providerId}','${userId}');`);
  const asUser=id=>db.query("select set_config('request.jwt.claim.sub',$1,false)",[id]);
  const rpc=async(name,args)=> (await db.query(`select public.${name}(${args.map((_,i)=>`$${i+1}`).join(',')}) result`,args)).rows[0].result;
  await asUser(adminId);
  assert.equal(await rpc('can_manage_accounts',[]),true);
  assert.equal(await rpc('is_super_administrator',[]),false);
  assert.equal((await rpc('super_admin_account_search',['9999999993',0])).total,2);
  assert.equal((await rpc('super_admin_account_detail',['user',userId,0])).profile.id,userId);
  await db.exec('begin');
  assert.equal((await rpc('super_admin_delete_account',['provider',providerId,`DELETE ${providerId}`,'Administrator deletion test'])).deleted,true);
  assert.equal((await rpc('super_admin_delete_account',['user',userId,`DELETE ${userId}`,'Administrator deletion test'])).deleted,true);
  await db.exec('rollback');
  await db.query("update admin_staff_profiles set staff_role='FINANCE_MANAGER' where user_id=$1",[adminId]);
  await assert.rejects(()=>rpc('super_admin_account_search',['9999999993',0]),/Administrator access/);
  await db.query("update admin_staff_profiles set staff_role='ADMINISTRATOR' where user_id=$1",[adminId]);
  await db.query("delete from user_roles where user_id=$1 and role='ADMIN'",[adminId]);
  await assert.rejects(()=>rpc('super_admin_account_search',['9999999993',0]),/Administrator access/);
  await db.query("insert into user_roles(user_id,role) values($1,'ADMIN')",[adminId]);
  await asUser(userId);
  await assert.rejects(()=>rpc('super_admin_delete_account',['user',userId,`DELETE ${userId}`,'Testing authorization']),/Administrator access/);
  await asUser('');
  await assert.rejects(()=>rpc('super_admin_account_detail',['user',userId,0]),/Administrator access/);
  await asUser(superId);
  assert.equal((await db.query("select has_function_privilege('anon','public.super_admin_delete_account(text,uuid,text,text)','execute') allowed")).rows[0].allowed,false);
  await db.query('update profiles set is_active=false where id=$1',[superId]);
  await assert.rejects(()=>rpc('super_admin_account_search',['9999999993',0]),/Administrator access/);
  await db.query('update profiles set is_active=true where id=$1',[superId]);
  for(const phone of ['9999999993','+91 99999 99993','919999999993']) {
    const found=await rpc('super_admin_account_search',[phone,0]);assert.equal(found.total,2);
  }
  assert.equal((await rpc('super_admin_account_search',[userId,0])).total,2);
  assert.equal((await rpc('super_admin_account_search',[providerId,0])).total,1);
  assert.equal((await rpc('super_admin_account_search',['8888888888',0])).total,0);
  await assert.rejects(()=>rpc('super_admin_account_search',['%anything%',0]),/complete/);
  await assert.rejects(()=>rpc('super_admin_account_search',['9999999993',-1]),/Invalid page/);
  const user=await rpc('super_admin_account_detail',['user',userId,0]);assert.equal(user.wallet.balance_paise,0);
  assert.equal(user.providers.length,1);assert.equal(user.profile.id,userId);
  const provider=await rpc('super_admin_account_detail',['provider',providerId,0]);assert.equal(provider.wallet.available_paise,0);
  assert.equal(provider.members.length,1);
  assert.ok(Array.isArray(provider.menus));
  // Refuse blocked transactions and retain every dependent row.
  await db.exec(`begin; insert into packages(id,provider_id,name,kind,dietary_type,duration_days) values('30000000-0000-4000-8000-000000000001','${providerId}','Weekly lunch','LUNCH_ONLY','VEG',7);
    insert into payment_orders(customer_id,provider_id,package_id,receipt,package_amount_paise,amount_paise,test_mode,status)
    values('${userId}','${providerId}','30000000-0000-4000-8000-000000000001','real-test-fixture',100,100,false,'CREATED');`);
  assert.equal((await rpc('super_admin_deletion_preview',['provider',providerId])).allowed,false);
  await db.exec('savepoint expected_rejection');
  await assert.rejects(()=>rpc('super_admin_delete_account',['provider',providerId,`DELETE ${providerId}`,'Blocked payment test']),/Real payments/);
  await db.exec('rollback to savepoint expected_rejection');
  assert.equal((await db.query('select * from providers where id=$1',[providerId])).rows.length,1);
  assert.equal((await db.query('select * from admin_account_deletions')).rows.length,0);
  await db.exec("update payment_orders set test_mode=true,status='CAPTURED' where receipt='real-test-fixture'");
  assert.equal((await rpc('super_admin_deletion_preview',['provider',providerId])).allowed,false);
  await db.exec("update payment_orders set status='FAILED' where receipt='real-test-fixture'");
  await db.exec(`insert into provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at)
    values('${providerId}','MEAL_EARNING',10000,1400,1400,8600,now()-interval '1 hour'),('${providerId}','MEAL_EARNING',10000,1400,1400,8600,now()+interval '1 hour');
    insert into provider_payout_requests(provider_id,amount_paise,preferred_method,requested_by) values('${providerId}',1000,'UPI','${userId}');`);
  const balances=(await rpc('super_admin_account_detail',['provider',providerId,0])).wallet;
  assert.equal(balances.available_paise,7600);assert.equal(balances.pending_paise,8600);assert.equal(balances.reserved_paise,1000);
  assert.equal((await rpc('super_admin_deletion_preview',['provider',providerId])).allowed,false);
  await db.exec('rollback;'); // Reverts test accounting, including immutable journal fixtures.
  assert.equal((await rpc('super_admin_deletion_preview',['user',superId])).allowed,false);
  assert.equal((await rpc('super_admin_deletion_preview',['user',userId])).allowed,false);
  await assert.rejects(()=>rpc('super_admin_delete_account',['provider',providerId,'DELETE wrong','Testing a reset']),/confirmation/);
  assert.equal((await rpc('super_admin_deletion_preview',['provider',providerId])).allowed,true);
  await db.query("insert into storage.objects(bucket_id,name) values('provider-media',$1)",[`${providerId}/kitchen/test.jpg`]);
  const deleted=await rpc('super_admin_delete_account',['provider',providerId,`DELETE ${providerId}`,'Test provider reset']);
  assert.equal(deleted.deleted,true);assert.equal(deleted.cleanup_pending,true);
  assert.equal((await db.query('select * from admin_account_deletions where id=$1',[deleted.job_id])).rows[0].objects.length,1);
  assert.equal((await db.query('select * from auth.users where id=$1',[userId])).rows.length,1);
  await db.query('insert into customer_wallets(customer_id,balance_paise) values($1,100)',[userId]);
  assert.equal((await rpc('super_admin_deletion_preview',['user',userId])).allowed,false);
  await assert.rejects(()=>rpc('super_admin_delete_account',['user',userId,`DELETE ${userId}`,'Customer reset test']),/Wallet/);
  await db.query('update customer_wallets set balance_paise=0 where customer_id=$1',[userId]);
  await db.query("insert into customer_wallet_entries(customer_id,entry_type,amount_paise,description,reference_type,reference_id) select $1,'ADJUSTMENT',1,'Test','fixture',n::text from generate_series(1,26)n",[userId]);
  assert.equal((await rpc('super_admin_deletion_preview',['user',userId])).allowed,false);
  assert.equal((await rpc('super_admin_account_detail',['user',userId,0])).wallet_entries.length,25);
  assert.equal((await rpc('super_admin_account_detail',['user',userId,1])).wallet_entries.length,1);
  await db.query('delete from customer_wallet_entries where customer_id=$1',[userId]);
  await db.exec(`create table public.unexpected_dependency(user_id uuid references auth.users(id)); insert into unexpected_dependency values('${userId}');`);
  await assert.rejects(()=>rpc('super_admin_delete_account',['user',userId,`DELETE ${userId}`,'Dependency rollback test']),/No data was deleted/);
  assert.equal((await db.query('select * from profiles where id=$1',[userId])).rows.length,1);
  assert.equal((await db.query('select * from admin_account_deletions')).rows.length,1);
  await db.exec('drop table unexpected_dependency;');
  const removed=await rpc('super_admin_delete_account',['user',userId,`DELETE ${userId}`,'Customer reset test']);assert.equal(removed.deleted,true);
  assert.equal((await db.query('select * from auth.users where id=$1',[userId])).rows.length,0);
  assert.equal((await db.query('select * from profiles where id=$1',[userId])).rows.length,0);
  assert.equal((await db.query("select * from audit_logs where action='ACCOUNT_PERMANENTLY_DELETED'")).rows.length,2);
  assert.equal((await db.query("select count(*)::int n from pg_trigger where tgname in('finance_entry_immutable','finance_line_immutable') and tgenabled='O'")).rows[0].n,2);
  console.log('PASS: schema migrations, authorization, phone/ID search, detail reads, blockers, confirmation, provider/user deletion, cleanup outbox, audit records.');
}
main().catch(e=>{console.error(e.stack);if(e.cause)console.error(e.cause.message);process.exitCode=1;}).finally(()=>db.close());
