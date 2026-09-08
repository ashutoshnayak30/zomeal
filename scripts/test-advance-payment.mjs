import assert from 'node:assert/strict';
import { advancePayment } from '../supabase/functions/_shared/advance-payment.mjs';
import { hmacHex, safeHexEqual } from '../supabase/functions/_shared/razorpay-signature.mjs';
for (const total of [50000, 50001, 116575, 1000000, 1500000]) {
  for (const amount of [500, Math.min(total, 1000000)]) {
    const fee = Math.floor(total * .015), delivery = 2310;
    const result = advancePayment(total - fee - delivery, fee, delivery, amount);
    assert.equal(result.amount, amount);
    assert.equal(result.packageAmount + result.fee + result.delivery, amount);
    assert.equal(result.remaining, total - amount);
    assert.equal(result.total, total);
  }
}
for (const bad of [0, 499, 1000001, 500.5, NaN, Infinity, '500', null]) {
  assert.throws(() => advancePayment(1500000, 0, 0, bad));
}
assert.throws(() => advancePayment(499, 0, 0, 500));
assert.throws(() => advancePayment(600, 0, 0, 700));
assert.equal(advancePayment(2000000,0,0).amount,1000000);
const signature=await hmacHex('secret','raw-body');
assert.equal(signature,'c8824120b95d09519faa8a99c2e04cc055aef40e000e9317a29d93e9ec186d02');
assert.equal(safeHexEqual(signature,signature.toUpperCase()),true);
assert.equal(safeHexEqual(signature,signature.slice(1)),false);
console.log('PASS: exact paise allocation, advance limits, overpayment rejection and webhook HMAC checks');
