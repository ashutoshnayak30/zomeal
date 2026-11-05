const assert=require('node:assert/strict'),fs=require('node:fs'),{JSDOM}=require('jsdom');
const dom=new JSDOM('<div id="dashboard"><section id="notificationsView" class="hidden"></section></div>',{runScripts:'outside-only'}),w=dom.window;
const saved=[];w.confirm=()=>true;
w.ZomealAPI={mealReminders:async payload=>{
 if(payload){saved.push(payload);return {};}
 return {items:['LUNCH','DINNER'].map(slot=>({slot,enabled:true,send_time:slot==='LUNCH'?'10:30:00':'18:30:00',title:'Test <img src=x>',message:'{main_course}',low_title:'Wallet',low_message:'Skipped meals stay skipped.'}))};
}};
w.eval(fs.readFileSync('admin/meal-reminders.js','utf8'));
(async()=>{
 const panel=w.document.querySelector('.meal-reminder-manager');
 await panel.querySelector('[data-refresh]').onclick();
 const inputs=panel.querySelectorAll('[name=send_time]');
 assert.equal(inputs[0].value,'10:30');assert.equal(inputs[1].value,'18:30');
 assert.equal(panel.querySelector('img'),null);
 inputs[0].value='11:15';inputs[1].value='19:00';
 const form=panel.querySelector('form');
 await form.onsubmit({preventDefault(){},target:form});
 assert.equal(saved.length,1);assert.equal(saved[0][0].send_time,'11:15');assert.equal(saved[0][1].send_time,'19:00');
 inputs[0].value='22:00';await form.onsubmit({preventDefault(){},target:form});assert.equal(saved.length,1);
 console.log('PASS: reminder controls load saved times, submit edited IST times, reject out-of-range input and escape templates');
 w.close();
})().catch(e=>{console.error(e);w.close();process.exitCode=1;});
