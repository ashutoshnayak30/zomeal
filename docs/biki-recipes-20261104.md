# BIKI recipe notebook — first version

Entry: BIKI > Teach BIKI > My recipes. Batch portions and raw ingredients are confirmed by the owner. Planning scales each ingredient by target / batch, using six decimal places internally. Costs use manually entered purchase prices in the same unit; missing prices produce a partial subtotal. No ledger or inventory writes.

Recipes persist in app-private preferences per login on this device, not Supabase. They are not automatically matched to menus. Choose the saved dish and enter its required portions manually. Recipe quantities are linear estimates; large-batch seasoning and whole-piece purchasing require owner review. No automatic learning or price lookup is claimed.

Voice RECIPE mode requires the backend patch `biki-recipe-v8.zip`. Upload the zip to Cloud Shell, extract into the existing backend source directory, run node tests and deploy the source using the current runtime service account and existing environment. No SQL migration required. Until deployment, use manual rows; the old backend rejects RECIPE mode safely.

Voice extracts ingredient quantities only; the owner explicitly enters and confirms serving count. Prices are entered separately. No human speech accuracy or on-device UI test has been performed.

Example: 9-portion chicken batch scales to 120 portions at 13.333333x. 1kg chicken becomes 13.333333kg; 200ml oil becomes 2666.666667ml; 500g onion becomes 6666.666667g. Do not interpret these recipe estimates as observed stock consumption.
