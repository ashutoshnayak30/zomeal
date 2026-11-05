# BIKI ingredient requirements and shopping planner

Entry: BIKI > Ingredients & shopping planner.

Reads the authenticated recipe book and inventory review RPCs. All-dish coverage requires migration 202611040003_recipe_all_dishes (deployed). No Cloud Run update or additional AI calls required.

Choose today through seven days ahead and lunch/dinner/both. Upcoming main courses and all saved side-dish snapshots scale linked recipes to their serving counts. Like-named ingredients aggregate across recipes after kg/g or litre/ml conversion. Both meals aggregate before subtracting available stock once. Whole-piece purchases round upward. One recipe serving is one person's dish serving, e.g. two rotis; batch ingredients for 18 rotis serving nine people use batch=9.

Last physical counts show their own date, slot, days elapsed and delivered meals since. Existing generic per-meal estimates are explicitly unconfirmed references, never silently deducted. Owner can copy a physical count, update it, and confirm available stock reserved for this plan. Unknown stock remains unknown. No mass-volume or cross-language alias guesses are made. Different item names need physical checking; ingredient naming should be consistent.

Reviewed shortages form the shopping list. Confirmations are ephemeral and clear on selection changes, refresh or close; this is not a reservation system. The stock-report and recipe buttons open existing cloud-save screens and refresh after closing.

Prelaunch preview mode scales one saved recipe to 1–10,000 portions, labelled PREVIEW throughout the dish list. It never creates orders or ledger entries.

All active approved package menu items are selectable without a category restriction. Plan requirements use each meal's saved sides, not today's menu. Repeated dish IDs within a meal count once; guest meal rows count separately. Missing recipes or absent side snapshots appear as incomplete-plan warnings, never fabricated ingredient amounts. Meals and dish servings have separate counts. Database rollback tests cover rice/roti/dal, deduplication, missing snapshots and authenticated book response.

Limits: missing recipes cannot produce ingredient quantities. Staff portions and waste need separate allowance. No automatic purchases, financial postings, stock deductions, persistent shopping lists, alias learning or recipe-based historical consumption inference. Those require separately verified workflows. Smartphone layout requires device testing.

Tests cover aggregation, unit families, unconfirmed versus explicit-zero stock, surplus, deficits, whole-egg rounding, cross-language isolation and invalid quantities, in addition to recipe pricing tests.
