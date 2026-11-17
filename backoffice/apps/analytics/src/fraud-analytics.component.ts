// PDF §5.9 Analytics UI: StarRocks OLAP fraud dashboards (via qry-svc) rendered with ECharts.
import { ChangeDetectionStrategy, Component, ElementRef, effect, input, signal, viewChild } from "@angular/core";
import * as echarts from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

type FraudDay = { day: string; alerts: number; hardBlocks: number; falsePositives: number; costPerTxnUsd: number };

@Component({
  selector: "difme-fraud-analytics",
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="wrap">
      <header>
        <h2>Fraud analytics</h2>
        @if (error()) { <p class="err">{{ error() }}</p> }
      </header>
      <div #chart class="chart"></div>
      <table>
        <thead><tr><th>Day</th><th>Alerts</th><th>Hard blocks</th><th>False positives</th><th>Cost / txn</th></tr></thead>
        <tbody>
          @for (d of days(); track d.day) {
            <tr><td>{{ d.day }}</td><td>{{ d.alerts }}</td><td>{{ d.hardBlocks }}</td><td>{{ d.falsePositives }}</td><td>{{ d.costPerTxnUsd.toFixed(5) }}</td></tr>
          }
        </tbody>
      </table>
    </section>
  `,
  styles: [`
    .wrap { font-family: var(--difme-font); color: var(--difme-color-text); }
    .chart { height: 320px; background: var(--difme-color-surface); border: 1px solid var(--difme-color-border); border-radius: var(--difme-radius); }
    table { width: 100%; margin-top: 16px; border-collapse: collapse; font-size: 13px; }
    th, td { text-align: right; padding: 6px; border-bottom: 1px solid var(--difme-color-border); }
    th:first-child, td:first-child { text-align: left; }
    .err { color: var(--difme-color-danger); }
  `],
})
export class FraudAnalyticsComponent {
  readonly token = input<string>("");
  readonly days = signal<FraudDay[]>([]);
  readonly error = signal<string | null>(null);
  private readonly chartEl = viewChild.required<ElementRef<HTMLDivElement>>("chart");
  private chart?: echarts.ECharts;

  constructor() {
    effect(() => {
      const t = this.token();
      fetch("/api/analytics/fraud-daily?days=30", { headers: t ? { Authorization: `Bearer ${t}` } : {} })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((d: FraudDay[]) => this.days.set(d))
        .catch((e: Error) => this.error.set(e.message));
    });
    effect(() => {
      const d = this.days();
      const el = this.chartEl().nativeElement;
      this.chart ??= echarts.init(el);
      this.chart.setOption({
        tooltip: { trigger: "axis" },
        legend: { data: ["Alerts", "Hard blocks", "False positives"] },
        xAxis: { type: "category", data: d.map((x) => x.day) },
        yAxis: { type: "value" },
        series: [
          { name: "Alerts", type: "bar", data: d.map((x) => x.alerts) },
          { name: "Hard blocks", type: "bar", data: d.map((x) => x.hardBlocks) },
          { name: "False positives", type: "line", data: d.map((x) => x.falsePositives) },
        ],
      });
    });
  }
}
