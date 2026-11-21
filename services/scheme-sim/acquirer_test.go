package schemesim_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/acquirer"
	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/adapter"
	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/scheme"
)

// Two cards share BIN and last four, so their masks are equal: a reversal must still release the right one.
func TestReversalFindsTheCardByFullPANNotItsMask(t *testing.T) {
	n, ad, _ := setup(t)
	future := time.Now().AddDate(2, 0, 0).Format("0601")
	for _, pan := range []string{"4375519999990001", "4375518888880001"} {
		if err := n.PutCard(scheme.Card{PAN: pan, Expiry: future, LimitMinor: 100_000}); err != nil {
			t.Fatal(err)
		}
	}
	a := auth(t, ad, "4375519999990001", future, 10_000)
	b := auth(t, ad, "4375518888880001", future, 20_000)
	if a["approved"] != true || b["approved"] != true {
		t.Fatalf("auths: %v %v", a, b)
	}
	if code, r := post(t, ad.URL+"/v1/reverse", map[string]any{"rrn": b["rrn"], "amountMinor": 20_000}); code != 200 || r["responseCode"] != "00" {
		t.Fatalf("reverse: %v", r)
	}
	ca, _ := n.Card("4375519999990001")
	cb, _ := n.Card("4375518888880001")
	if ca.OutstandingMinor != 10_000 || cb.OutstandingMinor != 0 {
		t.Fatalf("wrong card released: a=%d b=%d", ca.OutstandingMinor, cb.OutstandingMinor)
	}
}

func acq(t *testing.T, key string) (*scheme.Network, *httptest.Server) {
	t.Helper()
	n, _, _ := setup(t)
	l := startISO(t, n)
	srv := httptest.NewServer(acquirer.New(adapter.NewClient(l, 3*time.Second), key).Handler("/dummy-connector"))
	t.Cleanup(srv.Close)
	return n, srv
}

func call(t *testing.T, method, url, key string, body any) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(method, url, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if key != "" {
		req.Header.Set("api-key", key)
	}
	r, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(r.Body).Decode(&out)
	return r.StatusCode, out
}

func payBody(pan string, amount int64) map[string]any {
	y := time.Now().AddDate(2, 0, 0)
	return map[string]any{"amount": amount, "currency": "INR", "connector": "stripe_test", "return_url": nil,
		"payment_method_data": map[string]any{"card": map[string]any{"name": "T", "number": pan,
			"expiry_month": y.Format("01"), "expiry_year": y.Format("2006"), "cvc": "123"}}}
}

// The processor contract Hyperswitch's test connector speaks, answered by the network's card ledger.
func TestAcquirerServesThePSPProcessorContract(t *testing.T) {
	n, srv := acq(t, "k1")
	base := srv.URL + "/dummy-connector"
	if code, _ := call(t, "POST", base+"/payment", "wrong", payBody("4375510000000001", 1000)); code != 401 {
		t.Fatalf("wrong api-key accepted: %d", code)
	}
	code, p := call(t, "POST", base+"/payment", "k1", payBody("4375510000000001", 600_000))
	if code != 200 || p["status"] != "succeeded" || p["payment_method_type"] != "card" || p["currency"] != "INR" {
		t.Fatalf("payment: %d %v", code, p)
	}
	if c, _ := n.Card("4375510000000001"); c.OutstandingMinor != 600_000 {
		t.Fatalf("network hold: %d", c.OutstandingMinor)
	}
	// Over the remaining limit: the network's 51 reaches the PSP as the error code.
	code, d := call(t, "POST", base+"/payment", "k1", payBody("4375510000000001", 500_000))
	if e, _ := d["error"].(map[string]any); code != 400 || e["code"] != "51" || e["message"] != "Insufficient funds" {
		t.Fatalf("decline: %d %v", code, d)
	}
	id := p["id"].(string)
	if code, g := call(t, "GET", base+"/payments/"+id, "k1", nil); code != 200 || g["id"] != id {
		t.Fatalf("sync: %d %v", code, g)
	}
	// Partial refund, then more than what is left is refused (13), and the card's outstanding follows.
	code, r := call(t, "POST", base+"/"+id+"/refund", "k1", map[string]any{"amount": 200_000})
	if code != 200 || r["status"] != "succeeded" || r["refund_amount"] != float64(200_000) || r["payment_amount"] != float64(600_000) {
		t.Fatalf("refund: %d %v", code, r)
	}
	if c, _ := n.Card("4375510000000001"); c.OutstandingMinor != 400_000 {
		t.Fatalf("after refund: %d", c.OutstandingMinor)
	}
	code, over := call(t, "POST", base+"/"+id+"/refund", "k1", map[string]any{"amount": 400_001})
	if e, _ := over["error"].(map[string]any); code != 400 || e["code"] != "13" {
		t.Fatalf("over-refund: %d %v", code, over)
	}
	if code, rs := call(t, "GET", base+"/refunds/"+r["id"].(string), "k1", nil); code != 200 || rs["status"] != "succeeded" {
		t.Fatalf("refund sync: %d %v", code, rs)
	}
	if code, _ := call(t, "GET", base+"/payments/acq_nope", "k1", nil); code != 404 {
		t.Fatalf("unknown payment: %d", code)
	}
	if code, e := call(t, "POST", base+"/payment", "k1", map[string]any{"amount": 100, "currency": "XXX",
		"payment_method_data": payBody("4375510000000001", 1)["payment_method_data"]}); code != 400 {
		t.Fatalf("unknown currency: %d %v", code, e)
	}
}

// A reversal after a partial refund releases only what the refund didn't already give back.
func TestReversalAfterPartialRefundReleasesTheRest(t *testing.T) {
	n, srv := acq(t, "")
	base := srv.URL + "/dummy-connector"
	_, p := call(t, "POST", base+"/payment", "", payBody("4375510000000001", 300_000))
	call(t, "POST", base+"/"+p["id"].(string)+"/refund", "", map[string]any{"amount": 100_000})
	c := adapter.NewClient(isoAddr, 3*time.Second)
	if r, err := c.Reverse(p["id"].(string)[4:], 300_000); err != nil || r.ResponseCode != "00" {
		t.Fatalf("reverse: %v %v", r, err)
	}
	if card, _ := n.Card("4375510000000001"); card.OutstandingMinor != 0 {
		t.Fatalf("outstanding after reversal: %d", card.OutstandingMinor)
	}
}
