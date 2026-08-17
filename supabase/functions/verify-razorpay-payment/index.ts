import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";

type VerifyBody = {
  payment_order_id?: string;
  razorpay_order_id?: string;
  razorpay_payment_id?: string;
  razorpay_signature?: string;
};

function bytesToHex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes)).map((value) => value.toString(16).padStart(2, "0")).join("");
}

function safeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let result = 0;
  for (let index = 0; index < left.length; index++) result |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return result === 0;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  try {
    const secret = Deno.env.get("RAZORPAY_KEY_SECRET") ?? "";
    const keyId = Deno.env.get("RAZORPAY_KEY_ID") ?? "";
    if (!secret || !keyId) throw new Error("Razorpay secrets are not configured");
    const body = (await request.json()) as VerifyBody;
    if (!body.payment_order_id || !body.razorpay_order_id || !body.razorpay_payment_id || !body.razorpay_signature) {
      return jsonResponse({ error: "Complete Razorpay payment evidence is required" }, 400);
    }
    const db = serviceClient();
    const user = await optionalUser(request);
    const { data: order, error: orderError } = await db.from("payment_orders").select("*").eq("id", body.payment_order_id).maybeSingle();
    if (orderError) throw orderError;
    if (!order) return jsonResponse({ error: "Payment order was not found" }, 404);
    if (order.customer_id && order.customer_id !== user?.id) return jsonResponse({ error: "This payment belongs to another customer" }, 403);
    if (order.gateway_order_id !== body.razorpay_order_id) return jsonResponse({ error: "Razorpay order mismatch" }, 400);

    const signingKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const signature = bytesToHex(await crypto.subtle.sign("HMAC", signingKey, new TextEncoder().encode(`${body.razorpay_order_id}|${body.razorpay_payment_id}`)));
    if (!safeEqual(signature, body.razorpay_signature.toLowerCase())) {
      await db.from("payment_orders").update({ failure_code: "SIGNATURE_MISMATCH", failure_description: "Checkout signature verification failed" }).eq("id", order.id);
      return jsonResponse({ error: "Payment verification failed" }, 400);
    }

    const gatewayResponse = await fetch(`https://api.razorpay.com/v1/payments/${encodeURIComponent(body.razorpay_payment_id)}`, {
      headers: { Authorization: `Basic ${btoa(`${keyId}:${secret}`)}` },
    });
    const payment = await gatewayResponse.json();
    if (!gatewayResponse.ok) return jsonResponse({ error: payment?.error?.description ?? "Could not confirm payment with Razorpay" }, 502);
    if (payment.order_id !== order.gateway_order_id || Number(payment.amount) !== Number(order.amount_paise) || payment.currency !== order.currency) {
      return jsonResponse({ error: "Razorpay payment details do not match the Zomeal order" }, 400);
    }
    if (!["authorized", "captured"].includes(payment.status)) return jsonResponse({ error: `Payment status is ${payment.status}` }, 409);

    const captured = payment.status === "captured";
    const update = {
      gateway_payment_id: body.razorpay_payment_id,
      status: captured ? "CAPTURED" : "AUTHORIZED",
      verified_at: new Date().toISOString(),
      captured_at: captured ? new Date().toISOString() : null,
    };
    const { error: updateError } = await db.from("payment_orders").update(update).eq("id", order.id);
    if (updateError) throw updateError;

    let subscriptionId: string | null = null;
    if (captured && user) {
      const { data: activation, error: activationError } = await db.rpc("finalize_captured_payment", {
        target_payment_order: order.id, target_customer: user.id,
      });
      if (activationError) throw activationError;
      subscriptionId = activation?.subscription_id ?? null;
    }

    // Anonymous Test Mode orders verify the gateway flow without creating business data.
    return jsonResponse({
      verified: true, captured, status: update.status, payment_order_id: order.id,
      razorpay_payment_id: body.razorpay_payment_id,
      subscription_activated: subscriptionId !== null, subscription_id: subscriptionId,
      activation_note: subscriptionId ? "Payment verified and subscription activated." : user ? "Payment is authorized and will activate after capture." : "Anonymous Test Mode payment verified; no subscription was created.",
    });
  } catch (error) {
    console.error("verify-razorpay-payment", error);
    return jsonResponse({ error: errorMessage(error) }, 500);
  }
});
