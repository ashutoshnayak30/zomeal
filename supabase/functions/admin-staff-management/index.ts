import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";

const STAFF_ROLES = ["ADMIN", "OPERATIONS", "FINANCE"] as const;
type StaffRole = typeof STAFF_ROLES[number];

function validRole(value: unknown): value is StaffRole {
  return typeof value === "string" && STAFF_ROLES.includes(value as StaffRole);
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return jsonResponse({ message: "Method not allowed" }, 405);

  try {
    const caller = await optionalUser(request);
    if (!caller) return jsonResponse({ message: "Your admin session has expired" }, 401);

    const client = serviceClient();
    const { data: callerRole, error: callerRoleError } = await client
      .from("user_roles")
      .select("role")
      .eq("user_id", caller.id)
      .eq("role", "ADMIN")
      .maybeSingle();
    if (callerRoleError) throw callerRoleError;
    if (!callerRole) return jsonResponse({ message: "Only an ADMIN can manage staff access" }, 403);

    const body = await request.json().catch(() => ({}));
    const action = String(body.action || "list").toLowerCase();

    if (action === "list") {
      const { data: roleRows, error: roleError } = await client
        .from("user_roles")
        .select("user_id,role,created_at")
        .in("role", [...STAFF_ROLES])
        .order("created_at", { ascending: false });
      if (roleError) throw roleError;

      const { data: usersPage, error: usersError } = await client.auth.admin.listUsers({ page: 1, perPage: 1000 });
      if (usersError) throw usersError;
      const users = new Map(usersPage.users.map((user) => [user.id, user]));
      const staff = (roleRows || []).map((row) => {
        const user = users.get(row.user_id);
        return {
          user_id: row.user_id,
          email: user?.email || "Unknown email",
          role: row.role,
          invited_at: user?.invited_at || null,
          last_sign_in_at: user?.last_sign_in_at || null,
          email_confirmed_at: user?.email_confirmed_at || null,
          created_at: row.created_at,
          is_current_user: row.user_id === caller.id,
        };
      });
      return jsonResponse({ staff });
    }

    if (action === "invite") {
      const email = String(body.email || "").trim().toLowerCase();
      const role = body.role;
      if (!/^\S+@\S+\.\S+$/.test(email)) return jsonResponse({ message: "Enter a valid email address" }, 400);
      if (!validRole(role)) return jsonResponse({ message: "Choose ADMIN, OPERATIONS or FINANCE" }, 400);

      const { data: usersPage, error: usersError } = await client.auth.admin.listUsers({ page: 1, perPage: 1000 });
      if (usersError) throw usersError;
      let target = usersPage.users.find((user) => user.email?.toLowerCase() === email);
      let invited = false;
      if (!target) {
        const { data, error } = await client.auth.admin.inviteUserByEmail(email, {
          redirectTo: "https://admin.zomeal.in",
          data: { invited_to: "zomeal_admin", staff_role: role },
        });
        if (error) throw error;
        target = data.user;
        invited = true;
      }

      await client.from("user_roles").delete().eq("user_id", target.id).in("role", [...STAFF_ROLES]);
      const { error: insertError } = await client.from("user_roles").insert({ user_id: target.id, role });
      if (insertError) throw insertError;
      await client.from("audit_logs").insert({
        actor_id: caller.id,
        action: invited ? "ADMIN_STAFF_INVITED" : "ADMIN_STAFF_ACCESS_GRANTED",
        entity_type: "user",
        entity_id: target.id,
        after_data: { email, role, invited },
      });
      return jsonResponse({ message: invited ? `Invitation sent to ${email}` : `Access updated for ${email}` });
    }

    if (action === "change_role") {
      const userId = String(body.user_id || "");
      const role = body.role;
      if (!userId || !validRole(role)) return jsonResponse({ message: "A valid staff member and role are required" }, 400);
      await client.from("user_roles").delete().eq("user_id", userId).in("role", [...STAFF_ROLES]);
      const { error } = await client.from("user_roles").insert({ user_id: userId, role });
      if (error) throw error;
      await client.from("audit_logs").insert({ actor_id: caller.id, action: "ADMIN_STAFF_ROLE_CHANGED", entity_type: "user", entity_id: userId, after_data: { role } });
      return jsonResponse({ message: "Staff role updated" });
    }

    if (action === "revoke") {
      const userId = String(body.user_id || "");
      if (!userId) return jsonResponse({ message: "Choose a staff account" }, 400);
      if (userId === caller.id) return jsonResponse({ message: "You cannot revoke your own admin access" }, 400);
      const { error } = await client.from("user_roles").delete().eq("user_id", userId).in("role", [...STAFF_ROLES]);
      if (error) throw error;
      await client.from("audit_logs").insert({ actor_id: caller.id, action: "ADMIN_STAFF_ACCESS_REVOKED", entity_type: "user", entity_id: userId });
      return jsonResponse({ message: "Staff access revoked" });
    }

    return jsonResponse({ message: "Unsupported action" }, 400);
  } catch (error) {
    return jsonResponse({ message: errorMessage(error) }, 400);
  }
});
