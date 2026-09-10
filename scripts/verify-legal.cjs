const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const root = path.join(__dirname, '..');
const source = JSON.parse(fs.readFileSync(path.join(root, 'legal/policies.json'), 'utf8'));
for (const module of ['app', 'provider-app']) {
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(root, module, 'src/main/assets/legal-policies.json'), 'utf8')), source);
}
for (const [id, policy] of Object.entries(source.policies)) {
  const html = fs.readFileSync(path.join(root, 'website', id + '.html'), 'utf8');
  assert(html.includes('support@zomeal.in'));
  assert(html.includes('legal.css'));
  assert(html.includes(source.updated));
  for (const [heading] of policy.sections) assert(html.includes(heading.replaceAll('&', '&amp;')));
  for (const match of html.matchAll(/href="([^"#:]+)"/g)) {
    assert(fs.existsSync(path.join(root, 'website', match[1])), match[1]);
  }
}
console.log('PASS: matching website and bundled app policies, dates, contacts and local links.');
