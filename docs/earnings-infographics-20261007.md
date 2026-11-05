# Provider earnings infographic dashboard

Native green-theme cost ring, six-month earnings/cost bars, actual revenue and estimated profit cards. Month navigation reads the existing kitchen statement. Payouts, holds, advances and lifetime earnings remain available.

Reference refinement: deep-green gradient revenue hero, estimated margin and allocated-cost highlights, percentage cost tracks, and paired six-month columns with tappable month details. White cards and Zomeal greens are retained; no synthetic values or changed accounting rules.

The ring now uses costs to date, with fixed costs prorated by calendar days and staff-managed salary accrual supplied by the statement. Its coloured coverage is elapsed days divided by days in the month; within that coverage segments show cost shares. Grey is remaining time, not remaining budget. Commission is separately disclosed and already deducted from net earnings. Missing expenses/approved salaries can overstate profit; the UI warns about this.

The 100-customer preview is an in-memory October scenario with a day slider, 100 lunches at ₹65 and 100 dinners at ₹55 each day, 68% cooking costs including gas, saved local demo overheads and current provider commission. Unknown commission is explicitly labelled before-commission. No live writes. Payout/history controls remain live and are labelled as such. Animation is applied to the ring and revenue/deductions/result bars.

Current-month outlook scales calendar-day revenue and variable costs; adds full monthly overhead only once. Today is provisional. Projections are not guaranteed earnings or withdrawable balances. No demo data is mixed in.

Transaction history uses posting-month IST groups and provider-scoped keyset pagination (50 displayed rows; 51st is lookahead). Deploy migration 202610070003_provider_transaction_history.sql before using history. The existing statement migrations must also be deployed.

No production deployment or APK assembly performed. Validate layout on the device after building; test large font and empty/negative data.
