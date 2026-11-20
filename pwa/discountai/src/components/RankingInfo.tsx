import { Info } from 'lucide-react';
import { useEngine } from '../sim/engine';
import { cx } from './ui';

/**
 * "How offers are ranked": the main ranking parameters and their relative weight, reachable from every page
 * that lists offers or bids (consumer law asks for this wherever results from several traders are ranked).
 */
export function RankingInfo({ className }: { className?: string }) {
  const { state } = useEngine();
  const w = state.config.weights;
  const pct = (n: number) => `${Math.round(n * 100)}%`;
  return (
    <details className={cx('rounded-lg border border-ink-200 bg-white px-3 py-2 text-[13px] text-ink-700', className)}>
      <summary className="cursor-pointer select-none font-medium text-ink-900 inline-flex items-center gap-1.5"><Info size={14} /> How offers are ranked</summary>
      <div className="mt-2 flex flex-col gap-2">
        <p><b>Store results</b> keep each store’s own relevance order for your search. Results are not sponsored: no store pays DiscountAI for placement.</p>
        <p><b>Buying partner bids</b> are ranked by one score: Score = w1 × (share of the discount left for you) + w2 × (buying partner reliability) − w3 × (response time). A lower yield ask leaves more of the discount for you, so it ranks higher.</p>
        <ul className="list-disc pl-5">
          <li>Card investors: w1 {pct(w.A.w1)} · w2 {pct(w.A.w2)} · w3 {pct(w.A.w3)}</li>
          <li>Token investors: w1 {pct(w.B.w1)} · w2 {pct(w.B.w2)} · w3 {pct(w.B.w3)}</li>
          <li>UPI investors: w1 {pct(w.C.w1)} · w2 {pct(w.C.w2)} · w3 {pct(w.C.w3)}</li>
        </ul>
        <p>Only buying partners who pass the eligibility rules can bid. Ties go to the lower yield ask, then the earlier bid. The second and third bids stay on as backups.</p>
      </div>
    </details>
  );
}
