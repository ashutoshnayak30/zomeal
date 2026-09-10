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
    const cleanPath=(path?:string|null)=>path?.replace(/^provider-media\//,"").replace(/^\/+/,"")||"";
    const paths=[...new Set((data||[]).flatMap((provider:any)=>[
      provider.primary_photo_path,provider.kitchen_photo_path,provider.meal_photo_path,
      ...(provider.weekly_menu||[]).flatMap((day:any)=>(day.items||[]).map((item:any)=>item.photo_path)),
    ]).map(cleanPath).filter(Boolean))];
    // One batched Storage request replaces a separate network round trip for
    // every provider and dish photograph in the catalogue.
    const signedByPath=new Map<string,string>();
    if(paths.length){
      const {data:signed,error:signError}=await client.storage.from("provider-media").createSignedUrls(paths,3600);
      if(signError)console.error("Provider media signing failed",signError);
      (signed||[]).forEach((item:any,index:number)=>{
        if(item?.signedUrl)signedByPath.set(paths[index],item.signedUrl);
      });
    }
    const signedUrl=(path?:string|null)=>signedByPath.get(cleanPath(path))||null;
    const providers=(data||[]).map((provider:any)=>({
      ...provider,
      primary_photo_url:signedUrl(provider.primary_photo_path),
      kitchen_photo_url:signedUrl(provider.kitchen_photo_path),
      meal_photo_url:signedUrl(provider.meal_photo_path),
      weekly_menu:(provider.weekly_menu||[]).map((day:any)=>({
        ...day,items:(day.items||[]).map((item:any)=>({...item,photo_url:signedUrl(item.photo_path)}))
      }))
    }));
    return json({pincode:String(pincode),count:providers.length,providers});
  }catch(error){
    console.error(error);
    return json({error:"We could not check this area right now. Please try again."},500);
  }
});
