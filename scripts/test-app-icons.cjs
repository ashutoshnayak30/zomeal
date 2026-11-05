const fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path');
function svg(file){
 const xml=fs.readFileSync(file,'utf8');
 if(xml.includes('@drawable/zomeal_tiffin_logo')) {
  const data=fs.readFileSync('app/src/main/res/drawable-nodpi/zomeal_tiffin_logo.png').toString('base64');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="#F4F9EF"/><image href="data:image/png;base64,${data}" width="108" height="108"/></svg>`;
 }
 return xml.replace(/<vector[^>]*>/,'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">').replace('</vector>','</svg>')
 .replace(/<group android:scaleX="([^"]+)" android:scaleY="([^"]+)" android:translateX="([^"]+)" android:translateY="([^"]+)">/,'<g transform="translate($3 $4) scale($1 $2)">').replace('</group>','</g>')
 .replaceAll('android:fillColor','fill').replaceAll('android:pathData','d');
}
(async()=>{
 const customerUi=fs.readFileSync('app/src/main/java/com/zomeal/app/MainActivity.kt','utf8');
 assert.ok(!customerUi.includes('R.drawable.zomeal_logo'));
 assert.equal((customerUi.match(/R\.drawable\.zomeal_tiffin_logo/g)||[]).length,4);
 let cards='';
 for(const [module,label] of [['app','Zomeal'],['provider-app','Zomeal Partner']]){
  const base=module+'/src/main/res/';const manifest=fs.readFileSync(module+'/src/main/AndroidManifest.xml','utf8');
  assert.ok(manifest.includes(`android:label="${label}"`));
  assert.ok(manifest.includes('android:icon="@mipmap/ic_launcher"'));
  assert.ok(manifest.includes('default_notification_icon'));
  for(const version of ['v26','v33'])assert.ok(fs.readFileSync(base+`mipmap-anydpi-${version}/ic_launcher.xml`,'utf8').includes('ic_launcher_foreground'));
  assert.ok(fs.readFileSync(base+'mipmap-anydpi-v33/ic_launcher.xml','utf8').includes('<monochrome'));
  const notification=svg(base+'drawable/ic_stat_zomeal.xml');
  assert.ok(!/fill="(?!#FFFFFF)/.test(notification));
  cards+=`<section><h2>${label}</h2><div class="row"><div class="large">${svg(base+'mipmap-anydpi/ic_launcher.xml')}</div><div class="small">${svg(base+'mipmap-anydpi/ic_launcher.xml')}</div></div><p>Notification bar (24px)</p><div class="status">${notification}<span>10:00 PM</span></div></section>`;
 }
 const {chromium}=require(process.env.PLAYWRIGHT_MODULE);
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH,headless:true});
 try{const page=await browser.newPage({viewport:{width:820,height:490},deviceScaleFactor:1});
 await page.setContent(`<html><style>body{margin:0;padding:32px;background:#f3f6f2;font:16px Arial;color:#123b2b;display:flex;gap:24px}section{background:white;border-radius:24px;padding:24px;width:320px}h2{font-size:22px;margin:0 0 24px}.row{display:flex;align-items:center;gap:28px}.large{width:156px;height:156px;border-radius:35px;overflow:hidden}.small{width:48px;height:48px;border-radius:50%;overflow:hidden}svg{width:100%;height:100%}.status{background:#123b2b;color:white;padding:12px;border-radius:12px;display:flex;gap:18px;align-items:center}.status svg{width:24px;height:24px}p{margin-top:28px}</style>${cards}</html>`);
 const out=path.resolve('../outputs/app-icons-20260923');fs.mkdirSync(out,{recursive:true});await page.screenshot({path:path.join(out,'icons-preview.png')});
 console.log('PASS: launcher variants, themed/monochrome icons and FCM defaults. Preview: '+path.join(out,'icons-preview.png'));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
