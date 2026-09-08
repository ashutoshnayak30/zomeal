import { errorMessage, jsonResponse } from "../_shared/http.ts";
import { serviceClient } from "../_shared/supabase.ts";
import { hmacHex, safeHexEqual } from "../_shared/razorpay-signature.mjs";

Deno.serve(async request=>{
  if(request.method!=="POST")return jsonResponse({error:"Method not allowed"},405);
  try{
    const secret=Deno.env.get("RAZORPAY_WEBHOOK_SECRET")??"";if(!secret)throw new Error("Razorpay webhook secret is not configured");
    const raw=await request.text();const supplied=request.headers.get("x-razorpay-signature")??"";
    if(!safeHexEqual(await hmacHex(secret,raw),supplied))return jsonResponse({error:"Invalid webhook signature"},401);
    const payload=JSON.parse(raw);const eventType=String(payload.event??"");
    const payment=payload?.payload?.payment?.entity;const gatewayOrderId=String(payment?.order_id??"");
    const gatewayPaymentId=String(payment?.id??"");if(!gatewayOrderId||!gatewayPaymentId)return jsonResponse({received:true,ignored:true});
    const db=serviceClient();const eventId=request.headers.get("x-razorpay-event-id")??`${eventType}:${gatewayPaymentId}:${payment?.status??""}`;
    const {data:order,error:orderError}=await db.from("payment_orders").select("*").eq("gateway_order_id",gatewayOrderId).maybeSingle();
    if(orderError)throw orderError;
    let {data:eventRow,error:eventError}=await db.from("payment_gateway_events").upsert({gateway_event_id:eventId,payment_order_id:order?.id??null,event_type:eventType,
      signature_valid:true,payload,processing_status:order?"RECEIVED":"IGNORED"},{onConflict:"gateway_event_id",ignoreDuplicates:true}).select("id,processing_status").maybeSingle();
    if(eventError)throw eventError;
    if(!eventRow){const existing=await db.from("payment_gateway_events").select("id,processing_status").eq("gateway_event_id",eventId).maybeSingle();if(existing.error)throw existing.error;
      if(existing.data?.processing_status==="PROCESSED"||existing.data?.processing_status==="IGNORED")return jsonResponse({received:true,duplicate:true});eventRow=existing.data;}
    if(!eventRow)throw new Error("Could not persist webhook event");
    if(!order){return jsonResponse({received:true,ignored:true});}
    if(Number(payment.amount)!==Number(order.amount_paise)||String(payment.currency)!==order.currency)throw new Error("Webhook payment does not match the Zomeal order");
    if(eventType==="payment.captured"){
      await db.from("payment_orders").update({gateway_payment_id:gatewayPaymentId,status:"CAPTURED",verified_at:new Date().toISOString(),captured_at:payment.captured_at?new Date(Number(payment.captured_at)*1000).toISOString():new Date().toISOString()}).eq("id",order.id);
      if(order.customer_id&&!order.test_mode){const {error}=await db.rpc("apply_captured_payment",{target_payment_order:order.id,target_customer:order.customer_id});if(error)throw error;}
    }else if(eventType==="payment.failed"){
      await db.from("payment_orders").update({gateway_payment_id:gatewayPaymentId,status:"FAILED",failure_code:payment?.error_code??"PAYMENT_FAILED",failure_description:payment?.error_description??"Payment failed"}).eq("id",order.id).neq("status","CAPTURED");
    }
    await db.from("payment_gateway_events").update({processing_status:"PROCESSED",processed_at:new Date().toISOString()}).eq("id",eventRow.id);
    return jsonResponse({received:true});
  }catch(error){console.error("razorpay-webhook",error);return jsonResponse({error:errorMessage(error)},500);}
});
