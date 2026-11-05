const assert=require('node:assert/strict');
module.exports=async(db)=>{
 await db.exec('begin');
 try {
  const owner=crypto.randomUUID(),other=crypto.randomUUID(),p=crypto.randomUUID(),q=crypto.randomUUID();
  await db.query('insert into auth.users(id) values($1),($2)',[owner,other]);
  for(const id of [p,q]) await db.query("insert into providers(id,legal_name,display_name,slug,dietary_type) values($1::uuid,'History','History',($1::uuid)::text,'VEG')",[id]);
  await db.query('insert into provider_members(provider_id,user_id) values($1,$2),($3,$4)',[p,owner,q,other]);
  await db.query("insert into provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,created_at) select $1,'MEAL_EARNING',6500,1400,910,5590,now(),'2026-10-01T00:00:00Z' from generate_series(1,55)",[p]);
  await db.query("insert into provider_financial_ledger(provider_id,entry_type,gross_paise,commission_basis_points,commission_paise,provider_net_paise,available_at,created_at) values($1,'MEAL_EARNING',6500,1400,910,5590,now(),'2026-09-30T18:29:59Z'),($1,'MEAL_EARNING',6500,1400,910,5590,now(),'2026-09-30T18:30:00Z')",[p]);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[owner]);
  const months=async()=>(await db.query('select provider_transaction_months() r')).rows[0].r.months;
  const page=async(t=null,id=null)=>(await db.query("select provider_transaction_page('2026-10-01',$1,$2) r",[t,id])).rows[0].r;
  assert.deepEqual((await months()).map(x=>x.entry_count),[56,1]);
  const first=await page(); assert.equal(first.entries.length,51);assert.equal(first.has_more,true);
  const cursor=first.entries[49],second=await page(cursor.created_at,cursor.id);
  assert.equal(second.entries.length,6);assert.equal(second.has_more,false);
  assert.equal(new Set([...first.entries.slice(0,50),...second.entries].map(x=>x.id)).size,56);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[other]);
  assert.equal((await months()).length,0);assert.equal((await page()).entries.length,0);
  console.log('PASS: earnings history ownership, IST month boundaries, same-time keyset pagination and no missing/duplicate entries');
 } finally {await db.exec('rollback');}
};
