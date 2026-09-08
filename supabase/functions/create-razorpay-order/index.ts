import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";
import { advancePayment } from "../_shared/advance-payment.mjs";

type CreateOrderBody = {
  package_id?: string;
  delivery_address?: Record<string, unknown>;
  weekly_menu?: Record<string, unknown>;
  start_date?: string;
  first_meal?: "LUNCH" | "DINNER";
  amount_paise?: number;
  quote_only?: boolean;
  quoted_total_paise?: number;
  subscription_id?: string;
  purpose?: "WALLET_RECHARGE";
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);

  try {
    const keyId = Deno.env.get("RAZORPAY_KEY_ID") ?? "";
    const keySecret = Deno.env.get("RAZORPAY_KEY_SECRET") ?? "";
    if (!keyId || !keySecret) throw new Error("Razorpay secrets are not configured");
    const testMode = keyId.startsWith("rzp_test_");
    const user = await optionalUser(request);
    if (!user && !testMode) return jsonResponse({ error: "Authentication is required for live payments" }, 401);

    const body = (await request.json()) as CreateOrderBody;
    if (!user) return jsonResponse({ error: "Authentication is required for payments" }, 401);
    const db = serviceClient();

    if (body.purpose === "WALLET_RECHARGE") {
      if (body.quote_only === true) return jsonResponse({ minimum_paise: 500, maximum_paise: 1000000, purpose: "WALLET_RECHARGE" });
      const requested = Number(body.amount_paise);
      if (!Number.isSafeInteger(requested) || requested < 500 || requested > 1000000)
        return jsonResponse({ error: "Add between ₹5 and ₹10,000 to your wallet." }, 400);
      await db.from("payment_orders").update({ status: "CANCELLED", failure_code: "GATEWAY_MODE_CHANGED",
        failure_description: "Checkout replaced after Razorpay Test/Live mode changed" })
        .eq("customer_id", user.id).in("status", ["CREATING", "CREATED", "AUTHORIZED"])
        .eq("checkout_payload->>purpose", "WALLET_RECHARGE").neq("test_mode", testMode);
      const { data: openOrder, error: openError } = await db.from("payment_orders")
        .select("id,gateway_order_id,amount_paise,receipt,test_mode").eq("customer_id", user.id)
        .in("status", ["CREATING", "CREATED", "AUTHORIZED"]).eq("checkout_payload->>purpose", "WALLET_RECHARGE")
        .order("created_at", { ascending: false }).limit(1).maybeSingle();
      if (openError) throw openError;
      if (openOrder) {
        if (Number(openOrder.amount_paise) !== requested) return jsonResponse({ error: "An earlier wallet recharge is still open. Complete it before choosing another amount." }, 409);
        if (!openOrder.gateway_order_id) return jsonResponse({ error: "Your recharge is being prepared. Try again in a moment." }, 409);
        return jsonResponse({ payment_order_id: openOrder.id, razorpay_order_id: openOrder.gateway_order_id, key_id: keyId,
          amount_paise: Number(openOrder.amount_paise), currency: "INR", receipt: openOrder.receipt, test_mode: openOrder.test_mode,
          purpose: "WALLET_RECHARGE", reused: true });
      }
      const localId = crypto.randomUUID();
      const receipt = `ZW-${localId.replaceAll("-", "").slice(0, 20)}`;
      const { error: insertError } = await db.from("payment_orders").insert({ id: localId, customer_id: user.id,
        provider_id: null, package_id: null, receipt, package_amount_paise: requested, amount_paise: requested,
        test_mode: testMode, checkout_payload: { purpose: "WALLET_RECHARGE" } });
      if (insertError) throw insertError;
      const gatewayResponse = await fetch("https://api.razorpay.com/v1/orders", { method: "POST",
        headers: { Authorization: `Basic ${btoa(`${keyId}:${keySecret}`)}`, "Content-Type": "application/json" },
        body: JSON.stringify({ amount: requested, currency: "INR", receipt,
          notes: { zomeal_payment_order_id: localId, customer_id: user.id, purpose: "WALLET_RECHARGE" } }) });
      const gatewayBody = await gatewayResponse.json();
      if (!gatewayResponse.ok) {
        await db.from("payment_orders").update({ status: "FAILED", failure_code: gatewayBody?.error?.code ?? "ORDER_CREATE_FAILED",
          failure_description: gatewayBody?.error?.description ?? "Razorpay order creation failed" }).eq("id", localId);
        return jsonResponse({ error: gatewayBody?.error?.description ?? "Could not create wallet recharge" }, 502);
      }
      await db.from("payment_orders").update({ status: "CREATED", gateway_order_id: gatewayBody.id }).eq("id", localId);
      return jsonResponse({ payment_order_id: localId, razorpay_order_id: gatewayBody.id, key_id: keyId,
        amount_paise: requested, currency: "INR", receipt, test_mode: testMode, purpose: "WALLET_RECHARGE" });
    }

    if (!body.package_id && !body.subscription_id) return jsonResponse({ error: "package_id or subscription_id is required" }, 400);

    if (body.subscription_id) {
      const { data: subscription, error: subscriptionError } = await db.from("customer_subscriptions")
        .select("id,customer_id,provider_id,package_id,total_paid_paise,status").eq("id",body.subscription_id).eq("customer_id",user.id)
        .in("status",["ACTIVE","PAUSED","CANCEL_PENDING"]).maybeSingle();
      if(subscriptionError) throw subscriptionError;
      if(!subscription) return jsonResponse({ error: "Active subscription was not found" }, 404);
      const { data: planOrder, error: planError }=await db.from("payment_orders").select("plan_total_paise")
        .eq("subscription_id",subscription.id).eq("status","CAPTURED").order("plan_total_paise",{ascending:false}).limit(1).maybeSingle();
      if(planError) throw planError;
      const planTotal=Number(planOrder?.plan_total_paise??subscription.total_paid_paise);
      const paid=Number(subscription.total_paid_paise);
      const remaining = Math.max(planTotal-paid,0);
      const {data:openOrder,error:openError}=await db.from("payment_orders").select("id,gateway_order_id,amount_paise,receipt,test_mode")
        .eq("subscription_id",subscription.id).in("status",["CREATING","CREATED","AUTHORIZED"]).eq("checkout_payload->>purpose","PLAN_BALANCE")
        .order("created_at",{ascending:false}).limit(1).maybeSingle();
      if(openError)throw openError;
      if(openOrder){
        if(body.quote_only===true)return jsonResponse({plan_total_paise:planTotal,paid_paise:paid,remaining_paise:remaining,minimum_paise:Number(openOrder.amount_paise),maximum_paise:Number(openOrder.amount_paise),open_payment:true});
        if(!openOrder.gateway_order_id)return jsonResponse({error:"Your payment order is being prepared. Try again in a moment."},409);
        if(body.amount_paise!==undefined&&body.amount_paise!==Number(openOrder.amount_paise))return jsonResponse({error:"An earlier balance payment is still open. Complete that amount before choosing another."},409);
        return jsonResponse({payment_order_id:openOrder.id,razorpay_order_id:openOrder.gateway_order_id,key_id:keyId,amount_paise:Number(openOrder.amount_paise),currency:"INR",receipt:openOrder.receipt,test_mode:openOrder.test_mode,plan_total_paise:planTotal,remaining_balance_paise:remaining-Number(openOrder.amount_paise),purpose:"PLAN_BALANCE",reused:true});
      }
      const requested = body.amount_paise ?? Math.min(remaining, 1000000);
      const minimum = Math.min(50000, remaining);
      if (!Number.isSafeInteger(requested) || requested < minimum || requested > Math.min(remaining,1000000))
        return jsonResponse({ error: `Pay between ${minimum} and ${Math.min(remaining,1000000)} paise toward this balance.` }, 400);
      if (remaining<=0) return jsonResponse({error:"This plan is fully paid"},409);
      if (body.quote_only === true) return jsonResponse({ plan_total_paise:planTotal,paid_paise:paid,remaining_paise:remaining,minimum_paise:minimum,maximum_paise:Math.min(remaining,1000000) });
      const localId=crypto.randomUUID(); const receipt=`ZM-${localId.replaceAll("-","").slice(0,20)}`;
      const { error: insertError }=await db.from("payment_orders").insert({id:localId,customer_id:user.id,provider_id:subscription.provider_id,package_id:subscription.package_id,
        subscription_id:subscription.id,receipt,package_amount_paise:requested,amount_paise:requested,plan_total_paise:planTotal,test_mode:testMode,
        checkout_payload:{purpose:"PLAN_BALANCE",subscription_id:subscription.id}});
      if(insertError) throw insertError;
      const gatewayResponse=await fetch("https://api.razorpay.com/v1/orders",{method:"POST",headers:{Authorization:`Basic ${btoa(`${keyId}:${keySecret}`)}`,"Content-Type":"application/json"},
        body:JSON.stringify({amount:requested,currency:"INR",receipt,notes:{zomeal_payment_order_id:localId,subscription_id:subscription.id,purpose:"PLAN_BALANCE"}})});
      const gatewayBody=await gatewayResponse.json();
      if(!gatewayResponse.ok){await db.from("payment_orders").update({status:"FAILED",failure_code:gatewayBody?.error?.code??"ORDER_CREATE_FAILED",failure_description:gatewayBody?.error?.description??"Razorpay order creation failed"}).eq("id",localId);return jsonResponse({error:gatewayBody?.error?.description??"Could not create payment order"},502);}
      await db.from("payment_orders").update({status:"CREATED",gateway_order_id:gatewayBody.id}).eq("id",localId);
      return jsonResponse({payment_order_id:localId,razorpay_order_id:gatewayBody.id,key_id:keyId,amount_paise:requested,currency:"INR",receipt,test_mode:testMode,
        plan_total_paise:planTotal,remaining_balance_paise:remaining-requested,purpose:"PLAN_BALANCE"});
    }

    const { data: packageRow, error: packageError } = await db
      .from("packages")
      .select("id,provider_id,name,kind,duration_days,is_active")
      .eq("id", body.package_id)
      .eq("is_active", true)
      .maybeSingle();
    if (packageError) throw packageError;
    if (!packageRow) return jsonResponse({ error: "This package is no longer available" }, 409);

    const { data: price, error: priceError } = await db
      .from("package_price_versions")
      .select("total_price_paise")
      .eq("package_id", packageRow.id)
      .eq("status", "APPROVED")
      .is("effective_until", null)
      .order("version", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (priceError) throw priceError;
    if (!price) return jsonResponse({ error: "This package does not have an approved price" }, 409);

    const { data: settings, error: settingsError } = await db
      .from("platform_settings")
      .select("setting_key,value")
      .in("setting_key", ["customer_platform_fee", "subscription_delivery_fee"])
      .lte("effective_from", new Date().toISOString())
      .or(`effective_until.is.null,effective_until.gt.${new Date().toISOString()}`);
    if (settingsError) throw settingsError;
    const setting = (key: string) => settings?.find((item) => item.setting_key === key)?.value ?? {};
    const packageAmount = Number(price.total_price_paise);
    const platformBasisPoints = Number(setting("customer_platform_fee").basis_points ?? 150);
    const platformFee = Math.round(packageAmount * platformBasisPoints / 10000);
    const configuredDeliveryFee = setting("subscription_delivery_fee");
    const deliveryFeeDuration = Number(configuredDeliveryFee.duration_days ?? 30);
    const deliveryFee = Math.round(Number(configuredDeliveryFee.amount_paise ?? 9900) * Number(packageRow.duration_days) / deliveryFeeDuration);
    const discount = 0;
    const planTotal = packageAmount + platformFee + deliveryFee - discount;
    // Initial subscription checkout temporarily accepts ₹5 for controlled testing.
    // Restore minimum_paise to 50000 before production launch.
    if (body.quote_only === true) return jsonResponse({ plan_total_paise: planTotal, minimum_paise: 500, maximum_paise: Math.min(planTotal, 1000000) });
    if (body.quoted_total_paise !== undefined && body.quoted_total_paise !== planTotal) return jsonResponse({ error: "The plan price has changed. Go back and reopen payment to review the latest total." }, 409);
    let advance;
    try { advance = advancePayment(packageAmount, platformFee, deliveryFee, body.amount_paise); }
    catch (error) { return jsonResponse({ error: errorMessage(error) }, 400); }
    const amount = advance.amount;

    const localId = crypto.randomUUID();
    const receipt = `ZM-${localId.replaceAll("-", "").slice(0, 20)}`;
    const checkoutPayload = {
      purpose: "INITIAL_PLAN",
      delivery_address: body.delivery_address ?? {}, weekly_menu: body.weekly_menu ?? {},
      start_date: body.start_date ?? null, first_meal: body.first_meal ?? null,
    };
    const { error: insertError } = await db.from("payment_orders").insert({
      id: localId, customer_id: user?.id ?? null, provider_id: packageRow.provider_id,
      package_id: packageRow.id, receipt, package_amount_paise: advance.packageAmount,
      platform_fee_paise: advance.fee, delivery_fee_paise: advance.delivery,
      plan_total_paise: advance.total,
      discount_paise: discount, amount_paise: amount, test_mode: testMode,
      checkout_payload: checkoutPayload,
    });
    if (insertError) throw insertError;

    const gatewayResponse = await fetch("https://api.razorpay.com/v1/orders", {
      method: "POST",
      headers: {
        Authorization: `Basic ${btoa(`${keyId}:${keySecret}`)}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ amount, currency: "INR", receipt, notes: {
        zomeal_payment_order_id: localId, package_id: packageRow.id,
        customer_id: user.id,
      }}),
    });
    const gatewayBody = await gatewayResponse.json();
    if (!gatewayResponse.ok) {
      await db.from("payment_orders").update({
        status: "FAILED", failure_code: gatewayBody?.error?.code ?? "ORDER_CREATE_FAILED",
        failure_description: gatewayBody?.error?.description ?? "Razorpay order creation failed",
      }).eq("id", localId);
      return jsonResponse({ error: gatewayBody?.error?.description ?? "Could not create payment order" }, 502);
    }
    await db.from("payment_orders").update({ status: "CREATED", gateway_order_id: gatewayBody.id }).eq("id", localId);

    return jsonResponse({
      payment_order_id: localId, razorpay_order_id: gatewayBody.id, key_id: keyId,
      amount_paise: amount, currency: "INR", receipt, test_mode: testMode,
      plan_total_paise: advance.total, remaining_balance_paise: advance.remaining,
      package: { id: packageRow.id, name: packageRow.name, kind: packageRow.kind },
      breakdown: { package_paise: packageAmount, platform_fee_paise: platformFee, delivery_fee_paise: deliveryFee, discount_paise: discount },
    });
  } catch (error) {
    console.error("create-razorpay-order", error);
    return jsonResponse({ error: errorMessage(error) }, 500);
  }
});
