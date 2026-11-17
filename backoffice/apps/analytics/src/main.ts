// Angular 22 remote shipped as the web component <difme-analytics> (ADR-010).
import { createApplication } from "@angular/platform-browser";
import { createCustomElement } from "@angular/elements";
import { provideZonelessChangeDetection } from "@angular/core";
import { FraudAnalyticsComponent } from "./fraud-analytics.component";

createApplication({ providers: [provideZonelessChangeDetection()] }).then((app) => {
  if (!customElements.get("difme-analytics")) {
    customElements.define("difme-analytics", createCustomElement(FraudAnalyticsComponent, { injector: app.injector }));
  }
});
