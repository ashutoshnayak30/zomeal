// Offline PostgreSQL checks. No network calls or production records.
const {PGlite}=require('@electric-sql/pglite');
const fs=require('fs');const path=require('path');const assert=require('assert/strict');
const db=new PGlite();
const admin='10000000-0000-4000-8000-000000000001',customer='10000000-0000-4000-8000-000000000002';
async function main(){
 await db.exec(`create role anon;create role authenticated;create schema auth;create schema storage;
 create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
 create function public.can_manage_accounts() returns boolean language sql stable as $$select auth.uid()='${admin}'::uuid$$;
 create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
 create table storage.objects(bucket_id text,name text);alter table storage.objects enable row level security;
 create table public.packages(id uuid primary key,duration_days integer);
 create table public.customer_subscriptions(customer_id uuid,package_id uuid,status text,end_date date,created_at timestamptz default now());
 grant usage on schema auth,public,storage to authenticated;
 `);
 await db.exec(fs.readFileSync(path.join(__dirname,'../supabase/migrations/202609110001_home_banners.sql'),'utf8'));
 await db.exec(`insert into storage.objects values('home-banners','images/test.webp');`);
 async function user(id){await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id])}
 async function save(payload,version=null){return (await db.query('select public.admin_save_home_banner($1::jsonb,$2::timestamptz) value',[JSON.stringify(payload),version])).rows[0].value}
 async function feed(){return (await db.query('select public.customer_home_banners() value')).rows[0].value.banners}
 const payload={title:'Test',image_path:'images/test.webp',destination:'WALLET',destination_value:'',audience:'ALL',sort_order:0,enabled:false};
 await user(admin);let banner=await save(payload);
 await user(customer);assert.equal((await feed()).length,0);await assert.rejects(save(payload),/Administrator/);
 await db.exec('set role authenticated');assert.equal((await db.query('select * from home_banners')).rows.length,0);await assert.rejects(db.query("update home_banners set enabled=true"),/permission denied/);await db.exec('reset role');
 await user(admin);banner=await save({...banner,enabled:true},banner.updated_at);
 await user(customer);assert.equal((await feed()).length,1);
 await user(admin);await assert.rejects(save({...banner,title:'Stale'},'2000-01-01T00:00:00Z'),/changed elsewhere/);
 await assert.rejects(save({...payload,destination:'HTTPS',destination_value:'javascript:alert(1)'}),/check constraint/);
 await assert.rejects(save({...payload,image_path:'images/missing.webp'}),/Upload/);
 await assert.rejects(save({...payload,starts_at:'2030-01-02',ends_at:'2030-01-01'}),/check constraint/);
 banner=await save({...banner,audience:'WEEKLY'},banner.updated_at);
 await user(customer);assert.equal((await feed()).length,0);
 await db.exec(`insert into packages values('20000000-0000-4000-8000-000000000001',7);insert into customer_subscriptions(customer_id,package_id,status,end_date) values('${customer}','20000000-0000-4000-8000-000000000001','ACTIVE',current_date+7);`);
 assert.equal((await feed()).length,1);
 await user(admin);banner=await save({...banner,ends_at:'2000-01-01'},banner.updated_at);
 await user(customer);assert.equal((await feed()).length,0);
 await user(admin);await db.query('select admin_delete_home_banner($1,$2)',[banner.id,banner.updated_at]);assert.equal((await feed()).length,0);
 assert.equal((await db.query('select count(*)::int n from home_banner_audit')).rows[0].n,5);
 await user('');await assert.rejects(feed(),/Sign in/);
 console.log('PASS: admin-only writes, RLS, drafts, publication, audiences, expiry, stale edits, link validation, deletion and audit.');
 await db.close();
}
main().catch(e=>{console.error(e);process.exit(1)});
