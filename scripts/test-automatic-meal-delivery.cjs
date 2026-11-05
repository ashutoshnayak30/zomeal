const assert=require('node:assert/strict');
module.exports=async db=>{
 await db.exec('begin');
 try{
  const u='95000000-0000-4000-8000-000000000001',p='95000000-0000-4000-8000-000000000002',pkg='95000000-0000-4000-8000-000000000003';
  await db.query("insert into auth.users(id,phone) values($1,'918787878787')",[u]);
  await db.query("insert into providers(id,legal_name,display_name,slug,dietary_type) values($1,'Delivery test','Delivery test','auto-delivery-test','BOTH')",[p]);
  await db.query("insert into packages(id,provider_id,name,kind,dietary_type,duration_days) values($1,$2,'Both','LUNCH_AND_DINNER','BOTH',30)",[pkg,p]);
  const sub=(await db.query("insert into customer_subscriptions(customer_id,provider_id,package_id,status,start_date,end_date,package_price_paise) values($1,$2,$3,'ACTIVE','2090-01-01','2090-01-30',300000) returning id",[u,p,pkg])).rows[0].id;
  await db.query('insert into customer_wallets(customer_id,balance_paise) values($1,100000)',[u]);
  for(const slot of ['LUNCH','DINNER'])await db.query("insert into subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,meal_value_paise) values($1,$2,$3,'2090-01-01',$4,7500)",[sub,p,u,slot]);
  const run=async time=>(await db.query('select process_automatic_meal_delivery($1) n',[time])).rows[0].n;
  assert.equal(await run('2090-01-01T14:29:59+05:30'),0);
  assert.equal(await run('2090-01-01T14:30:00+05:30'),1);
  assert.equal(await run('2090-01-01T14:30:00+05:30'),0);
  assert.equal(await run('2090-01-01T21:49:59+05:30'),0);
  assert.equal(await run('2090-01-01T21:50:00+05:30'),1);
  assert.equal(await run('2090-01-01T22:00:00+05:30'),0);
  assert.equal((await db.query("select count(*)::int n from provider_financial_ledger where subscription_id=$1 and entry_type='MEAL_EARNING'",[sub])).rows[0].n,2);
  assert.equal((await db.query("select count(*)::int n from subscription_meals where subscription_id=$1 and status='DELIVERED' and auto_delivered_at is not null",[sub])).rows[0].n,2);
  for(const [day,slot,status] of [['02','LUNCH','PAUSED'],['02','DINNER','CANCELLED'],['03','LUNCH','SCHEDULED'],['04','LUNCH','SCHEDULED']])await db.query("insert into subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,status,meal_value_paise) values($1,$2,$3,$4,$5,$6,7500)",[sub,p,u,'2090-01-'+day,slot,status]);
  assert.equal(await run('2090-01-02T23:00:00+05:30'),0);
  await db.query('update customer_wallets set balance_paise=0 where customer_id=$1',[u]);
  assert.equal(await run('2090-01-03T23:00:00+05:30'),0,'insufficient funds excluded');
  await db.query('update customer_wallets set balance_paise=100000 where customer_id=$1',[u]);
  assert.equal(await run('2090-01-04T14:30:00+05:30'),1,'no historical backfill');
  assert.equal((await db.query("select status from subscription_meals where subscription_id=$1 and service_date='2090-01-03'",[sub])).rows[0].status,'SCHEDULED');
  for(const role of ['anon','authenticated'])assert.equal((await db.query("select has_function_privilege($1,'process_automatic_meal_delivery(timestamptz)','execute') ok",[role])).rows[0].ok,false);
  console.log('PASS: automatic delivery IST boundaries, no duplicates, earnings once, paused/cancelled/funding exclusions, no historical backfill, private worker');
 }finally{await db.exec('rollback');}
};
