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
    const client = serviceClient();
    const caller = await optionalUser(request);
    if (!caller) return jsonResponse({ message: "Your session has expired" }, 401);
    const body = await request.json().catch(() => ({}));
    const action = String(body.action || "list").toLowerCase();

    if (action === "finalize_otp") {
      const email = String(caller.email || "").trim().toLowerCase();
      const { data: invitation, error: invitationError } = await client
        .from("admin_staff_invitations")
        .select("id,email,role,invited_by,expires_at")
        .eq("email", email)
        .eq("status", "PENDING")
        .gt("expires_at", new Date().toISOString())
        .order("invited_at", { ascending: false })
        .limit(1)
        .maybeSingle();
      if (invitationError) throw invitationError;
      if (!invitation) return jsonResponse({ message: "No valid staff invitation was found for this email" }, 403);
      await client.from("user_roles").delete().eq("user_id", caller.id).in("role", [...STAFF_ROLES]);
      const { error: roleError } = await client.from("user_roles").insert({ user_id: caller.id, role: invitation.role });
      if (roleError) throw roleError;
      const { error: verifiedError } = await client.from("admin_staff_invitations").update({ status: "VERIFIED", verified_user_id: caller.id, verified_at: new Date().toISOString() }).eq("id", invitation.id);
      if (verifiedError) throw verifiedError;
      await client.from("audit_logs").insert({ actor_id: caller.id, action: "ADMIN_STAFF_EMAIL_OTP_VERIFIED", entity_type: "user", entity_id: caller.id, after_data: { email, role: invitation.role, invited_by: invitation.invited_by } });
      return jsonResponse({ message: "Email verified and staff access activated", role: invitation.role });
    }

    const { data: callerRole, error: callerRoleError } = await client
      .from("user_roles")
      .select("role")
      .eq("user_id", caller.id)
      .eq("role", "ADMIN")
      .maybeSingle();
    if (callerRoleError) throw callerRoleError;
    if (!callerRole) return jsonResponse({ message: "Only an ADMIN can manage staff access" }, 403);

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
      const { data: invitations, error: invitationsError } = await client
        .from("admin_staff_invitations")
        .select("id,email,role,status,invited_at,expires_at")
        .eq("status", "PENDING")
        .order("invited_at", { ascending: false });
      if (invitationsError) throw invitationsError;
      const pending = (invitations || []).filter((invite) => !staff.some((member) => member.email.toLowerCase() === invite.email.toLowerCase()));
      return jsonResponse({ staff, pending });
    }

    if (action === "invite") {
      const email = String(body.email || "").trim().toLowerCase();
      const role = body.role;
      if (!/^\S+@\S+\.\S+$/.test(email)) return jsonResponse({ message: "Enter a valid email address" }, 400);
      if (!validRole(role)) return jsonResponse({ message: "Choose ADMIN, OPERATIONS or FINANCE" }, 400);

      await client.from("admin_staff_invitations").update({ status: "CANCELLED" }).eq("email", email).eq("status", "PENDING");
      const { data: invitation, error: invitationError } = await client.from("admin_staff_invitations").insert({ email, role, invited_by: caller.id }).select("id").single();
      if (invitationError) throw invitationError;
      const { error: otpError } = await client.auth.signInWithOtp({ email, options: { shouldCreateUser: true, data: { invited_to: "zomeal_admin", staff_role: role } } });
      if (otpError) {
        await client.from("admin_staff_invitations").update({ status: "CANCELLED" }).eq("id", invitation.id);
        throw otpError;
      }
      await client.from("audit_logs").insert({
        actor_id: caller.id,
        action: "ADMIN_STAFF_OTP_SENT",
        entity_type: "user",
        entity_id: invitation.id,
        after_data: { email, role, expires_in_hours: 24 },
      });
      return jsonResponse({ message: `Six-digit verification code sent to ${email}` });
    }

    if (action === "cancel_invite") {
      const invitationId = String(body.invitation_id || "");
      if (!invitationId) return jsonResponse({ message: "Choose an invitation" }, 400);
      const { error } = await client.from("admin_staff_invitations").update({ status: "CANCELLED" }).eq("id", invitationId).eq("status", "PENDING");
      if (error) throw error;
      await client.from("audit_logs").insert({ actor_id: caller.id, action: "ADMIN_STAFF_INVITATION_CANCELLED", entity_type: "admin_staff_invitation", entity_id: invitationId });
      return jsonResponse({ message: "Pending invitation cancelled" });
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
