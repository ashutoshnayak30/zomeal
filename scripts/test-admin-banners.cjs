const {JSDOM}=require('jsdom');const fs=require('fs');const path=require('path');const assert=require('assert/strict');
const root=path.join(__dirname,'..');
const dom=new JSDOM('<div id="dashboard"><div class="sidebar"></div><nav id="nav"><button id="homeBannersNav"></button></nav><h1 id="pageTitle"></h1><span id="crumb"></span><main><section id="overviewView" class="view"></section></main></div>',{runScripts:'outside-only',url:'https://admin.zomeal.in/'});
const w=dom.window;let saved=null,fail=false,uploaded=0,records=[];
w.confirm=()=>true;w.HTMLElement.prototype.scrollIntoView=()=>{};
w.URL.createObjectURL=()=> 'blob:preview';w.URL.revokeObjectURL=()=>{};
w.createImageBitmap=async()=>({width:1200,height:450,close(){}});
w.HTMLCanvasElement.prototype.getContext=()=>({drawImage(){}});
w.HTMLCanvasElement.prototype.toBlob=function(callback){callback(new w.Blob(['image'],{type:'image/webp'}))};
w.ZomealAPI={configured:true,accountAccess:async()=>true,homeBanners:async()=>records,homeBannerUrl:p=>p?'https://example.test/'+p:'',uploadHomeBanner:async()=>{uploaded++;return 'images/test.webp'},saveHomeBanner:async(p,v)=>{if(fail)throw Error('Temporary save error');saved={...p,id:p.id||'test-id',updated_at:'2026-09-11T00:00:00Z'};records=[saved];return saved},deleteHomeBanner:async()=>{records=[]}};
w.eval(fs.readFileSync(path.join(root,'admin/banners.js'),'utf8'));
const tick=()=>new Promise(r=>setTimeout(r,10));
async function main(){
 w.document.querySelector('#homeBannersNav').click();await tick();
 const form=w.document.querySelector('#bannerForm'),f=form.elements;
 assert.equal(w.document.querySelector('#homeBannersView').classList.contains('hidden'),false);
 f.title.value='Monthly meals';f.destination.value='HTTPS';f.destination.dispatchEvent(new w.Event('change'));assert.equal(f.destination_value.required,true);
 f.destination_value.value='https://zomeal.in/';f.enabled.checked=true;
 Object.defineProperty(f.image,'files',{value:[new w.File(['image'],'banner.png',{type:'image/png'})],configurable:true});f.image.dispatchEvent(new w.Event('change'));await tick();
 assert.equal(w.document.querySelector('#bannerPreview').hidden,false);
 form.dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();assert.equal(uploaded,1);assert.equal(saved.enabled,true);assert.equal(saved.destination,'HTTPS');assert.equal(saved.image_path,'images/test.webp');
 w.document.querySelector('[data-edit]').click();f.title.value='<img src=x onerror=alert(1)>';fail=true;form.dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();assert.equal(f.title.value,'<img src=x onerror=alert(1)>');assert.equal(w.document.querySelector('#bannerSave').disabled,false);
 fail=false;form.dispatchEvent(new w.Event('submit',{cancelable:true}));await tick();assert.equal(w.document.querySelectorAll('#bannerList img').length,1);assert.equal(uploaded,1);
 w.document.querySelector('[data-delete]').click();await tick();assert.equal(records.length,0);
 console.log('PASS: navigation, preview, image upload/save, HTTPS destination, edit retries, escaping and deletion.');dom.window.close();
}
main().catch(e=>{console.error(e);process.exit(1)});
