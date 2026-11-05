// Deterministic conversion of the supplied white logo to native vector paths.
// No raster upscaling or AI redraw. Run from the repository root.
const fs=require('node:fs'),zlib=require('node:zlib'),assert=require('node:assert/strict');
const source=process.argv[2];if(!source)throw Error('Pass the original white-on-green PNG');
const png=fs.readFileSync(source);assert.equal(png.subarray(1,4).toString(),'PNG');
let width,height,type,parts=[];
for(let offset=8;offset<png.length;){const n=png.readUInt32BE(offset),tag=png.toString('ascii',offset+4,offset+8),data=png.subarray(offset+8,offset+8+n);if(tag==='IHDR'){width=data.readUInt32BE(0);height=data.readUInt32BE(4);assert.equal(data[8],8);type=data[9];assert.equal(data[12],0);}if(tag==='IDAT')parts.push(data);offset+=n+12;}
assert.ok(type===2||type===6);const channels=type===2?3:4,stride=width*channels,raw=zlib.inflateSync(Buffer.concat(parts)),pixels=Buffer.alloc(height*stride);
const paeth=(a,b,c)=>{const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;};
for(let y=0;y<height;y++){const filter=raw[y*(stride+1)];for(let x=0;x<stride;x++){const a=x>=channels?pixels[y*stride+x-channels]:0,b=y?pixels[(y-1)*stride+x]:0,c=y&&x>=channels?pixels[(y-1)*stride+x-channels]:0;const prediction=[0,a,b,Math.floor((a+b)/2),paeth(a,b,c)][filter];assert.notEqual(prediction,undefined);pixels[y*stride+x]=(raw[y*(stride+1)+1+x]+prediction)&255;}}
const mask=new Uint8Array(width*height);let minX=width,minY=height,maxX=0,maxY=0;
for(let y=0;y<height;y++)for(let x=0;x<width;x++){const p=(y*width+x)*channels;if(Math.min(pixels[p],pixels[p+1],pixels[p+2])>215&&(channels===3||pixels[p+3]>200)){mask[y*width+x]=1;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}}
const on=(x,y)=>x>=0&&x<width&&y>=0&&y<height&&mask[y*width+x];
const edges=new Map(),key=(x,y)=>y*(width+1)+x;
function edge(x,y,u,v){const a=key(x,y);if(!edges.has(a))edges.set(a,[]);edges.get(a).push(key(u,v));}
for(let y=0;y<height;y++)for(let x=0;x<width;x++)if(on(x,y)){
 if(!on(x,y-1))edge(x,y,x+1,y);if(!on(x+1,y))edge(x+1,y,x+1,y+1);if(!on(x,y+1))edge(x+1,y+1,x,y+1);if(!on(x-1,y))edge(x,y+1,x,y);
}
function simplify(points,tolerance){if(points.length<3)return points;const a=points[0],b=points.at(-1),dx=b[0]-a[0],dy=b[1]-a[1],den=dx*dx+dy*dy;let best=0,index=0;for(let i=1;i<points.length-1;i++){const p=points[i],t=den?Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den)):0,d=(p[0]-a[0]-t*dx)**2+(p[1]-a[1]-t*dy)**2;if(d>best){best=d;index=i;}}return best>tolerance*tolerance?[...simplify(points.slice(0,index+1),tolerance).slice(0,-1),...simplify(points.slice(index),tolerance)]:[a,b];}
const contours=[];
while(edges.size){const start=edges.keys().next().value;let current=start,points=[];do{points.push([current%(width+1),Math.floor(current/(width+1))]);const next=edges.get(current);if(!next)throw Error('Open contour');const end=next.pop();if(!next.length)edges.delete(current);current=end;}while(current!==start);points.push(points[0]);let area=0;for(let i=1;i<points.length;i++)area+=points[i-1][0]*points[i][1]-points[i][0]*points[i-1][1];if(Math.abs(area)>16){const mid=Math.floor(points.length/2);contours.push([...simplify(points.slice(0,mid+1),1.8).slice(0,-1),...simplify(points.slice(mid),1.8)]);}}
assert.ok(contours.length>=4,'Must preserve internal tiffin/leaf negative spaces');
const size=Math.max(maxX-minX+1,maxY-minY+1),scale=62/size,cx=(minX+maxX+1)/2,cy=(minY+maxY+1)/2;
const path=contours.map(points=>points.map((p,i)=>(i?'L':'M')+(54+(p[0]-cx)*scale).toFixed(3)+','+(54+(p[1]-cy)*scale).toFixed(3)).join(' ')+' Z').join(' ');
const vector=(dp)=>`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${dp}dp" android:height="${dp}dp" android:viewportWidth="108" android:viewportHeight="108"><path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="${path}" /></vector>\n`;
assert.ok(path.length<30000,'Android resource string length must stay below its UTF-8 limit');
fs.writeFileSync('app/src/main/res/drawable/ic_launcher_foreground.xml',vector(108));
fs.writeFileSync('app/src/main/res/drawable/ic_launcher_monochrome.xml',vector(108));
fs.writeFileSync('app/src/main/res/drawable/ic_launcher_legacy_foreground.xml',vector(108).replace('<path','<group android:pivotX="54" android:pivotY="54" android:scaleX="1.25" android:scaleY="1.25"><path').replace('</vector>','</group></vector>'));
fs.mkdirSync('../outputs/launcher-vector',{recursive:true});
fs.writeFileSync('../outputs/launcher-vector/logo.svg',`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><defs><linearGradient id="green" x2="0" y2="1"><stop stop-color="#197438"/><stop offset="1" stop-color="#75AE20"/></linearGradient></defs><rect width="108" height="108" fill="url(#green)"/><path fill="white" fill-rule="evenodd" d="${path}"/></svg>`);
console.log(JSON.stringify({sourceSize:[width,height],contours:contours.length,pathBytes:path.length,foreground:'native vector',safeBounds:[23,23,85,85]}));
