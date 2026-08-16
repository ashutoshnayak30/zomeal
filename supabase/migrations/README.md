# Database migrations

Versioned PostgreSQL schema migrations are applied in timestamp order with the Supabase CLI.

Migration `202608130005_admin_provider_workspace.sql` adds transactional provider onboarding and editing, related catalogue persistence, live admin detail reads, and audited provider decisions.

Migration `202608130006_fix_provider_activation.sql` corrects the provider activation wrapper to call the established readiness-checked activation function.

Migration `202608130007_finalize_manual_provider.sql` provides one-click audited activation for manually onboarded providers and approves their complete submitted workspace.

Migration `202608130008_provider_operations_media.sql` adds delivery personnel, the live provider directory/readiness workspace, and audited admin item/provider photo uploads.

Migration `202608130009_admin_profile_controls.sql` protects verified provider phones and adds a full audited lunch/dinner weekly-menu replacement operation.

Migration `202608130010_menu_item_enrichment.sql` adds audited admin editing for descriptions, ingredients, nutrition and allergen declarations.

Migration `202608140001_admin_media_management.sql` lets admins review, relabel, approve, reject, suspend or archive provider-uploaded photos.

Migration `202608140002_multi_main_course_items.sql` stores every daily main-course alternative as a separate menu item so each dish can have its own photo, ingredients, allergens and nutrition.

Migration `202608140003_live_form_drafts.sql` provides authenticated, cross-device autosave and resume for incomplete admin and provider forms.

Migration `202608160001_subscription_operations_ledger.sql` snapshots confirmed subscription pricing, generates dated lunch/dinner meals, records customer tracking events, and accrues provider earnings after delivery with the 48-hour hold.

Migration `202608160002_provider_payout_requests.sql` reserves available provider earnings for audited UPI, bank, cheque or cash payout requests and records completed payouts in the financial ledger.

Migration `202608160003_admin_finance_workspace.sql` adds admin/finance payout queues, verified provider balances, and delivered-meal ledger inspection for settlement review.

Migration `202608160004_finance_controls_and_advances.sql` adds configurable settlement holds, audited finance test cycles, provider advance requests, admin disbursement controls, and automatic recovery from future earnings.

Migration `202608160005_provider_commission_terms.sql` replaces the fixed 14% assumption with provider-specific negotiated terms, defaults every provider to 14%, snapshots the agreed rate onto new subscriptions, and exposes the rate to admin and provider experiences.

Migration `202608160006_advance_full_amount.sql` separates requested and negotiated advance amounts, guarantees 0% advance commission, disburses the full approved amount, and recovers only from future net meal earnings.

Migration `202608160007_provider_payout_destinations.sql` adds post-activation UPI/bank payout destinations, masked provider/admin views, finance verification, audit records, and blocks electronic payout requests until a matching destination is verified.
