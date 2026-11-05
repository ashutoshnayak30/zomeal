// Rasterize existing native vector geometry at exact Android density sizes.
const fs=require('node:fs');const {chromium}=require('C:/Users/HP/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async()=>{const xml=fs.readFileSync('app/src/main/res/drawable/ic_launcher_foreground.xml','utf8');const path=/android:pathData="([^"]+)"/.exec(xml)[1];
const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});try{const page=await browser.newPage({deviceScaleFactor:1});
for(const [density,scale] of [['mdpi',1],['hdpi',1.5],['xhdpi',2],['xxhdpi',3],['xxxhdpi',4]])for(const status of [false,true]){
 const size=(status?24:48)*scale,dir=`app/src/main/res/${status?'drawable':'mipmap'}-${density}`;fs.mkdirSync(dir,{recursive:true});
 await page.setViewportSize({width:size,height:size});await page.setContent(`<style>html,body{margin:0;width:100%;height:100%;background:transparent}svg{display:block;width:100%;height:100%}</style><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">${status?'':'<defs><linearGradient id="g" x2="0" y2="1"><stop stop-color="#197438"/><stop offset="1" stop-color="#75AE20"/></linearGradient></defs><rect width="108" height="108" fill="url(#g)"/>'}<g transform="translate(54 54) scale(${status?1.45:1.25}) translate(-54 -54)"><path fill="white" fill-rule="evenodd" d="${path}"/></g></svg>`);
 await page.screenshot({path:`${dir}/${status?'ic_zomeal_status':'ic_zomeal_app'}.png`,omitBackground:true});
}console.log('Exported 5 application-icon densities and 5 transparent status-icon densities from native vector.');
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
