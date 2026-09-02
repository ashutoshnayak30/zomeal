import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";

type SendSmsEvent = {
  user?: {
    phone?: string | null;
  };
  sms?: {
    otp?: string | null;
  };
};

type Msg91Response = {
  type?: string;
  message?: string;
};

const MSG91_FLOW_URL = "https://api.msg91.com/api/v5/flow/";

function errorResponse(message: string, status: number): Response {
  return Response.json(
    { error: { http_code: status, message } },
    { status },
  );
}

function requiredSecret(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`Missing required secret: ${name}`);
  return value;
}

function normalizeIndianMobile(phone: string): string | null {
  let digits = phone.replace(/\D/g, "");
  if (digits.length === 10) digits = `91${digits}`;
  if (digits.startsWith("0") && digits.length === 11) {
    digits = `91${digits.slice(1)}`;
  }
  return /^91[6-9]\d{9}$/.test(digits) ? digits : null;
}

function hookSigningSecret(): string {
  const configured = requiredSecret("SEND_SMS_HOOK_SECRETS");
  const firstSecret = configured.split("|")[0]?.trim();
  return firstSecret.replace(/^v1,whsec_/, "");
}

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return errorResponse("Method not allowed", 405);
  }

  let event: SendSmsEvent;
  try {
    const rawBody = await request.text();
    const verifier = new Webhook(hookSigningSecret());
    event = verifier.verify(
      rawBody,
      Object.fromEntries(request.headers),
    ) as SendSmsEvent;
  } catch (error) {
    console.error("Rejected invalid Send SMS hook request", {
      reason: error instanceof Error ? error.message : "verification failed",
    });
    return errorResponse("Invalid hook signature", 401);
  }

  const mobile = normalizeIndianMobile(event.user?.phone ?? "");
  const otp = event.sms?.otp?.trim() ?? "";
  if (!mobile || !/^\d{6}$/.test(otp)) {
    return errorResponse("Invalid phone number or verification code", 400);
  }

  let authKey: string;
  let flowId: string;
  let senderId: string;
  try {
    authKey = requiredSecret("MSG91_AUTH_KEY");
    flowId = requiredSecret("MSG91_TEMPLATE_ID");
    senderId = requiredSecret("MSG91_SENDER_ID");
  } catch (error) {
    console.error(error instanceof Error ? error.message : "Missing SMS configuration");
    return errorResponse("SMS service is not configured", 500);
  }

  try {
    const response = await fetch(MSG91_FLOW_URL, {
      method: "POST",
      headers: {
        accept: "application/json",
        authkey: authKey,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        flow_id: flowId,
        sender: senderId,
        recipients: [{ mobiles: mobile, OTP: otp }],
      }),
      signal: AbortSignal.timeout(4_000),
    });

    const result = await response.json().catch(() => ({})) as Msg91Response;
    if (!response.ok || result.type !== "success") {
      console.error("MSG91 rejected Send SMS request", {
        status: response.status,
        providerType: result.type ?? "unknown",
        providerMessage: result.message ?? "No response message",
      });
      return errorResponse("SMS provider could not send the code", 502);
    }

    // Supabase Send SMS hooks require no response body on success.
    return new Response(null, { status: 200 });
  } catch (error) {
    console.error("MSG91 Send SMS request failed", {
      reason: error instanceof Error ? error.message : "request failed",
    });
    return errorResponse("SMS provider is temporarily unavailable", 502);
  }
});
