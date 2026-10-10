const assert=require('node:assert/strict');
module.exports=async(db,admin)=>{
 await db.exec('begin');
 const uid=crypto.randomUUID();
 const as=id=>db.query("select set_config('request.jwt.claim.sub',$1,false)",[id]);
 const rpc=async(n,a=[])=>(await db.query(`select ${n}(${a.map((_,i)=>'$'+(i+1)).join(',')}) r`,a)).rows[0].r;
 const reject=async(fn,re)=>{await db.exec('savepoint failure');try{await assert.rejects(fn,re);}finally{await db.exec('rollback to savepoint failure;release savepoint failure');}};
 try{
  await db.query("insert into auth.users(id,phone) values($1,'919876543210')",[uid]);await as(admin);
  await rpc('admin_customer_action',[uid,'VERIFY','Verified profile with customer']);
  assert.ok((await db.query('select admin_reviewed_at from profiles where id=$1',[uid])).rows[0].admin_reviewed_at);
  assert.equal((await db.query('select phone_confirmed_at from auth.users where id=$1',[uid])).rows[0].phone_confirmed_at,null);
  await rpc('admin_customer_action',[uid,'BLOCK','Customer requested access block']);
  assert.equal((await db.query('select is_active from profiles where id=$1',[uid])).rows[0].is_active,false);
  assert.ok((await db.query('select banned_until>now() banned from auth.users where id=$1',[uid])).rows[0].banned);
  await as(uid);await reject(()=>db.query('update profiles set is_active=true where id=$1',[uid]),/Administrator/);
  await reject(()=>rpc('admin_payment_register',['2026-10-01','2026-10-10','', '',0]),/Finance/);
  await reject(()=>rpc('admin_ceo_report',['2026-10-01','2026-10-10']),/analytics/);
  await as(admin);await rpc('admin_customer_action',[uid,'UNBLOCK','Customer access review complete']);
  await reject(()=>rpc('admin_customer_action',[admin,'BLOCK','Cannot block administrator']),/staff/);
  await reject(()=>rpc('admin_customer_action',[uid,'VERIFY','short']),/reason/);
  const report=await rpc('admin_ceo_report',['2026-10-01','2026-10-10']);assert.ok(report.ceo);assert.ok(report.ceo.registered_customers>=1);
  const payments=await rpc('admin_payment_register',['2026-10-01','2026-10-10','','',0]);assert.ok(Array.isArray(payments.rows));
  const provider=crypto.randomUUID(),pkg=crypto.randomUUID();
  await db.query("insert into providers(id,legal_name,display_name,slug,dietary_type) values($1::uuid,'Register','Register fixture',($1::uuid)::text,'VEG')",[provider]);
  await db.query("insert into packages(id,provider_id,name,kind,dietary_type,duration_days) values($1,$2,'Test','LUNCH_ONLY','VEG',7)",[pkg,provider]);
  await db.query("insert into payment_orders(customer_id,provider_id,package_id,receipt,package_amount_paise,amount_paise,test_mode,status,created_at) select $1,$2,$3,'workspace-'||n,5000,5000,false,'FAILED','2026-09-30T18:30:00Z' from generate_series(1,51)n",[uid,provider,pkg]);
  await db.query("insert into payment_orders(customer_id,provider_id,package_id,receipt,package_amount_paise,amount_paise,test_mode,status,created_at) values($1,$2,$3,'workspace-test',5000,5000,true,'FAILED','2026-10-01T00:00:00Z'),($1,$2,$3,'workspace-before',5000,5000,false,'FAILED','2026-09-30T18:29:59Z')",[uid,provider,pkg]);
  const a=await rpc('admin_payment_register',['2026-10-01','2026-10-01','FAILED','Register fixture',0]);
  const b=await rpc('admin_payment_register',['2026-10-01','2026-10-01','FAILED','Register fixture',1]);
  assert.equal(a.total,51);assert.equal(a.rows.length,50);assert.equal(b.rows.length,1);assert.equal(new Set([...a.rows,...b.rows].map(r=>r.id)).size,51);
  await reject(()=>rpc('admin_payment_register',['2026-10-10','2026-10-01','','',0]),/Invalid/);
  const payload={category:'OFFER',app_kind:'CUSTOMER',audience:'ALL',title:'Offer test',message:'Test only',start_at:new Date(Date.now()+600000).toISOString(),ends_at:new Date(Date.now()+86400000).toISOString(),daytime_only:true};
  const id=await rpc('admin_save_notification_schedule',[payload]);assert.equal((await db.query('select category from notification_schedules where id=$1',[id])).rows[0].category,'OFFER');
  await reject(()=>rpc('admin_save_notification_schedule',[{...payload,category:'INVALID'}]),/category/);
  console.log('PASS: admin customer review/ban/unban, self-reactivation denied, OTP unchanged, role checks, reporting and campaign categories');
 }finally{await db.exec('rollback');await as(admin);}
};
