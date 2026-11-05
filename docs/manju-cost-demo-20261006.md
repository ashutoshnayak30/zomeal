# Manju Kitchen cost demo

The provider Costs screen offers a separate local demo for authenticated profile provider ID `9726ff16-eead-4e63-ba58-e797e8cb97ed`. It requires the profile RPC to succeed. No SQL deployment or production seed is needed.

Install the new provider APK, sign in to Manju Kitchen, open Costs, then **Open demo & monthly projection**.

- Fixed dates: 1–6 October 2026.
- Per day: 100 lunches at ₹65 and 100 dinners at ₹55, gross ₹12,000.
- Nine reproducibly varied cooking-cost rows total ₹8,160 each day (68%), including gas.
- Six days: ₹72,000 sales and ₹48,960 cooking costs.
- Monthly inputs: rent, electricity/water, salaries, other overhead. Blank inputs count as zero and are explicitly labelled incomplete assumptions.
- Commission is optional, explicitly entered as a demo percentage. No current commercial rate is inferred or changed. If blank, every profit result is before commission.
- Monthly projection assumes 200 meals/day for all 31 days: ₹372,000 revenue, ₹252,960 cooking costs, leaving ₹119,040 before commission and monthly overhead. It is not earned money.
- Overhead for six days is monthly amount × 6 / 31, rounded once to paise. Full October deducts full monthly overhead.
- Inputs can be saved locally to the Manju-specific demo preferences on the phone. They do not sync or create actual expenses.
- The demo screen has no repository mutation, real meal creation, wallet debit, provider-ledger insertion or payout path. Closing it returns to unchanged live accounts.

Verification: `KitchenDemoMathTest.kt` tests six-day totals, 68% daily row totals, daily variation, commission validation, overhead, projected profit and projected loss. Android compilation is checked with `:provider-app:assembleDebug`. Phone visual QA is separate.
