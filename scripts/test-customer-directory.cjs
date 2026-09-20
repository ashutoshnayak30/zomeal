// Called by test-admin-accounts.cjs after the real migration chain is loaded.
// All fixtures live in one rolled-back transaction, never a remote database.
const assert=require('node:assert/strict');
module.exports=async function testCustomerDirectory(db,administrator){
  const id=n=>`60000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
  const provider='61000000-0000-4000-8000-000000000001';
  const pkg='62000000-0000-4000-8000-000000000001';
  const asUser=user=>db.query("select set_config('request.jwt.claim.sub',$1,false)",[user]);
  const get=async(q='',filter='ALL',page=0)=>(await db.query('select public.admin_customer_directory($1,$2,$3) value',[q,filter,page])).rows[0].value;
  await db.exec('begin');
  try {
    await asUser(administrator);
    const before=(await get()).summary;
    for(let n=1;n<=36;n++)await db.query(`insert into auth.users(id,phone,raw_user_meta_data,created_at)
      values($1,$2,$3,now()-($4::integer * interval '1 minute'))`,
      [id(n),n===36?null:`91900000${String(n).padStart(4,'0')}`,JSON.stringify({full_name:n===1?'Asha <script>':n===2?'100% Real':`Directory fixture ${n}`,account_type:n===36?'CUSTOMER_TEST':'CUSTOMER'}),n]);
    await db.query("update profiles set full_name='Customer',phone=null,registration_pincode='751019' where id=$1",[id(1)]);
    await db.query('update profiles set is_active=false where id=$1',[id(33)]);
    await db.query("insert into user_roles(user_id,role) values($1,'ADMIN'),($2,'PROVIDER')",[id(34),id(35)]);
    await db.query("insert into providers(id,legal_name,display_name,slug,dietary_type) values($1,'Directory fixture','Directory kitchen','directory-fixture','BOTH')",[provider]);
    await db.query('insert into provider_members(provider_id,user_id) values($1,$2)',[provider,id(35)]);
    await db.query("insert into packages(id,provider_id,name,kind,dietary_type,duration_days) values($1,$2,'Monthly both','LUNCH_AND_DINNER','BOTH',30)",[pkg,provider]);
    const addSub=async(n,status,start,end)=>db.query(`insert into customer_subscriptions(customer_id,provider_id,package_id,status,start_date,end_date,delivery_address,package_price_paise)
      values($1,$2,$3,$4,(now() at time zone 'Asia/Kolkata')::date+$5::integer,(now() at time zone 'Asia/Kolkata')::date+$6::integer,'{"pincode":"757043","city":"Current city"}',310000)`,[id(n),provider,pkg,status,start,end]);
    await addSub(1,'ACTIVE',0,29);
    await addSub(1,'ACTIVE',30,59); // Future relocation must not displace today.
    await addSub(2,'PAUSED',-1,28);
    await addSub(3,'ACTIVE',-40,-11); // Stale ACTIVE status must read Expired.
    await addSub(4,'ACTIVE',1,30);
    await addSub(5,'CANCELLED',0,29);
    await addSub(6,'CANCEL_PENDING',0,29);
    await addSub(7,'ACTIVE',-29,0); // Inclusive end date.
    await addSub(7,'COMPLETED',-80,-51);
    await addSub(33,'ACTIVE',0,29); // Disabled account is not an active user.
    await db.query('insert into customer_wallets(customer_id,balance_paise) values($1,12345)',[id(1)]);
    const all=await get();
    assert.equal(all.summary.total_accounts-before.total_accounts,34);
    assert.equal(all.summary.registered-before.registered,33);
    assert.equal(all.summary.incomplete-before.incomplete,1);
    assert.equal(all.summary.active-before.active,2);
    assert.equal(all.summary.paused-before.paused,1);
    assert.equal(all.summary.no_plan-before.no_plan,28);
    assert.equal(all.summary.inactive-before.inactive,1);
    const asha=(await get('ASHA')).customers[0];
    assert.equal(asha.full_name,'Asha <script>');assert.equal(asha.phone,'+919000000001');
    assert.equal(asha.balance_paise,12345);assert.equal(asha.subscription_state,'ACTIVE');
    assert.equal(asha.subscription.package_name,'Monthly both');assert.equal(asha.pincode,'757043');
    assert.equal(asha.subscription.package_price_paise,310000);
    for(const q of ['9000000001','+91 90000 00001','919000000001',id(1)])assert.equal((await get(q)).customers[0].id,id(1));
    assert.equal((await get('%')).total,1); // Literal search, not SQL wildcard.
    assert.equal((await get('ASHA','PAUSED')).total,0);
    assert.equal((await get(id(2),'PAUSED')).total,1);
    assert.equal((await get(id(3))).customers[0].subscription_state,'EXPIRED');
    assert.equal((await get(id(4))).customers[0].subscription_state,'UPCOMING');
    assert.equal((await get(id(5))).customers[0].subscription_state,'CANCELLED');
    assert.equal((await get(id(33),'ACTIVE')).total,0);
    assert.equal((await get(id(36),'INCOMPLETE')).customers[0].is_test_account,true);
    assert.equal((await get(id(36),'REGISTERED')).total,0);
    assert.equal((await get(id(35))).total,0); // Provider-only auth identity excluded.
    await db.query("update profiles set registration_pincode='757043' where id=$1",[id(35)]);
    assert.equal((await get(id(35))).total,1); // Provider who is also a customer.
    await db.query('update profiles set registration_pincode=null where id=$1',[id(35)]);
    const pages=[];for(let page=0;page<Math.ceil(all.total/25);page++)pages.push(...(await get('','ALL',page)).customers);
    assert.equal(pages.length,all.total);assert.equal(new Set(pages.map(c=>c.id)).size,all.total);
    assert.deepEqual((await get('missing')).summary,all.summary); // Global totals are not search totals.
    assert.equal((await get('','ALL',999)).customers.length,0);
    // RPC rejects invalid requests even when the page is called without the UI.
    for(const [q,filter,page,message] of [['','BAD',0,/filter/],['','ALL',-1,/page/],['x'.repeat(101),'ALL',0,/100 characters/]]){
      await db.exec('savepoint invalid_input');await assert.rejects(get(q,filter,page),message);await db.exec('rollback to savepoint invalid_input');
    }
    for(const user of [id(1),id(35),'']){
      await asUser(user);await db.exec('savepoint denied');await assert.rejects(get(),/Administrator access/);await db.exec('rollback to savepoint denied');
    }
    await asUser(administrator);
    assert.equal((await db.query("select has_function_privilege('anon','public.admin_customer_directory(text,text,integer)','execute') allowed")).rows[0].allowed,false);
    assert.equal((await db.query("select count(*)::int n from audit_logs where action='CUSTOMER_DIRECTORY_VIEWED' and metadata::text like '%9000000001%'")).rows[0].n,0);
    console.log('PASS: customer directory counts, unique pagination, phone/name search, incomplete registrations, time-aware plans, wallet, mixed roles and private access.');
  } finally {await db.exec('rollback');await asUser(administrator);}
};
