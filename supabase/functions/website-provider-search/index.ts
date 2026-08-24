import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors={
  "Access-Control-Allow-Origin":"*",
  "Access-Control-Allow-Headers":"authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods":"POST, OPTIONS",
};
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{...cors,"Content-Type":"application/json"}});

Deno.serve(async(req)=>{
  if(req.method==="OPTIONS")return new Response("ok",{headers:cors});
  if(req.method!=="POST")return json({error:"Method not allowed"},405);
  try{
    const {pincode}=await req.json();
    if(!/^[1-9][0-9]{5}$/.test(String(pincode||"")))return json({error:"Enter a valid six-digit pincode"},400);
    const url=Deno.env.get("SUPABASE_URL")!;
    const serviceKey=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const client=createClient(url,serviceKey,{auth:{persistSession:false}});
    const {data,error}=await client.rpc("customer_marketplace",{target_pincode:String(pincode)});
    if(error)throw error;
    const sign=async(path?:string|null)=>{
      if(!path)return null;
      const clean=path.replace(/^provider-media\//,"").replace(/^\/+/,"");
      const {data:signed}=await client.storage.from("provider-media").createSignedUrl(clean,900);
      return signed?.signedUrl||null;
    };
    const providers=await Promise.all((data||[]).map(async(provider:any)=>({
      ...provider,
      primary_photo_url:await sign(provider.primary_photo_path),
      kitchen_photo_url:await sign(provider.kitchen_photo_path),
      meal_photo_url:await sign(provider.meal_photo_path),
      weekly_menu:await Promise.all((provider.weekly_menu||[]).map(async(day:any)=>({
        ...day,items:await Promise.all((day.items||[]).map(async(item:any)=>({...item,photo_url:await sign(item.photo_path)})))
      })))
    })));
    return json({pincode:String(pincode),count:providers.length,providers});
  }catch(error){
    console.error(error);
    return json({error:"We could not check this area right now. Please try again."},500);
  }
});
