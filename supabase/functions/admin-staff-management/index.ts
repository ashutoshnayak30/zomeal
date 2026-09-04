import { corsHeaders, errorMessage, jsonResponse } from "../_shared/http.ts";
import { optionalUser, serviceClient } from "../_shared/supabase.ts";

const STAFF_ROLES = ["ADMIN", "OPERATIONS", "FINANCE"] as const;
type StaffRole = typeof STAFF_ROLES[number];
const STAFF_TITLES = ["SUPER_ADMIN", "ADMINISTRATOR", "OPERATIONS_MANAGER", "PROVIDER_ONBOARDING", "CATALOGUE_REVIEWER", "SERVICE_AREA_MANAGER", "CUSTOMER_SUPPORT", "FINANCE_MANAGER", "FINANCE_EXECUTIVE", "AUDITOR"] as const;
type StaffTitle = typeof STAFF_TITLES[number];

function validStaffTitle(value: unknown): value is StaffTitle {
  return typeof value === "string" && STAFF_TITLES.includes(value as StaffTitle);
}

function permissionGroup(title: StaffTitle): StaffRole {
  if (title === "SUPER_ADMIN" || title === "ADMINISTRATOR") return "ADMIN";
  if (title === "FINANCE_MANAGER" || title === "FINANCE_EXECUTIVE" || title === "AUDITOR") return "FINANCE";
  return "OPERATIONS";
}

function defaultTitle(role: StaffRole): StaffTitle {
  if (role === "ADMIN") return "ADMINISTRATOR";
  if (role === "FINANCE") return "FINANCE_MANAGER";
  return "OPERATIONS_MANAGER";
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
        .select("id,email,role,staff_role,invited_by,expires_at")
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
      const { error: profileError } = await client.from("admin_staff_profiles").upsert({ user_id: caller.id, staff_role: invitation.staff_role || defaultTitle(invitation.role), assigned_by: invitation.invited_by, updated_at: new Date().toISOString() });
      if (profileError) throw profileError;
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

    // Do not let an ordinary admin promote themselves into the data-deletion role.
    const { data: callerTitle, error: callerTitleError } = await client.from("admin_staff_profiles")
      .select("staff_role").eq("user_id", caller.id).maybeSingle();
    if (callerTitleError) throw callerTitleError;
    if (["invite", "change_role", "revoke", "cancel_invite"].includes(action) && callerTitle?.staff_role !== "SUPER_ADMIN") {
      return jsonResponse({ message: "Only a Super Administrator can change staff access" }, 403);
    }

    if (action === "list") {
      const { data: roleRows, error: roleError } = await client
        .from("user_roles")
        .select("user_id,role,granted_at")
        .in("role", [...STAFF_ROLES])
        .order("granted_at", { ascending: false });
      if (roleError) throw roleError;

      const { data: usersPage, error: usersError } = await client.auth.admin.listUsers({ page: 1, perPage: 1000 });
      if (usersError) throw usersError;
      const userIds = [...new Set((roleRows || []).map((row) => row.user_id))];
      const { data: profileRows } = userIds.length
        ? await client.from("admin_staff_profiles").select("user_id,staff_role").in("user_id", userIds)
        : { data: [], error: null };
      const staffProfiles = new Map((profileRows || []).map((profile) => [profile.user_id, profile.staff_role]));
      const users = new Map(usersPage.users.map((user) => [user.id, user]));
      const staff = (roleRows || []).map((row) => {
        const user = users.get(row.user_id);
        return {
          user_id: row.user_id,
          email: user?.email || "Unknown email",
          role: row.role,
          permission_group: row.role,
          staff_role: staffProfiles.get(row.user_id) || defaultTitle(row.role),
          invited_at: user?.invited_at || null,
          last_sign_in_at: user?.last_sign_in_at || null,
          email_confirmed_at: user?.email_confirmed_at || null,
          created_at: row.granted_at || user?.created_at || null,
          is_current_user: row.user_id === caller.id,
        };
      });
      let { data: invitations, error: invitationsError } = await client
        .from("admin_staff_invitations")
        .select("id,email,role,staff_role,status,invited_at,expires_at")
        .eq("status", "PENDING")
        .order("invited_at", { ascending: false });
      if (invitationsError) {
        const legacyResult = await client
          .from("admin_staff_invitations")
          .select("id,email,role,status,invited_at,expires_at")
          .eq("status", "PENDING")
          .order("invited_at", { ascending: false });
        if (legacyResult.error) throw legacyResult.error;
        invitations = (legacyResult.data || []).map((invite) => ({ ...invite, staff_role: defaultTitle(invite.role) }));
        invitationsError = null;
      }
      const pending = (invitations || []).filter((invite) => !staff.some((member) => member.email.toLowerCase() === invite.email.toLowerCase()));
      return jsonResponse({ staff, pending });
    }

    if (action === "invite") {
      const email = String(body.email || "").trim().toLowerCase();
      const staffRole = body.staff_role;
      if (!/^\S+@\S+\.\S+$/.test(email)) return jsonResponse({ message: "Enter a valid email address" }, 400);
      if (!validStaffTitle(staffRole)) return jsonResponse({ message: "Choose a valid Zomeal staff role" }, 400);
      const role = permissionGroup(staffRole);

      await client.from("admin_staff_invitations").update({ status: "CANCELLED" }).eq("email", email).eq("status", "PENDING");
      const { data: invitation, error: invitationError } = await client.from("admin_staff_invitations").insert({ email, role, staff_role: staffRole, invited_by: caller.id }).select("id").single();
      if (invitationError) throw invitationError;
      const { error: otpError } = await client.auth.signInWithOtp({
        email,
        options: {
          shouldCreateUser: true,
          emailRedirectTo: "https://admin.zomeal.in/?staff-invite=1",
          data: { invited_to: "zomeal_admin", staff_role: staffRole, permission_group: role },
        },
      });
      if (otpError) {
        await client.from("admin_staff_invitations").update({ status: "CANCELLED" }).eq("id", invitation.id);
        throw otpError;
      }
      await client.from("audit_logs").insert({
        actor_id: caller.id,
        action: "ADMIN_STAFF_OTP_SENT",
        entity_type: "user",
        entity_id: invitation.id,
        after_data: { email, role, staff_role: staffRole, expires_in_hours: 24 },
      });
      return jsonResponse({ message: `Six-digit email verification sent to ${email}` });
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
      const staffRole = body.staff_role;
      if (!userId || !validStaffTitle(staffRole)) return jsonResponse({ message: "A valid staff member and role are required" }, 400);
      if (userId === caller.id && staffRole !== "SUPER_ADMIN") return jsonResponse({ message: "You cannot remove your own Super Administrator access" }, 400);
      const role = permissionGroup(staffRole);
      await client.from("user_roles").delete().eq("user_id", userId).in("role", [...STAFF_ROLES]);
      const { error } = await client.from("user_roles").insert({ user_id: userId, role });
      if (error) throw error;
      const { error: profileError } = await client.from("admin_staff_profiles").upsert({ user_id: userId, staff_role: staffRole, assigned_by: caller.id, updated_at: new Date().toISOString() });
      if (profileError) throw profileError;
      await client.from("audit_logs").insert({ actor_id: caller.id, action: "ADMIN_STAFF_ROLE_CHANGED", entity_type: "user", entity_id: userId, after_data: { role, staff_role: staffRole } });
      return jsonResponse({ message: "Staff role updated" });
    }

    if (action === "revoke") {
      const userId = String(body.user_id || "");
      if (!userId) return jsonResponse({ message: "Choose a staff account" }, 400);
      if (userId === caller.id) return jsonResponse({ message: "You cannot revoke your own admin access" }, 400);
      const { error } = await client.from("user_roles").delete().eq("user_id", userId).in("role", [...STAFF_ROLES]);
      if (error) throw error;
      await client.from("admin_staff_profiles").delete().eq("user_id", userId);
      await client.from("audit_logs").insert({ actor_id: caller.id, action: "ADMIN_STAFF_ACCESS_REVOKED", entity_type: "user", entity_id: userId });
      return jsonResponse({ message: "Staff access revoked" });
    }

    return jsonResponse({ message: "Unsupported action" }, 400);
  } catch (error) {
    return jsonResponse({ message: errorMessage(error) }, 400);
  }
});
