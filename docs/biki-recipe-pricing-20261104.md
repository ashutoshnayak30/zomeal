# Recipe pricing and package-only selection

Migration `202611040002_recipe_price_basis.sql` deployed to project tojgwcxfvicrenfabgml.

- Recipe name is canonical from a searchable menu selection. Options require an approved menu item in an available day of a currently effective approved menu linked to an active package owned by the kitchen.
- Save enforces the same restriction on the server. Private legacy functions retain ownership checks, optimistic locking, auditing and idempotency but cannot be called by authenticated or anonymous API clients.
- Ingredient quantity and pricing basis are independent: kg, litre, piece, packet (explicit size/unit), or full batch ingredient cost.
- Backend recomputes normalized legacy `price`; it does not trust client normalized values. New fields retain the owner's entered price and basis.
- Old recipe prices are not guessed or silently converted. Owner must re-enter and confirm the price basis once. Install the new APK before editing recipes: old versions cannot save without the new fields.
- Target requirement = batch quantity × target portions / confirmed batch portions. Target cost = batch ingredient cost × target portions / batch portions. Keep 12-decimal arithmetic until final monetary rounding; display quantities with three decimals and promote grams/ml to kg/litre when appropriate.
- No mass-to-volume inference (e.g. oil g to litres). Missing price remains unknown. Plans do not create expenses or stock deductions.

Verification: 10 Kotlin unit tests; debug APK assembled. SQL rollback test verified active package menu availability, canonical name, onion ₹20/kg → ₹0.02/g, and rejection of unlinked recipes. Deployment verified legacy and anonymous execute permissions are denied. No real-phone layout test was available.

Example: 250 g onion for 9 people at ₹20/kg is ₹5 per batch. For 100 people: 2.778 kg displayed, ₹55.56 cost. One kg chicken for 9 people scales to 11.111 kg for 100; that quantity scaling is intentional, not a pricing-unit conversion.
