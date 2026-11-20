// Trader identity and consumer-facing legal details, shown in the footer and at checkout
// (consumer law asks for the trader's identity, geographical address and a way to reach a person).
// Fill the empty fields with DIFME's real details; empty fields are simply not shown.
export const TRADER = {
  brand: 'DiscountAI by DIFME',
  legalName: '',      // registered company name, e.g. "<Company> Private Limited"
  address: '',        // registered geographical address
  supportEmail: '',   // monitored by a person (human customer service)
  supportPhone: '',   // optional
  website: 'https://difme.example',
};

/** DiscountAI's platform fee, as a share of the list price (₹200 on ₹10,000). */
export const PLATFORM_FEE_PCT = 2;

export const traderName = () => TRADER.legalName || TRADER.brand;
export const hasSupport = () => Boolean(TRADER.supportEmail || TRADER.supportPhone);
