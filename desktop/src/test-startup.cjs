'use strict';
// Only the explicit source-test entry can initialize an empty directory automatically.
// Packaged releases and normal source launches use the two-step creation wizard.
function testStartupOptions({ packaged, requested, explicitData, explicitProfile, initialized, hasBinding, hasUpgradeJournal, nonempty }) {
  if (packaged || !requested || !explicitData || !explicitProfile) return null;
  return { initialize: !initialized && !hasBinding && !hasUpgradeJournal && !nonempty, upgrade: false };
}
module.exports = { testStartupOptions };
