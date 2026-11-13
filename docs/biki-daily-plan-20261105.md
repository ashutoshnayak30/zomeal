# BIKI daily kitchen plan

BIKI > Today's kitchen plan · What to buy opens today by default. Prepare shows selected upcoming meals, linked dish portions, missing recipes, an ingredient spreadsheet and an estimated ingredient subtotal. What to buy retains physical-stock confirmation and shows shortages and estimated purchase amounts. Both tabs share one plan; changing tabs does not reset confirmations. Date/slot changes and refresh do reset them.

Requirements use the existing authenticated recipe book, including all side snapshots when migration 202611040003 is installed. This change does not deploy or verify that migration. No new database migration or voice backend deployment is required for this UI update.

Costs use reviewed recipe price-basis fields (kg/litre/piece/packet/batch), scaled by recipe batch servings. Missing or invalid prices stay unknown. Ingredient aggregation sums costs only when every contributing row is priced. Shopping estimates apply the resulting recipe-weighted price to confirmed shortages. These are not supplier quotes; packet rounding is not inferred. Whole pieces round upward before pricing. Purchase costs are separate from preparation ingredient estimates and neither posts to the ledger.

Acceptance checks: today and both slots; switch lunch/dinner; rice/roti and guest dish coverage from server; missing recipes; blank price; 250 g onion at ₹20/kg; mixed kg/g requirements; unknown stock versus confirmed zero; surplus; fractional eggs; outdated physical counts; refresh clearing confirmations; preview remaining isolated. Test small phone navigation and keyboard insets on device.

This is the preparation/shopping increment only. Closing-stock reconciliation, recipe-based historical consumption, missed-report recovery, persistent reservations and financial summary integration are not newly implemented here. Existing release AABs predate this change and must be rebuilt to include it.
