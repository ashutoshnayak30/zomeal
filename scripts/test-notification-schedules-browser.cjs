const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH});
 try{
 const page=await browser.newPage();await page.route('**/*',r=>r.abort());
 let html=fs.readFileSync('admin/index.html','utf8').replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<link\b[^>]*>/gi,t=>{const f=/href="([^"]+)"/.exec(t)?.[1];return f&&/^[\w.-]+\.css$/.test(f)?`<style>${fs.readFileSync('admin/'+f,'utf8')}</style>`:'';});
 await page.setContent(html);await page.evaluate(()=>{
 document.querySelector('#dashboard').classList.remove('hidden');document.querySelectorAll('.login-shell').forEach(e=>e.classList.add('hidden'));
 const section=document.createElement('section');section.id='notificationsView';document.querySelector('#overviewView').parentElement.append(section);
 document.querySelectorAll('.view').forEach(e=>e.classList.add('hidden'));
 window.ZomealAPI={campaignSchedules:async()=>({schedules:[],runs:[]}),campaignPreview:async()=>({count:0,users:[],kitchens:[]})};
 });
 await page.addScriptTag({content:fs.readFileSync('admin/notification-schedules.js','utf8')});
 const dir=path.resolve('../outputs/notification-schedules');fs.mkdirSync(dir,{recursive:true});
 for(const [name,width,height] of [['desktop',1440,1000],['mobile',390,844]]){
 await page.setViewportSize({width,height});if(width<700)await page.waitForFunction(()=>document.querySelector('.sidebar').getBoundingClientRect().right<=1);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true);
 await page.screenshot({path:path.join(dir,name+'.png'),fullPage:true});
 }
 console.log('PASS: desktop/mobile campaign layout without horizontal overflow');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
