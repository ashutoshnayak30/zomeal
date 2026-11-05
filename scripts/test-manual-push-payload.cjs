const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('supabase/functions/send-fcm-notification/index.ts','utf8');
const expression=source.split(/\r?\n/).find(l=>l.includes('body: JSON.stringify({ message:')).trim().replace(/^body: JSON.stringify\(/,'').replace(/\),$/,'');
for(const appKind of ['CUSTOMER','PROVIDER']){
 const {message}=vm.runInNewContext('('+expression+')',{appKind,device:{token:'test'},title:'Test title',messageBody:'Test message',destination:'notifications'});
 assert.equal(message.data.title,'Test title');assert.equal(message.data.body,'Test message');
 if(appKind==='CUSTOMER'){assert.equal(message.notification,undefined);assert.equal(message.android.notification,undefined);}
 else {assert.equal(message.notification.title,'Test title');assert.equal(message.android.notification.channel_id,'zomeal_provider_updates');}
}
const kotlin=fs.readFileSync('app/src/main/java/com/zomeal/app/ZomealMessagingService.kt','utf8');
assert.ok(kotlin.includes('.setSmallIcon(R.drawable.ic_zomeal_status)'));assert.ok(!kotlin.includes('.setLargeIcon('));
const manifest=fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
assert.equal((manifest.match(/android:icon="@mipmap\/ic_zomeal_app"/g)||[]).length,2);
assert.ok(kotlin.includes('message.data["title"]?.trim()?.takeIf'));assert.ok(kotlin.includes('message.data["body"]?.trim()?.takeIf'));
console.log('PASS: manual customer payload has no automatic-rendering fields, title/body preserved, provider unchanged, local icon and nonblank handling wired');
