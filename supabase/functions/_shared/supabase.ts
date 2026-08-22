import { createClient, type User } from "https://esm.sh/@supabase/supabase-js@2";

export function serviceClient() {
  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) throw new Error("Supabase server configuration is missing");
  return createClient(url, serviceKey, { auth: { persistSession: false } });
}

export async function optionalUser(request: Request): Promise<User | null> {
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return null;
  const token = authorization.slice(7);
  const client = serviceClient();
  const { data, error } = await client.auth.getUser(token);
  return error ? null : data.user;
}
