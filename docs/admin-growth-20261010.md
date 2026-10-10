# CEO growth dashboards

Overview and Business reports now share production-only animated trend cards, a revenue composition donut, signed financial comparison bars and an exact-value table. Existing provider performance, user management and finance links remain available.

Select a KPI to chart revenue, collections, commission, delivered value, provider earnings, platform fees or captured/failed payments. Group by calendar day, Monday-start week or month. Quick ranges: today, last seven days (including today), this month.

Growth compares the selected period to the immediately preceding equal number of days. Today is incomplete and explicitly marked provisional. First/last calendar buckets may be partial. Chart lines align bucket positions, not the same calendar dates. A zero prior value shows “No prior activity”, never infinite growth; negative prior values show an amount change rather than a misleading percentage. All money remains in paise until display. Missing comparison data does not become zero.

Financial meaning is inherited from `admin_business_dashboard`: collections are current CAPTURED payments, revenue is platform fees plus ledger commission, and ledger reversals follow the existing service-date attribution. These are not immutable historical snapshots; late updates can change historical results. Revenue is not company profit. Current active-provider/customer counts are not historical app-usage metrics. Donuts are omitted for negative or all-zero components. Categories in magnitude bars overlap and must not be summed. No demo or forecast values added.

No new database migration or API permissions are required. Requires the existing admin-management deployment for Business reports. Reduced-motion preferences disable animations. Exact chart data is available in an expandable table; charts are responsive and keyboard controls have focus states.

## Checks (repo root)

```cmd
node scripts\test-admin-growth.cjs
node scripts\test-admin-workspace-ui.cjs
node --check admin\growth.js
node --check admin\app.js
node --check admin\workspace.js
```

## Deploy (Windows CMD)

```cmd
cd /d C:\Users\HP\Documents\Codex\2026-08-09\build\zomeal
git add admin/index.html admin/app.js admin/workspace.js admin/growth.js admin/growth.css scripts/test-admin-growth.cjs docs/admin-growth-20261010.md
git diff --cached --stat
git commit -m "Add CEO growth dashboards and animated reporting charts"
git push origin main
```

If admin hosting auto-deploys main, wait for that deployment then hard-refresh admin with Ctrl+F5. Otherwise deploy the static admin directory using the existing hosting workflow. No APK rebuild required.
