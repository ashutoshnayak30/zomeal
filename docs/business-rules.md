# Zomeal MVP business rules

Status: Phase 2 baseline  
Last updated: 13 August 2026

This document is the product and operations source of truth for the first Zomeal marketplace release. Financial and tax configuration must remain configurable and must be reviewed by Zomeal's accountant and legal adviser before production launch.

## 1. Marketplace roles

- **Customer:** browses providers, configures menus, purchases and manages a subscription.
- **Provider:** maintains approved packages, menus, delivery areas and capacity; fulfils meals and views earnings.
- **Operations staff:** handles providers, serviceability, fulfilment and customer migrations.
- **Finance staff:** handles settlements, payout requests, refunds and reconciliations.
- **Administrator:** approves providers, price changes, area changes, closures, cancellations and staff permissions.
- A user may hold more than one authorized role, but privileged actions must be audited.

## 2. Serviceability

- Serviceability is determined by an exact Indian six-digit pincode.
- A provider may serve multiple approved pincodes.
- Providers may request an additional pincode or delivery-area expansion through the provider app.
- An administrator approves or rejects all additions, removals and expansions.
- A pincode is shown as serviceable only when it is active and has at least one approved, active provider with capacity for the requested package.
- Customers in an unserviceable pincode may browse providers and configure a menu, but cannot purchase until they select a serviceable address.
- Full delivery address is collected during plan review, after menu setup. The address pincode must match the pincode used for discovery.

## 3. Provider lifecycle and closure

- A provider cannot stop service or remove an active delivery area unilaterally.
- The provider submits a closure or area-exit request for administrator approval.
- Affected customers receive notice at least seven days before the approved stop date.
- Customers have five days to choose a compatible replacement provider.
- Zomeal sends reminders and operations staff may contact customers who have not selected a provider.
- A compatible provider may be assigned only when package, dietary requirements, serviceability, capacity and price conditions are satisfied and the transfer does not impose an undisclosed additional charge.
- If no compatible provider exists, the subscription must be paused, cancelled or refunded according to the approved resolution. No incompatible subscription may be silently activated.

## 4. Packages, pricing and changes

- Providers define Lunch Only, Dinner Only and Lunch + Dinner packages as applicable.
- Provider prices include separately identifiable lunch and dinner values, even for a combined package.
- Price records are versioned and effective-dated. A paid subscription retains its pricing snapshot.
- A provider price change remains pending until approved or rejected by an administrator.
- Approved prices affect only new subscriptions and renewals from their effective date.
- The initial Zomeal commission is **14% of the provider package value**.
- The customer platform fee is initially **1.5% of the provider package value**.
- The delivery fee is **₹99 for each 30-day subscription**, whether or not every scheduled meal is consumed.
- Commission, platform fee and delivery fee are configuration values, never hard-coded in client applications.

## 5. Discounts

- Zomeal and providers may fund discounts.
- A discount records its funding party (`ZOMEAL`, `PROVIDER` or `SHARED`), fixed or percentage value, validity period, eligibility, usage limits and approval state.
- Provider-funded discounts reduce provider settlement. Zomeal-funded discounts reduce Zomeal revenue.
- A shared discount records the exact funding split.
- Discount and pricing snapshots are retained with every purchase for audit and refund calculations.

## 6. Capacity

- Capacity is managed per provider, pincode, service date and meal slot.
- Availability uses reserved quantity, confirmed quantity and capacity limit, and must be checked atomically during checkout.
- A provider cannot reduce capacity below existing commitments without administrator approval and a customer-resolution plan.
- Replacement meals created by pauses consume future capacity.

## 7. Weekly menus and daily changes

- Providers publish available menu choices by service date and meal slot.
- Customers may configure Monday through Sunday menus within provider-defined choices.
- Zomeal may auto-select a full week using a customer's vegetarian/non-vegetarian preference.
- Daily menu changes do not rewrite the customer's reusable weekly-menu template.
- Change cut-offs are enforced by backend server time, not device time.
- Current MVP cut-offs and next-day presentation rules remain configurable by operations.

## 8. Pauses and extension credits

- A customer may pause up to seven lunches and seven dinners per subscription cycle.
- Lunch and dinner pauses are counted separately.
- An eligible paused meal generates one replacement meal credit of the same slot and value.
- Replacement meals extend the subscription schedule after the original end date, subject to provider capacity.
- A meal receives exactly one remedy: replacement/extension, renewal credit, wallet credit or refund—never more than one.
- Provider holidays, Zomeal cancellations and failed fulfilment do not consume the customer's pause allowance.
- Cut-offs and eligibility are validated by the backend.

## 9. Delivery, earnings and provider payouts

- Provider earnings accrue per confirmed delivered meal, not for the complete package in advance.
- Earnings become available 48 hours after confirmed delivery, unless held for a dispute, cancellation or refund.
- Provider balances distinguish pending, on-hold, available, requested, paid and reversed amounts.
- Providers may request any available amount.
- During MVP, payout requests may be initiated by phone and recorded by finance staff in the admin panel.
- Supported recorded payout modes are bank transfer, UPI, cheque and office cash.
- Every payout requires a unique reference, processor, timestamp and proof/acknowledgement. Cash and cheque require provider acknowledgement.
- Financial ledgers are append-only. Corrections use reversal entries rather than editing historical amounts.

## 10. Cancellation and refunds

- A customer may request cancellation of the complete subscription.
- Administrator approval is required and the decision target is within 48 hours.
- Zomeal owns customer communication and refund execution.
- Refund calculation uses the immutable purchase snapshot and considers delivered meals, eligible unused meals, discounts, fees and approved deductions.
- Providers cannot independently cancel an active customer subscription or issue a refund.
- Refund states are `REQUESTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `REFUND_INITIATED`, `REFUNDED` and `FAILED`.
- Every state transition records actor, time and reason.

## 11. Tax and compliance

- Tax must be represented as configurable rules with effective dates, taxable component, jurisdiction, rate and registration status.
- DPIIT/Startup India recognition must not be treated in software as an automatic general GST exemption.
- Zomeal must obtain written advice from a qualified Indian CA before production concerning GST registration, restaurant-service treatment, e-commerce operator obligations, TCS, invoicing and settlement reporting.
- Staging may use disabled tax rules, but invoice and ledger models must retain tax fields.

## 12. Audit, security and money representation

- All money is stored in integer paise; floating-point types are prohibited for financial amounts.
- Administrator, finance and operations changes are written to an audit log with actor, action, entity, before/after data and timestamp.
- Client apps never receive database service-role credentials.
- Role-based access and PostgreSQL row-level security are mandatory before production.
- Payment webhooks, refunds, settlement release and payout completion are server-side operations and must be idempotent.

## 13. Decisions still requiring approval

- Exact GST and TCS treatment after CA review.
- Payment-gateway fee allocation.
- Detailed cancellation/refund deduction formula.
- Exact customer menu-change and pause cut-off values.
- Maximum provider payout frequency and minimum payout amount.
- Rules for provider-funded delivery and promotional fee waivers.

## 14. Provider catalogue and media moderation

- Providers may create food items, daily lunch/dinner menus, alternate choices, nutrition facts and allergen declarations.
- Provider catalogue records remain drafts until submitted and approved by Zomeal.
- Providers may upload their logo, owner/profile photo, kitchen and packaging photos, package covers, complete meal photos and individual food-item photos.
- FSSAI and other compliance documents are private and never displayed in the customer app.
- Every new image or replacement begins as `PENDING_REVIEW`; providers cannot approve their own media.
- Only `APPROVED` media may be returned by customer-facing APIs or displayed in the customer app.
- Replacing an approved image creates a new version. The previous approved version may remain visible until the replacement is approved.
- Admin reviewers may approve, reject, suspend or archive media. Rejection requires a reason, and every review action is audited.
- Providers may view their own pending and rejected media and resubmit a corrected image.
- Providers must upload genuine, relevant images they own or are authorized to use. Misleading stock images are prohibited.
- Public meal imagery carries this notice: "Images are provided by the service provider for reference. Actual presentation may vary, while listed items and committed portions remain applicable."
- Supported MVP formats are JPEG, PNG and WebP. Maximum upload size is 5 MB. Food images should use 4:3 and profile images 1:1 where practical.

## 15. Minimum provider activation

- Optional enrichment must never delay a provider who is operationally ready.
- Activation requires provider/display and contact details, a basic business address, an authorized provider account, at least one approved serviceable pincode, and at least one active package.
- Every active package requires an approved current price, delivery timing, and an approved current Monday-Sunday menu for every meal slot that the package sells.
- Each required day/slot must contain at least one approved named food item. Fixed and changeable choices are recorded when the provider offers them.
- Only a Zomeal administrator may change a provider to `ACTIVE`, and activation is audited.
- Photos, descriptions, ingredients, nutrition, allergen declarations, kitchen information, extra customization, discounts and promotional content are optional profile enhancements.
- Optional information contributes to a separate profile-completion score and never changes activation readiness.
- Legal food-business documentation requirements remain subject to compliance review before production launch.
