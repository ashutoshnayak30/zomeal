import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";

type ServiceAccount = { project_id: string; client_email: string; private_key: string };
const encode = (value: string | Uint8Array) => {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
};
const pemBytes = (pem: string) => Uint8Array.from(atob(pem.replace(/-----[^-]+-----/g, "").replace(/\s/g, "")), c => c.charCodeAt(0));

async function googleAccessToken(account: ServiceAccount) {
  const now = Math.floor(Date.now() / 1000);
  const header = encode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = encode(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3500 }));
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(account.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = encode(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claim}`))));
  const response = await fetch("https://oauth2.googleapis.com/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${header}.${claim}.${signature}` }) });
  const result = await response.json();
  if (!response.ok || !result.access_token) throw new Error(result.error_description || "Firebase authorization failed");
  return String(result.access_token);
}

Deno.serve(async request => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse({ error: "Method not allowed" }, 405);
  try {
    const user = await optionalUser(request);
    if (!user) return jsonResponse({ error: "Authentication required" }, 401);
    const supabase = serviceClient();
    const { data: roles } = await supabase.from("user_roles").select("role").eq("user_id", user.id);
    if (!(roles || []).some(row => ["ADMIN", "OPERATIONS", "FINANCE"].includes(row.role))) return jsonResponse({ error: "Administrator access required" }, 403);

    const body = await request.json();
    const title = String(body.title || "Zomeal").trim().slice(0, 100);
    const messageBody = String(body.body || "").trim().slice(0, 500);
    const destination = String(body.destination || "home").trim().slice(0, 80);
    const appKind = String(body.app_kind || "CUSTOMER").toUpperCase();
    if (!title || !messageBody || !["CUSTOMER", "PROVIDER"].includes(appKind)) return jsonResponse({ error: "Invalid notification" }, 400);

    let userIds: string[] = Array.isArray(body.user_ids) ? body.user_ids.map(String).slice(0, 2000) : [];
    let campaignId: string | null = null;
    let recipientCount = userIds.length;
    if (body.audience === "TARGETED_CUSTOMERS") {
      if (appKind !== "CUSTOMER") return jsonResponse({ error: "Targeted audiences are available for customer notifications only" }, 400);
      const { data: campaign, error: campaignError } = await supabase.rpc("admin_create_customer_notification_campaign", {
        requesting_admin: user.id,
        target_audience: String(body.target_type || "ALL"),
        target_value: body.target_value == null ? null : String(body.target_value),
        target_title: title,
        target_message: messageBody,
        target_destination: "notifications",
      });
      if (campaignError) throw campaignError;
      campaignId = String(campaign?.campaign_id || "") || null;
      userIds = Array.isArray(campaign?.user_ids) ? campaign.user_ids.map(String) : [];
      recipientCount = Number(campaign?.recipient_count || userIds.length);
    }
    if (body.audience === "LOW_WALLET_CUSTOMERS") {
      const { data: wallets, error } = await supabase.from("customer_wallets").select("customer_id,balance_paise").lt("balance_paise", 50000).limit(500);
      if (error) throw error;
      userIds = (wallets || []).map(row => row.customer_id);
    }
    if (!userIds.length) return jsonResponse({ sent: 0, devices: 0, recipients: recipientCount, campaign_id: campaignId, message: "No recipients" });
    const devices: { id: string; token: string }[] = [];
    for (let index = 0; index < userIds.length; index += 200) {
      const { data: page, error: tokenError } = await supabase.from("push_device_tokens").select("id,token").eq("app_kind", appKind).eq("enabled", true).in("user_id", userIds.slice(index, index + 200));
      if (tokenError) throw tokenError;
      devices.push(...(page || []));
    }

    const account = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") || "{}") as ServiceAccount;
    if (!account.project_id || !account.client_email || !account.private_key) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not configured");
    const accessToken = await googleAccessToken(account);
    let sent = 0; const invalid: string[] = []; const failures: string[] = [];
    for (const device of devices) {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
        method: "POST", headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({ message: { token: device.token, notification: { title, body: messageBody }, data: { title, body: messageBody, destination }, android: { priority: "high", notification: { channel_id: appKind === "PROVIDER" ? "zomeal_provider_updates" : "zomeal_updates" } } } }),
      });
      if (response.ok) sent++;
      else { const detail = await response.text(); failures.push(detail.slice(0, 240)); if (/UNREGISTERED|registration-token-not-registered/i.test(detail)) invalid.push(device.id); }
    }
    if (invalid.length) await supabase.from("push_device_tokens").update({ enabled: false, updated_at: new Date().toISOString() }).in("id", invalid);
    if (campaignId) await supabase.from("customer_notification_campaigns").update({ device_count: devices.length, sent_count: sent, failed_count: devices.length - sent, completed_at: new Date().toISOString() }).eq("id", campaignId);
    return jsonResponse({ sent, devices: devices.length, recipients: recipientCount, campaign_id: campaignId, invalidated: invalid.length, failed: devices.length - sent, errors: failures.slice(0, 3) });
  } catch (error) { return jsonResponse({ error: errorMessage(error) }, 500); }
});
