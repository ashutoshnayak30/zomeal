export async function hmacHex(secret, content) {
  const key=await crypto.subtle.importKey("raw",new TextEncoder().encode(secret),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
  const bytes=new Uint8Array(await crypto.subtle.sign("HMAC",key,new TextEncoder().encode(content)));
  return Array.from(bytes,value=>value.toString(16).padStart(2,"0")).join("");
}
export function safeHexEqual(left,right){
  const a=String(left).toLowerCase(),b=String(right).toLowerCase(); if(a.length!==b.length)return false;
  let result=0;for(let i=0;i<a.length;i++)result|=a.charCodeAt(i)^b.charCodeAt(i);return result===0;
}
