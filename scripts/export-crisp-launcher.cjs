const fs = require('node:fs');
const {chromium} = require('C:/Users/HP/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const xml = fs.readFileSync('app/src/main/res/drawable/ic_launcher_foreground.xml', 'utf8');
  const path = [...xml.matchAll(/android:pathData="([^"]+)"/g)].map(m=>m[1]).join('" /><path fill="white" fill-rule="evenodd" d="');
  const browser = await chromium.launch({headless:true, executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  try {
    const page = await browser.newPage({deviceScaleFactor:1});
    for (const [density,size] of [['mdpi',48],['hdpi',72],['xhdpi',96],['xxhdpi',144],['xxxhdpi',192],['preview',512]]) {
      await page.setViewportSize({width:size,height:size});
      await page.setContent('<style>html,body{margin:0}svg{display:block;width:100%;height:100%}</style><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="#088341"/><g transform="translate(54 54) scale(1.12) translate(-54 -54)"><path fill="white" fill-rule="evenodd" d="'+path+'"/></g></svg>');
      const dir = density === 'preview' ? '../outputs/crisp-launcher' : 'app/src/main/res/mipmap-'+density;
      fs.mkdirSync(dir,{recursive:true});
      await page.screenshot({path:dir+'/ic_zomeal_app.png'});
    }
  } finally { await browser.close(); }
})();
