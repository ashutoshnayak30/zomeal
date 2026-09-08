// All values are integer paise. Keep captured components equal to actual cash,
// while retaining the full quoted plan separately for the outstanding balance.
// Temporary controlled-testing minimum. Restore to 50000 (₹500) before release.
const INITIAL_PLAN_TEST_MINIMUM_PAISE = 500;

export function advancePayment(planPackage, planFee, planDelivery, requested = undefined) {
  const total = planPackage + planFee + planDelivery;
  if (![planPackage, planFee, planDelivery, total].every(Number.isSafeInteger) || total <= 0 || Math.min(planPackage, planFee, planDelivery) < 0) throw new Error("Invalid plan price");
  const amount = requested === undefined ? Math.min(total, 1000000) : requested;
  if (!Number.isSafeInteger(amount) || amount < INITIAL_PLAN_TEST_MINIMUM_PAISE || amount > 1000000 || amount > total) {
    throw new Error("Pay between ₹5 and ₹10,000, without exceeding the plan total.");
  }
  const fee = Math.floor(planFee * amount / total);
  const delivery = Math.floor(planDelivery * amount / total);
  return { total, amount, fee, delivery, packageAmount: amount - fee - delivery, remaining: total - amount };
}
