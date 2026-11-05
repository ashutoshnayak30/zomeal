const assert=require('node:assert/strict');
module.exports=async(db,admin)=>{
 await db.exec('begin');
 try {
 const id=()=>crypto.randomUUID(); const p=id(),u=id(),v=id(),pkg=id(),old=id(),fresh=id(),a=id(),b=id(),c=id(),d=id();
 await db.query("insert into auth.users(id) values($1),($2)",[u,v]);
 await db.query("insert into push_device_tokens(user_id,token,app_kind) values($1,'menu-sync-test-device','CUSTOMER')",[u]);
 await db.query("insert into providers(id,legal_name,display_name,slug,dietary_type) values($1,'Sync','Sync kitchen',$2,'BOTH')",[p,p]);
 await db.query("insert into packages(id,provider_id,name,kind,dietary_type,duration_days) values($1,$2,'Both','LUNCH_AND_DINNER','BOTH',30)",[pkg,p]);
 const rice=id();
 await db.query("insert into menu_items(id,provider_id,name,category,dietary_type,status,created_by,reviewed_by,reviewed_at) values($1,$2,'Rice, dal, salad','SIDE','BOTH','APPROVED',$3,$3,now())",[rice,p,admin]);
 for(const [i,name,diet] of [[a,'Old veg','VEG'],[b,'Old meat','NON_VEG'],[c,'New veg','VEG'],[d,'New meat','NON_VEG']])await db.query("insert into menu_items(id,provider_id,name,category,dietary_type,status,created_by,reviewed_by,reviewed_at) values($1,$2,$3,'MAIN_COURSE',$4,'APPROVED',$5,$5,now())",[i,p,name,diet,admin]);
 for(const menu of [old,fresh]){
 await db.query("insert into provider_menus(id,provider_id,name,status,valid_from,created_by) values($1,$2,($1::uuid)::text,'DRAFT',current_date-1,$3)",[menu,p,admin]);
 for(let day=1;day<=7;day++)for(const slot of ['LUNCH','DINNER']){
 const md=(await db.query("insert into menu_days(menu_id,day_of_week,meal_slot) values($1,$2,$3) returning id",[menu,day,slot])).rows[0].id;
 await db.query("insert into menu_day_choices(menu_day_id,menu_item_id,choice_group,is_default) values($1,$2,'SIDE',true)",[md,rice]);
 for(const [n,i] of (menu===old?[a,b]:[c,d]).entries())await db.query("insert into menu_day_choices(menu_day_id,menu_item_id,choice_group,display_order,is_default) values($1,$2,'MAIN_COURSE',$3,$4)",[md,i,n,n===0]);
 }
 await db.query("update provider_menus set status='APPROVED',reviewed_by=$2,reviewed_at=now() where id=$1",[menu,admin]);
 }
 await db.query("update provider_menus set status='ARCHIVED' where id=$1",[old]);
 const sub=(await db.query("insert into customer_subscriptions(customer_id,provider_id,package_id,status,start_date,end_date) values($1,$2,$3,'ACTIVE',current_date-1,current_date+30) returning id",[u,p,pkg])).rows[0].id;
 const template=(await db.query("insert into customer_weekly_menu_templates(customer_id,package_id,dietary_preference) values($1,$2,'BOTH') returning id",[u,pkg])).rows[0].id;
 await db.query("insert into customer_weekly_menu_selections(template_id,day_of_week,meal_slot,choice_group,menu_item_id) values($1,1,'LUNCH','MAIN_COURSE',$2),($1,2,'DINNER','MAIN_COURSE',$3)",[template,a,b]);
 const meals=[];for(const [days,status]of [[1,'SCHEDULED'],[2,'PAUSED'],[3,'CANCELLED'],[-1,'DELIVERED'],[4,'PREPARING']]){
 meals.push((await db.query("insert into subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id,status,meal_value_paise) values($1,$2,$3,current_date+$4::int,'LUNCH',$5,$6,6500) returning id",[sub,p,u,days,a,status])).rows[0].id);
 }
 const rpc=async(n,args=[])=>(await db.query('select '+n+'('+args.map((_,i)=>'$'+(i+1)).join(',')+') result',args)).rows[0].result;
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[admin]);
 const preview=await rpc('sync_customer_menu',[fresh,'test',false]);assert.equal(preview.customers,1);assert.equal(preview.conflicts.length,0);
 await db.exec('savepoint conflict');await db.query('delete from menu_day_choices where menu_item_id=$1',[d]);
 await assert.rejects(()=>rpc('sync_customer_menu',[fresh,'conflict',true]),/dietary conflicts/);await db.exec('rollback to savepoint conflict');
 assert.equal((await db.query("select count(*)::int n from customer_notifications where dedupe_key='MENU_UPDATE_conflict_WITH_SIDES'")).rows[0].n,0);
 assert.equal((await db.query("select has_function_privilege('authenticated','sync_customer_menu(uuid,text,boolean)','execute') allowed")).rows[0].allowed,false);
 assert.equal((await db.query('select selected_menu_item_id from subscription_meals where id=$1',[meals[0]])).rows[0].selected_menu_item_id,a);
 await rpc('sync_customer_menu',[fresh,'test',true]);await rpc('sync_customer_menu',[fresh,'test',true]);
 const sels=(await db.query('select menu_item_id from customer_weekly_menu_selections where template_id=$1',[template])).rows.map(r=>r.menu_item_id);assert.deepEqual(new Set(sels),new Set([c,d]));
 for(let i=0;i<meals.length;i++)assert.equal((await db.query('select selected_menu_item_id from subscription_meals where id=$1',[meals[i]])).rows[0].selected_menu_item_id,i<2?c:a);
 assert.equal((await db.query("select count(*)::int n from customer_notifications where customer_id=$1 and dedupe_key='MENU_UPDATE_test_WITH_SIDES'",[u])).rows[0].n,1);
 assert.equal((await db.query('select count(*)::int n from customer_menu_updates where customer_id=$1',[u])).rows[0].n,1);
 assert.equal((await db.query("select count(*)::int n from meal_push_outbox o join customer_notifications n on n.id=o.notification_id where n.customer_id=$1 and n.dedupe_key='MENU_UPDATE_test_WITH_SIDES'",[u])).rows[0].n,1);
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[u]);assert.equal((await rpc('customer_menu_update_history')).items.length,1);
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[v]);assert.equal((await rpc('customer_menu_update_history')).items.length,0);
 await db.exec('savepoint denied');await assert.rejects(()=>rpc('admin_menu_sync',[id(),true]),/Admin access/);await db.exec('rollback to savepoint denied');
 assert.equal(await rpc('menu_replacement_item',[fresh,b,1,'LUNCH']),d);
 // Provider submits exact dates; admin approves once. Recurring templates stay intact.
 await db.query("insert into provider_media(provider_id,media_type,status,storage_bucket,storage_path,mime_type,size_bytes,uploaded_by,reviewed_by,reviewed_at) values($1,'MEAL','APPROVED','provider-media',$1::uuid||'/test.jpg','image/jpeg',10,$2,$2,now())",[p,admin]);
 await db.query("update providers set support_phone='9999999988' where id=$1",[p]);
 await db.query("insert into provider_delivery_personnel(provider_id,full_name,phone,created_by) values($1,'Test rider','9999999988',$2)",[p,admin]);
 await db.query("update providers set status='ACTIVE',approved_by=$2,approved_at=now() where id=$1",[p,admin]);
 await db.query('insert into provider_members(provider_id,user_id) values($1,$2)',[p,v]);
 const date=(await db.query("select to_char(current_date+1,'YYYY-MM-DD') date")).rows[0].date;
 await rpc('provider_dated_menu_changes',[{old_item:c,new_name:'Temporary veg',slot:'LUNCH',dates:[date]}]);
 const request=(await rpc('provider_dated_menu_changes')).items[0];
 await db.exec('savepoint denied2');await assert.rejects(()=>rpc('admin_dated_menu_changes',[request.id,'APPROVED',null]),/Admin access/);await db.exec('rollback to savepoint denied2');
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[admin]);
 await rpc('admin_dated_menu_changes',[request.id,'APPROVED','Available for this date only']);
 await rpc('admin_dated_menu_changes',[request.id,'APPROVED','Retry']);
 const changed=(await db.query('select * from subscription_meals where id=$1',[meals[0]])).rows[0];
 assert.notEqual(changed.selected_menu_item_id,c);assert.equal(Number(changed.meal_value_paise),6500);
 assert.equal((await db.query('select selected_menu_item_id from subscription_meals where id=$1',[meals[1]])).rows[0].selected_menu_item_id,c);
 assert.equal((await db.query('select count(*)::int n from customer_weekly_menu_selections where template_id=$1 and menu_item_id=$2',[template,c])).rows[0].n,1);
 assert.equal((await db.query("select count(*)::int n from customer_notifications where customer_id=$1 and dedupe_key=$2",[u,'MENU_DATE_'+request.id])).rows[0].n,1);
 const newSub=(await db.query("insert into customer_subscriptions(customer_id,provider_id,package_id,status,start_date,end_date) values($1,$2,$3,'ACTIVE',current_date,current_date+30) returning id",[v,p,pkg])).rows[0].id;
 const laterMeal=(await db.query("insert into subscription_meals(subscription_id,provider_id,customer_id,service_date,meal_slot,selected_menu_item_id) values($1,$2,$3,current_date+1,'LUNCH',$4) returning selected_menu_item_id",[newSub,p,v,c])).rows[0];
 assert.equal(laterMeal.selected_menu_item_id,changed.selected_menu_item_id,'later-created meal uses approved dated replacement');
 const late=(await db.query("insert into dated_menu_changes(provider_id,old_item,new_name,meal_slot,service_dates,requested_by) values($1,$2,'Too late','LUNCH',array[current_date-1],$3) returning id",[p,c,v])).rows[0].id;
 await db.exec('savepoint late');await assert.rejects(()=>rpc('admin_dated_menu_changes',[late,'APPROVED',null]),/past preparation cutoff/);await db.exec('rollback to savepoint late');
 await rpc('sync_customer_menu',[fresh,'after-date',true]);
 assert.equal((await db.query('select selected_menu_item_id from subscription_meals where id=$1',[meals[0]])).rows[0].selected_menu_item_id,changed.selected_menu_item_id);
 console.log('PASS: dated replacements, provider/admin separation, unchanged weekly choices/prices, exact dates and retry deduplication');
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[v]);
 const payload={businessName:'Sync kitchen',contactName:'Owner',address:'Test street',city:'Bhubaneswar',state:'Odisha',pincode:'751019',dietaryType:'BOTH',lunchEnabled:false,dinnerEnabled:false,bothEnabled:true,bothPrice:'3000',weeklyBothPrice:'700',bothLunchDailyPrice:'50',deliveryName:'Test rider',deliveryPhone:'9999999988',menus:['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'].map(day=>({day,lunchFixed:'Pulao, dal, salad',dinnerFixed:'Pulao, dal, salad',lunch:[{name:'Newest veg',foodType:'VEG'},{name:'Newest meat',foodType:'NON_VEG'}],dinner:[{name:'Newest veg',foodType:'VEG'},{name:'Newest meat',foodType:'NON_VEG'}]}))};
 const full=await rpc('provider_submit_business_update',[payload]);
 // Legacy merge failures could drop option 2 despite retaining it in the payload.
 await db.query("delete from menu_day_choices c using menu_days d,provider_menus m,menu_items i where c.menu_day_id=d.id and d.menu_id=m.id and c.menu_item_id=i.id and m.change_request_id=$1 and d.day_of_week=2 and d.meal_slot='LUNCH' and i.name='Newest meat'",[full.change_request_id]);
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[admin]);
 const approved=await rpc('admin_review_provider_business_update',[full.change_request_id,'APPROVED','Verified menu',payload]);
 assert.equal(approved.status,'APPROVED');assert.ok(approved.customer_menu_impact.customers>0);
 assert.equal((await db.query("select count(*)::int n from menu_day_choices c join menu_days d on d.id=c.menu_day_id join provider_menus m on m.id=d.menu_id where m.change_request_id=$1 and d.day_of_week=2 and d.meal_slot='LUNCH' and c.choice_group='MAIN_COURSE'",[full.change_request_id])).rows[0].n,2);
 assert.equal((await db.query('select selected_menu_item_id from subscription_meals where id=$1',[meals[0]])).rows[0].selected_menu_item_id,changed.selected_menu_item_id);

 assert.ok(approved.customer_menu_impact.changes.some(x=>x.category==='SIDE'));
 for(let i=0;i<meals.length;i++){
  const meal=(await db.query('select side_dishes,meal_value_paise,status from subscription_meals where id=$1',[meals[i]])).rows[0];
  assert.equal(meal.side_dishes[0].name,i<2?'Pulao, dal, salad':'Rice, dal, salad','only eligible sides update, including dated main-course override');
  assert.equal(Number(meal.meal_value_paise),6500);
 }
 const key='MENU_UPDATE_'+full.change_request_id+'_WITH_SIDES';
 assert.equal((await db.query('select count(*)::int n from customer_notifications where customer_id=$1 and dedupe_key=$2',[u,key])).rows[0].n,1);
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[u]);
 const state=await rpc('customer_active_subscription_state');
 assert.equal(state.daily_meals.find(x=>x.id===meals[0]).side_text,'Pulao, dal, salad');
 // No main-course edit: shared sides must still update and notify.
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[v]);
 payload.menus.forEach(day=>{day.lunchFixed='Veg biriyani, raita';day.dinnerFixed='Veg biriyani, raita'});
 await db.query("update provider_change_requests set requested_at=now()-interval '1 minute' where id=$1",[full.change_request_id]);
 await db.query("update provider_menus set name=name||' first approval' where change_request_id=$1",[full.change_request_id]);
 const sidesOnly=await rpc('provider_submit_business_update',[payload]);
 // The isolated test shares a transaction timestamp; real approvals use separate transactions.
 await db.query("update package_price_versions set effective_from=now()-interval '1 second' where package_id in(select id from packages where provider_id=$1) and status='APPROVED'",[p]);
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[admin]);
 const sidePreview=await rpc('admin_menu_sync',[sidesOnly.change_request_id,false]);
 assert.ok(sidePreview.changes.length>0);assert.ok(sidePreview.changes.every(x=>x.category==='SIDE'));
 assert.equal((await db.query('select side_dishes from subscription_meals where id=$1',[meals[0]])).rows[0].side_dishes[0].name,'Pulao, dal, salad','preview does not mutate sides');
 const sideApproved=await rpc('admin_review_provider_business_update',[sidesOnly.change_request_id,'APPROVED',null,payload]);
 assert.ok(sideApproved.customer_menu_impact.changes.every(x=>x.category==='SIDE'));
 await rpc('admin_menu_sync',[sidesOnly.change_request_id,true]);
 const sideKey='MENU_UPDATE_'+sidesOnly.change_request_id+'_WITH_SIDES';
 assert.equal((await db.query('select count(*)::int n from customer_notifications where customer_id=$1 and dedupe_key=$2',[u,sideKey])).rows[0].n,1);
 assert.equal((await db.query('select count(*)::int n from meal_push_outbox o join customer_notifications n on n.id=o.notification_id where n.customer_id=$1 and n.dedupe_key=$2',[u,sideKey])).rows[0].n,1);
 assert.equal((await db.query('select side_dishes from subscription_meals where id=$1',[meals[0]])).rows[0].side_dishes[0].name,'Veg biriyani, raita');
 // Future side removals are real updates too, not a fallback to stale cached rice.
 await db.exec('savepoint removal');
 const target=(await db.query('select id from provider_menus where change_request_id=$1',[sidesOnly.change_request_id])).rows[0].id;
 await db.query("delete from menu_day_choices c using menu_days d where c.menu_day_id=d.id and d.menu_id=$1 and c.choice_group<>'MAIN_COURSE'",[target]);
 await rpc('sync_customer_menu',[target,'remove-sides',true]);
 assert.deepEqual((await db.query('select side_dishes from subscription_meals where id=$1',[meals[0]])).rows[0].side_dishes,[]);
 await db.exec('rollback to savepoint removal');
 await db.exec('savepoint incompatible_side');
 await db.query("update menu_items set dietary_type='NON_VEG' where id in(select c.menu_item_id from menu_day_choices c join menu_days d on d.id=c.menu_day_id where d.menu_id=$1 and c.choice_group='SIDE')",[target]);
 await assert.rejects(()=>rpc('sync_customer_menu',[target,'wrong-side-diet',true]),/dietary conflicts/);
 await db.exec('rollback to savepoint incompatible_side');
 // Recover the latest approved revision when an earlier faulty approval archived it.
 await db.query("update provider_menus set status='ARCHIVED',valid_until=current_date where id=$1",[target]);
 assert.equal((await rpc('admin_provider_change_detail',[sidesOnly.change_request_id])).publication.live,false);
 assert.equal((await rpc('admin_menu_sync',[sidesOnly.change_request_id,false])).requires_repair,true);
 assert.equal((await db.query('select status from provider_menus where id=$1',[target])).rows[0].status,'ARCHIVED','preview visibility is rolled back');
 const repaired=await rpc('admin_menu_sync',[sidesOnly.change_request_id,true]);assert.equal(repaired.menu_live,true);assert.equal(repaired.repaired,true);
 assert.equal((await rpc('admin_provider_change_detail',[sidesOnly.change_request_id])).publication.live,true);
 await db.exec('savepoint stale_request');await assert.rejects(()=>rpc('admin_menu_sync',[full.change_request_id,true]),/older request/);await db.exec('rollback to savepoint stale_request');
 // A request without saved menu entries must never archive the working menu.
 const incomplete=id();
 await db.query("insert into provider_change_requests(id,provider_id,request_type,requested_by,requested_payload) select $1,provider_id,request_type,requested_by,requested_payload from provider_change_requests where id=$2",[incomplete,sidesOnly.change_request_id]);
 await db.exec('savepoint incomplete_request');await assert.rejects(()=>rpc('admin_review_provider_business_update',[incomplete,'APPROVED',null,payload]),/no saved menu/);await db.exec('rollback to savepoint incomplete_request');
 assert.equal((await db.query('select status from provider_menus where id=$1',[target])).rows[0].status,'APPROVED');
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[v]);
 assert.equal((await rpc('provider_profile_hub')).weekly_menu.length,14,'provider app reads repaired published menu');
 await db.query("select set_config('request.jwt.claim.sub',$1,false)",[admin]);
 console.log('PASS: approved-menu repair, live provider read model, stale request rejection and incomplete approval rollback');
 const lostDay=(await db.query("select id from menu_days where menu_id=$1 and day_of_week=2 and meal_slot='LUNCH'",[target])).rows[0].id;
 const lostVeg=(await db.query("select id from menu_items where provider_id=$1 and name='Newest veg'",[p])).rows[0].id;
 const wrongMeat=(await db.query("select id from menu_items where provider_id=$1 and name='Newest meat'",[p])).rows[0].id;
 await db.query('delete from menu_day_choices where menu_day_id=$1 and menu_item_id=$2',[lostDay,lostVeg]);
 const photo=(await db.query("insert into provider_media(provider_id,menu_item_id,media_type,status,storage_bucket,storage_path,mime_type,size_bytes,uploaded_by,reviewed_by,reviewed_at,change_request_id,alt_text) values($1,$2,'MENU_ITEM','ARCHIVED','provider-media',$1::uuid||'/lost-veg.jpg','image/jpeg',10,$3,$3,now(),$4,'Newest veg') returning id",[p,wrongMeat,admin,sidesOnly.change_request_id])).rows[0].id;
 const recoveredPreview=await rpc('admin_menu_sync',[sidesOnly.change_request_id,false]);assert.equal(recoveredPreview.restored_alternatives,1);assert.equal(recoveredPreview.conflicts.length,0);
 assert.equal((await db.query('select count(*)::int n from menu_day_choices where menu_day_id=$1 and menu_item_id=$2',[lostDay,lostVeg])).rows[0].n,0,'preview does not persist reconstructed choices');
 assert.equal((await db.query('select menu_item_id from provider_media where id=$1',[photo])).rows[0].menu_item_id,wrongMeat);
 assert.equal((await rpc('admin_menu_sync',[sidesOnly.change_request_id,true])).restored_alternatives,1);
 assert.equal((await db.query('select menu_item_id from provider_media where id=$1',[photo])).rows[0].menu_item_id,lostVeg);
 assert.equal((await rpc('admin_menu_sync',[sidesOnly.change_request_id,true])).restored_alternatives,0);
 assert.equal((await db.query("select has_function_privilege('authenticated','reconcile_request_main_courses(uuid)','execute') allowed")).rows[0].allowed,false);
 console.log('PASS: every payload alternative retained, lost approved veg option and exact-label photo recovered, rollback-only preview and idempotent repair');
 assert.equal((await db.query('select side_dishes from subscription_meals where id=$1',[meals[0]])).rows[0].side_dishes[0].name,'Veg biriyani, raita');
 console.log('PASS: rice → pulao → biriyani, side-only previews/approvals, combined single inbox/push, snapshots, prices, removal and retry safety');
 console.log('PASS: real provider submission and full approval invokes atomic customer sync');
 await db.query('delete from menu_day_choices where menu_item_id=$1',[d]);assert.equal(await rpc('menu_replacement_item',[fresh,b,1,'LUNCH']),null);
 console.log('PASS: menu sync dry run, veg/nonveg mapping, future and paused choices, past/cancelled/preparing preservation, notification deduplication and private history');
 }finally{await db.exec('rollback');}
};
