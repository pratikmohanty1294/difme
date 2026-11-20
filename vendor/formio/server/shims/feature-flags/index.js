'use strict';
// Open-source stand-in for @formio/feature-flags, which Form.io publishes under a proprietary subscription licence
// (DIFME's rule: open source only). Written from the package's public interface only. The formio server calls
// isFeatureEnabled(FEATURE_FLAGS.STRUCTURED_LOGGING, getConfig); other flags go through hook.alter().
// Every flag defaults to off; set its environment variable (named after the flag) to true or 1 to turn it on.
const flag = (key, description) => Object.freeze({ key, defaultValue: false, envVar: key, description });

const FEATURE_FLAGS = Object.freeze({
  BULK_S: flag('BULK_S', 'Bulk submission operations'),
  EXAMPLE: flag('EXAMPLE', 'Example flag'),
  ENCAPSULATED_EVAL: flag('ENCAPSULATED_EVAL', 'Encapsulated evaluation'),
  STRUCTURED_LOGGING: flag('STRUCTURED_LOGGING', 'Structured (JSON) logging'),
  NEXTGEN_VALIDATOR: flag('NEXTGEN_VALIDATOR', 'Next-generation validator'),
});

// getConfig(flag) returns a boolean, or null/undefined to fall back to the flag's default.
function isFeatureEnabled(flagDef, getConfig) {
  const value = typeof getConfig === 'function' ? getConfig(flagDef) : undefined;
  return value === null || value === undefined ? flagDef.defaultValue : Boolean(value);
}

function getFeatureFlags(getConfig) {
  const out = {};
  for (const f of Object.values(FEATURE_FLAGS)) out[f.key] = isFeatureEnabled(f, getConfig);
  return out;
}

module.exports = { FEATURE_FLAGS, isFeatureEnabled, getFeatureFlags };
