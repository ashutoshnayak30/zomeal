import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";

// Only cleans paths captured by the atomic, administrator-checked deletion RPC.
// Callers cannot submit their own paths or buckets.
Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse({ message: "Method not allowed" }, 405);
  try {
    const caller = await optionalUser(request);
    if (!caller) return jsonResponse({ message: "Please sign in again" }, 401);
    const client = serviceClient();
    const [staff, role, profile] = await Promise.all([
      client.from("admin_staff_profiles").select("staff_role").eq("user_id", caller.id).maybeSingle(),
      client.from("user_roles").select("role").eq("user_id", caller.id).eq("role", "ADMIN").maybeSingle(),
      client.from("profiles").select("is_active").eq("id", caller.id).maybeSingle(),
    ]);
    if (staff.error || role.error || profile.error) throw new Error("Could not verify permissions");
    if (!["SUPER_ADMIN", "ADMINISTRATOR"].includes(staff.data?.staff_role || "") || !role.data || !profile.data?.is_active) {
      return jsonResponse({ message: "Administrator access required" }, 403);
    }
    const body = await request.json();
    if (body.action === "list") {
      const { data, error } = await client.from("admin_account_deletions")
        .select("id,target_kind,target_id,created_at,cleanup_status")
        .eq("cleanup_status", "PENDING").order("created_at").limit(50);
      if (error) throw error;
      return jsonResponse({ jobs: data });
    }
    if (body.action !== "cleanup" || !/^[0-9a-f-]{36}$/i.test(body.job_id || "")) {
      return jsonResponse({ message: "Choose a valid cleanup job" }, 400);
    }
    const { data: job, error } = await client.from("admin_account_deletions").select("*").eq("id", body.job_id).single();
    if (error) throw error;
    if (job.cleanup_status === "COMPLETE") return jsonResponse({ complete: true });
    for (const bucket of ["provider-media", "provider-documents"]) {
      const paths: string[] = job.objects.filter((item: { bucket: string; path: string }) => {
        if (!["provider-media", "provider-documents"].includes(item.bucket) || item.path.split("/")[0] !== job.target_id) {
          throw new Error("Cleanup path validation failed");
        }
        return item.bucket === bucket;
      }).map((item: { path: string }) => item.path);
      for (let offset = 0; offset < paths.length; offset += 100) {
        const { error: storageError } = await client.storage.from(bucket).remove(paths.slice(offset, offset + 100));
        if (storageError) throw storageError;
      }
    }
    const { error: updateError } = await client.from("admin_account_deletions")
      .update({ cleanup_status: "COMPLETE", completed_at: new Date().toISOString(), objects: [] }).eq("id", job.id);
    if (updateError) throw updateError;
    return jsonResponse({ complete: true });
  } catch (error) {
    return jsonResponse({ message: errorMessage(error) }, 400);
  }
});
