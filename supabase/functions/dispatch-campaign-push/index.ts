import { serviceClient } from "../_shared/supabase.ts";

// This endpoint accepts only the database-generated dispatcher secret, never
// recipient IDs or notification text from callers. All payloads come from admin-approved scheduled campaigns.
const encode = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
const encodedJson = (value: unknown) => encode(new TextEncoder().encode(JSON.stringify(value)));
async function firebaseAccess() {
  const account = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") || "{}");
  if (!account.private_key || !account.client_email || !account.project_id) throw new Error("Firebase configuration missing");
  const now = Math.floor(Date.now() / 1000);
  const unsigned = encodedJson({alg:"RS256",typ:"JWT"})+"."+encodedJson({iss:account.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3500});
  const bytes = Uint8Array.from(atob(account.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g,"")), c=>c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8",bytes,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["sign"]);
  const signature = encode(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5",key,new TextEncoder().encode(unsigned))));
  const response = await fetch("https://oauth2.googleapis.com/token",{method:"POST",signal:AbortSignal.timeout(10000),headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion:unsigned+"."+signature})});
  const result = await response.json();
  if (!response.ok || !result.access_token) throw new Error("Firebase authorization failed");
  return {project:account.project_id,token:result.access_token};
}

Deno.serve(async request => {
  if(request.method!=="POST")return new Response("Method not allowed",{status:405});
  const secret=request.headers.get("x-meal-dispatch-secret");
  if(!secret || secret.length>200)return new Response("Unauthorized",{status:401});
  try {
    const db=serviceClient();
    const auth=await db.rpc("authorize_meal_push_dispatch",{dispatch_secret:secret});
    if(auth.error || auth.data!==true)return new Response("Unauthorized",{status:401});
    const firebase=await firebaseAccess();
    const batch=await db.rpc("claim_campaign_push_batch");
    if(batch.error)throw new Error("Could not claim push batch");
    let sent=0,failed=0;
    const rows=batch.data || [];
    // Bounded batches/concurrency avoid Edge runtime timeouts; the lease permits retries.
    for(let offset=0;offset<rows.length;offset+=10)await Promise.all(rows.slice(offset,offset+10).map(async (row: {id:string;lease_id:string;notification_id:string;token:string|null;title:string;body:string;destination:string;app_kind:string})=>{
      let delivered=!row.token,invalid=false,code:string|null=row.token?null:"DEVICE_DISABLED";
      try {
        if(row.token){
          const response=await fetch(`https://fcm.googleapis.com/v1/projects/${firebase.project}/messages:send`,{method:"POST",signal:AbortSignal.timeout(10000),headers:{Authorization:`Bearer ${firebase.token}`,"Content-Type":"application/json"},body:JSON.stringify({message:{token:row.token,notification:{title:row.title,body:row.body},data:{title:row.title,body:row.body,destination:row.destination,notification_id:row.notification_id},android:{priority:"high",ttl:"86400s",notification:{channel_id:row.app_kind==="PROVIDER"?"zomeal_provider_updates":"zomeal_updates",tag:row.notification_id}}}})});
          delivered=response.ok;
          if(!delivered){const result=await response.json();code=String(result.error?.status || response.status);invalid=(result.error?.details || []).some((d:{errorCode?:string})=>d.errorCode==="UNREGISTERED");}
        }
      } catch {code="NETWORK_OR_TIMEOUT";}
      const finished=await db.rpc("finish_campaign_push",{target_id:row.id,target_lease:row.lease_id,delivered,invalid_token:invalid,error_code:code});
      if(delivered&&!finished.error)sent++;else failed++;
    }));
    return Response.json({processed:rows.length,sent,failed});
  }catch {return Response.json({error:"Campaign push dispatch failed; pending deliveries will retry"},{status:500});}
});
