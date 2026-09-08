# Zomeal customer journey production test

This sheet is evidence for a real, unassisted pilot. Do not mark a row complete
unless the participant performed the journey on their own device without hints.
Use participant codes rather than names or full phone numbers.

## Build under test

- Git commit: ____________________
- APK SHA-256: ____________________
- Supabase project/environment: ____________________
- Razorpay mode: LIVE / TEST (circle one)
- Test date: ____________________
- Observer: ____________________

## Required screen-size coverage

Use at least one device from each group. Record Android display size and the
device's font-size setting; do not infer the size from the handset model.

| Group | Target viewport | Font scale | Participant/device | Result |
|---|---:|---:|---|---|
| Small phone | about 360 × 640 dp | 1.0× | | Not run |
| Standard phone | about 390 × 844 dp | 1.0× | | Not run |
| Large/accessibility | at least 600 × 960 dp | 1.3× or larger | | Not run |

## Unassisted task

Read only this sentence to the participant: “Register for Zomeal, choose a real
provider and meal package, set your weekly menu and address, choose a start date,
and make the ₹5 test payment.”

Do not explain controls, point at the screen, correct entries, or rescue the
participant. If they ask for help, record the exact question and mark the task as
assisted. Never collect an OTP, UPI PIN, full phone number, or other secret in this
sheet.

## Journey checks

For every participant, verify:

1. Registration validates the phone and pincode and OTP entry is understandable.
2. Only approved, production-quality providers appear; provider and food photos load.
3. Weekly/monthly and lunch/dinner/both choices are understandable without help.
4. Cards do not clip or overlap at the participant's font size.
5. Address starts blank except for the registration pincode.
6. The screen distinguishes full plan price, advance paid now, outstanding plan
   balance, and spendable meal-wallet balance.
7. Razorpay reports a captured ₹5 payment before Zomeal reports success.
8. The active plan, chosen start date, payment reference and wallet ledger agree
   after reopening the app.

## Five-participant observation log

| Participant | Device / viewport / font scale | Completed unassisted? | Time | ₹5 captured? | Payment reference (last 6 only) | Errors, hesitations and exact questions |
|---|---|---|---:|---|---|---|
| P01 | | Not run | | | | |
| P02 | | Not run | | | | | |
| P03 | | Not run | | | | | |
| P04 | | Not run | | | | | |
| P05 | | Not run | | | | | |

## Release gate

Do not raise the temporary ₹5 minimum back to ₹500 until all of these are true:

- five of five participants finish registration without assistance;
- at least four of five finish provider/package/menu selection without assistance;
- all five can correctly explain plan price, advance paid, amount still due and
  wallet balance;
- all attempted ₹5 payments have one captured Razorpay payment and exactly one
  matching Zomeal payment record;
- no clipped, overlapping or unreachable control appears on the three required
  screen configurations;
- every high-severity failure is fixed and retested.

If a payment is ambiguous, duplicated or shown as successful before capture,
stop the pilot and reconcile Razorpay, Supabase payment records and the wallet
ledger before testing another participant.
