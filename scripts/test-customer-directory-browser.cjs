// Optional headless layout check with synthetic customers only.
// Set PLAYWRIGHT_MODULE and CHROME_PATH when they are not on the default paths.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.join(__dirname,'..');
async function main(){
  const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||undefined});
  try {
    const page=await browser.newPage();
    await page.route('**/*',route=>route.abort()); // No external requests or real accounts.
    let html=fs.readFileSync(path.join(root,'admin/index.html'),'utf8');
    html=html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<link\b[^>]*>/gi,tag=>{
      const href=/href="([^"]+)"/.exec(tag)?.[1];
      return href&&/^[\w.-]+\.css$/.test(href)?`<style>${fs.readFileSync(path.join(root,'admin',href),'utf8')}</style>`:'';
    });
    await page.setContent(html);
    await page.evaluate(()=>{
      document.querySelector('#dashboard').classList.remove('hidden');
      document.querySelectorAll('.login-shell').forEach(el=>el.classList.add('hidden'));
      window.ZomealAPI={configured:true,accountAccess:async()=>true,accountCleanup:async()=>({jobs:[]}),customerDirectory:async()=>({
        total:3,page_size:25,as_of_date:'2026-09-20',summary:{registered:2,active:1,paused:1,no_plan:0,total_accounts:3,incomplete:1,inactive:0},
        customers:[{id:'70000000-0000-4000-8000-000000000001',full_name:'UI test customer A',phone:'+919000000001',pincode:'757043',city:'Rairangpur',balance_paise:125000,registered_at:'2026-09-19',is_registered:true,account_enabled:true,active_subscription:true,subscription_state:'ACTIVE',subscription:{provider_name:'UI test kitchen',package_name:'Monthly meals',package_kind:'LUNCH_AND_DINNER',duration_days:30,start_date:'2026-09-20',end_date:'2026-10-19'}},
        {id:'70000000-0000-4000-8000-000000000002',full_name:'UI test customer B',phone:'+919000000002',pincode:'751019',balance_paise:25000,registered_at:'2026-09-18',is_registered:true,account_enabled:true,paused_subscription:true,subscription_state:'PAUSED',subscription:{provider_name:'UI test kitchen',package_name:'Weekly trial',package_kind:'LUNCH_ONLY',duration_days:7,start_date:'2026-09-18',end_date:'2026-09-24',pause_reason:'INSUFFICIENT_WALLET'}},
        {id:'70000000-0000-4000-8000-000000000003',full_name:null,phone:null,pincode:null,balance_paise:0,registered_at:'2026-09-17',is_registered:false,account_enabled:true,is_test_account:true,subscription_state:'NONE',subscription:null}]
      })};
    });
    await page.addScriptTag({content:fs.readFileSync(path.join(root,'admin/accounts.js'),'utf8')});
    await page.locator('#accountsNav').click();
    await page.locator('.account-directory-table tbody tr').first().waitFor();
    const output=path.resolve(root,'../outputs/admin-directory-20260920');fs.mkdirSync(output,{recursive:true});
    for(const [name,width,height] of [['desktop',1440,1080],['mobile',390,844]]){
      await page.setViewportSize({width,height});
      if(width<760)await page.waitForFunction(()=>document.querySelector('.sidebar').getBoundingClientRect().right<=1);
      const layout=await page.evaluate(()=>({width:window.innerWidth,scroll:document.documentElement.scrollWidth,rows:document.querySelectorAll('.account-directory-table tbody tr').length,
        search:document.querySelector('#customerDirectoryQuery').getBoundingClientRect().width}));
      assert.equal(layout.rows,3);assert.ok(layout.search>100);assert.ok(layout.scroll<=layout.width+1,JSON.stringify(layout));
      await page.screenshot({path:path.join(output,`${name}.png`),fullPage:true});
    }
    console.log('PASS: desktop and mobile directory layouts, full-width search and contained table scrolling.');
  } finally {await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
