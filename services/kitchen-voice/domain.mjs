export class VoiceError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export const categories = ['Grains', 'Vegetables', 'Meat & eggs', 'Oil & spices', 'Dairy', 'Gas & fuel', 'Packaging', 'Delivery', 'Staff', 'Rent', 'Utilities', 'Cleaning', 'Other'];
export const modes = ['PURCHASE', 'OPENING_STOCK', 'CLOSING_STOCK', 'RECIPE'];
export const MAX_BODY = 2561000;
export function validateAudio(body) {
  if (!body || !modes.includes(body.mode) || !['auto', 'hi-IN', 'or-IN', 'en-IN', 'as-IN', 'bn-IN', 'gu-IN', 'kn-IN', 'ml-IN', 'mr-IN', 'ne-NP', 'pa-Guru-IN', 'sd-IN', 'ta-IN', 'te-IN', 'ur-PK'].includes(body.language)) throw new VoiceError(400, 'Choose a supported voice language and a valid entry type.');
  // Fixed PCM format lets the server derive duration, rather than trust a client duration.
  const encoded = body.audio;
  if (typeof encoded !== 'string' || encoded.length > 2560000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) throw new VoiceError(400, 'Invalid audio.');
  const pcm = Buffer.from(encoded, 'base64');
  if (pcm.toString('base64') !== encoded || pcm.length % 2 || pcm.length < 3200 || pcm.length > 1920000) throw new VoiceError(400, 'Record between 0.1 and 60 seconds.');
  return { mode: body.mode, language: body.language, audio: encoded, seconds: Math.ceil(pcm.length / 32000) };
}
function decimal(value, max, places) {
  if (value === null || value === undefined) return null;
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > max) throw new VoiceError(502, 'AI returned an invalid number. Please try again.');
  return Math.round(value * 10 ** places) / 10 ** places;
}
export function validateDraft(raw, mode) {
  if (!raw || !Array.isArray(raw.items) || raw.items.length > 30) throw new VoiceError(502, 'Could not interpret this recording.');
  const stock = mode !== 'PURCHASE';
  const items = raw.items.map(row => {
    if (!row || typeof row.item !== 'string' || !row.item.trim() || row.item.length > 100 || !categories.includes(row.category)) throw new VoiceError(502, 'Could not interpret an item.');
    const quantity = decimal(row.quantity, 100000, 3);
    const unitPrice = decimal(row.unit_price_rupees, 1000000, 2);
    const statedTotal = decimal(row.total_rupees, 1000000, 2);
    const unit = typeof row.unit === 'string' ? row.unit : '';
    if (!['', 'kg', 'g', 'litre', 'ml', 'piece', 'packet', 'bag', 'cylinder'].includes(unit)) throw new VoiceError(502, 'Unsupported quantity unit.');
    const computed = quantity !== null && unitPrice !== null && unit ? Math.round(quantity * unitPrice * 100) : null;
    const stated = statedTotal === null ? null : Math.round(statedTotal * 100);
    const conflict = computed !== null && stated !== null && Math.abs(computed - stated) > 1;
    const amount = stated ?? computed;
    if (amount !== null && amount > 100000000) throw new VoiceError(502, 'Item amount is too large.');
    const missing = [];
    if (quantity === null || !unit) missing.push('quantity / unit');
    if (amount === null) missing.push(stock ? 'purchase-cost valuation' : 'price');
    if (conflict) missing.push('total differs from quantity × unit price');
    if (!stock && (quantity === 0 || amount === 0)) missing.push('purchase must be positive');
    if (stock && !categories.slice(0, 5).includes(row.category)) missing.push('not an ingredient: exclude from stock');
    return { item: row.item.trim(), category: row.category, quantity, unit, unit_price_paise: unitPrice === null ? null : Math.round(unitPrice * 100), amount_paise: amount, issues: missing };
  });
  return { mode, items, requires_review: true, saved: false, warning: 'Check every item, unit and price. No accounts have been changed.' };
}
