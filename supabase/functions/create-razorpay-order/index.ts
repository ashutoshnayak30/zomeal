import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";

type CreateOrderBody = {
  package_id?: string;
  delivery_address?: Record<string, unknown>;
  weekly_menu?: Record<string, unknown>;
  start_date?: string;
  first_meal?: "LUNCH" | "DINNER";
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
    if (!body.package_id) return jsonResponse({ error: "package_id is required" }, 400);
    const db = serviceClient();

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
    const deliveryFee = Number(setting("subscription_delivery_fee").amount_paise ?? 9900);
    const discount = 0;
    const amount = packageAmount + platformFee + deliveryFee - discount;
    if (!Number.isSafeInteger(amount) || amount <= 0) throw new Error("The calculated payment amount is invalid");

    const localId = crypto.randomUUID();
    const receipt = `ZM-${localId.replaceAll("-", "").slice(0, 20)}`;
    const checkoutPayload = {
      delivery_address: body.delivery_address ?? {}, weekly_menu: body.weekly_menu ?? {},
      start_date: body.start_date ?? null, first_meal: body.first_meal ?? null,
    };
    const { error: insertError } = await db.from("payment_orders").insert({
      id: localId, customer_id: user?.id ?? null, provider_id: packageRow.provider_id,
      package_id: packageRow.id, receipt, package_amount_paise: packageAmount,
      platform_fee_paise: platformFee, delivery_fee_paise: deliveryFee,
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
        customer_id: user?.id ?? "anonymous-test",
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
      package: { id: packageRow.id, name: packageRow.name, kind: packageRow.kind },
      breakdown: { package_paise: packageAmount, platform_fee_paise: platformFee, delivery_fee_paise: deliveryFee, discount_paise: discount },
    });
  } catch (error) {
    console.error("create-razorpay-order", error);
    return jsonResponse({ error: errorMessage(error) }, 500);
  }
});
