package schemesim_test

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/adapter"
	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/scheme"
	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/server"
)

// Starts the network on a random port and returns the adapter's HTTP API pointing at it.
func setup(t *testing.T) (*scheme.Network, *httptest.Server, *httptest.Server) {
	t.Helper()
	n := scheme.New()
	future := time.Now().AddDate(2, 0, 0).Format("0601")
	past := time.Now().AddDate(0, -2, 0).Format("0601")
	for _, c := range []scheme.Card{
		{PAN: "4375510000000001", Expiry: future, LimitMinor: 1_000_000},                     // Bank X, ₹10,000 limit
		{PAN: "4375510000000002", Expiry: past, LimitMinor: 1_000_000},                       // expired
		{PAN: "4375510000000003", Expiry: future, LimitMinor: 1_000_000, Status: "BLOCKED"}, // blocked
		{PAN: "5200000000000001", Expiry: future, LimitMinor: 1_000_000},                     // another issuer
	} {
		if err := n.PutCard(c); err != nil {
			t.Fatal(err)
		}
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go server.ServeISO(l, n)
	ad := httptest.NewServer(adapter.HTTP(adapter.NewClient(l.Addr().String(), 3*time.Second)))
	admin := httptest.NewServer(server.Admin(n))
	t.Cleanup(ad.Close)
	t.Cleanup(admin.Close)
	return n, ad, admin
}

func post(t *testing.T, url string, body any) (int, map[string]any) {
	t.Helper()
	b, _ := json.Marshal(body)
	r, err := http.Post(url, "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var out map[string]any
	_ = json.NewDecoder(r.Body).Decode(&out)
	return r.StatusCode, out
}

func auth(t *testing.T, ad *httptest.Server, pan, expiry string, amount int64) map[string]any {
	t.Helper()
	code, out := post(t, ad.URL+"/v1/authorize", map[string]any{"pan": pan, "expiry": expiry, "amountMinor": amount, "mcc": "5732", "merchantId": "M1"})
	if code != 200 {
		t.Fatalf("authorize: HTTP %d %v", code, out)
	}
	return out
}

func TestAuthorisationOutcomesOverISO8583(t *testing.T) {
	n, ad, _ := setup(t)
	future := time.Now().AddDate(2, 0, 0).Format("0601")
	ok := auth(t, ad, "4375510000000001", future, 800_000) // ₹8,000 of ₹10,000
	if ok["responseCode"] != "00" || ok["approved"] != true || len(ok["authCode"].(string)) != 6 || len(ok["rrn"].(string)) != 12 {
		t.Fatalf("approval: %v", ok)
	}
	if c, _ := n.Card("4375510000000001"); c.OutstandingMinor != 800_000 {
		t.Fatalf("hold not placed: %+v", c)
	}
	if r := auth(t, ad, "4375510000000001", future, 300_000); r["responseCode"] != "51" { // ₹2,000 left
		t.Fatalf("want 51 insufficient funds, got %v", r)
	}
	if r := auth(t, ad, "4375510000000002", time.Now().AddDate(0, -2, 0).Format("0601"), 1000); r["responseCode"] != "54" {
		t.Fatalf("want 54 expired, got %v", r)
	}
	if r := auth(t, ad, "4375510000000003", future, 1000); r["responseCode"] != "05" {
		t.Fatalf("want 05 blocked, got %v", r)
	}
	if r := auth(t, ad, "4999990000000009", future, 1000); r["responseCode"] != "14" {
		t.Fatalf("want 14 unknown card, got %v", r)
	}
	if r := auth(t, ad, "4375510000000001", "2001", 1000); r["responseCode"] != "54" {
		t.Fatalf("want 54 for a wrong expiry, got %v", r)
	}
	// Reversal releases the hold; a second reversal is idempotent; an unknown RRN is 25.
	code, rev := post(t, ad.URL+"/v1/reverse", map[string]any{"rrn": ok["rrn"], "amountMinor": 800_000})
	if code != 200 || rev["responseCode"] != "00" {
		t.Fatalf("reversal: %d %v", code, rev)
	}
	if c, _ := n.Card("4375510000000001"); c.OutstandingMinor != 0 {
		t.Fatalf("hold not released: %+v", c)
	}
	if _, again := post(t, ad.URL+"/v1/reverse", map[string]any{"rrn": ok["rrn"], "amountMinor": 800_000}); again["responseCode"] != "00" {
		t.Fatalf("second reversal: %v", again)
	}
	if _, unk := post(t, ad.URL+"/v1/reverse", map[string]any{"rrn": "000000000000", "amountMinor": 1}); unk["responseCode"] != "25" {
		t.Fatalf("unknown reversal: %v", unk)
	}
}

func TestIssuerOutageAndAdminAPI(t *testing.T) {
	n, ad, admin := setup(t)
	future := time.Now().AddDate(2, 0, 0).Format("0601")
	req, _ := http.NewRequest(http.MethodPut, admin.URL+"/v1/issuers/437551", bytes.NewReader([]byte(`{"down": true}`)))
	if r, err := http.DefaultClient.Do(req); err != nil || r.StatusCode != 200 {
		t.Fatalf("set issuer down: %v %v", r, err)
	}
	if r := auth(t, ad, "4375510000000001", future, 1000); r["responseCode"] != "91" {
		t.Fatalf("want 91 issuer down, got %v", r)
	}
	if r := auth(t, ad, "5200000000000001", future, 1000); r["responseCode"] != "00" { // other issuers unaffected
		t.Fatalf("other issuer: %v", r)
	}
	n.SetIssuerDown("437551", false)
	// Admin: a new card, masked in answers; transactions masked too.
	code, c := post(t, admin.URL+"/v1/cards", map[string]any{"pan": "6070000000000001", "expiry": future, "limitMinor": 50_000})
	if code != 201 || c["pan"] != "607000******0001" {
		t.Fatalf("card create: %d %v", code, c)
	}
	if code, bad := post(t, admin.URL+"/v1/cards", map[string]any{"pan": "12ab", "expiry": future}); code != 422 {
		t.Fatalf("bad card accepted: %d %v", code, bad)
	}
	r, _ := http.Get(admin.URL + "/v1/transactions?limit=5")
	var tx struct{ Transactions []scheme.Txn }
	_ = json.NewDecoder(r.Body).Decode(&tx)
	if len(tx.Transactions) == 0 || tx.Transactions[0].PAN != "520000******0001" {
		t.Fatalf("transactions: %+v", tx.Transactions)
	}
}

// Concurrent requests share one link; responses are matched by STAN, and holds add up exactly.
func TestConcurrentAuthorisationsShareOneLink(t *testing.T) {
	n, ad, _ := setup(t)
	future := time.Now().AddDate(2, 0, 0).Format("0601")
	var wg sync.WaitGroup
	approved := make(chan bool, 50)
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			approved <- auth(t, ad, "5200000000000001", future, 10_000)["approved"] == true
		}()
	}
	wg.Wait()
	close(approved)
	count := 0
	for a := range approved {
		if a {
			count++
		}
	}
	if c, _ := n.Card("5200000000000001"); count != 50 || c.OutstandingMinor != 500_000 {
		t.Fatalf("approved %d, outstanding %d", count, c.OutstandingMinor)
	}
}

func TestEchoHealth(t *testing.T) {
	_, ad, _ := setup(t)
	r, err := http.Get(ad.URL + "/healthz")
	if err != nil || r.StatusCode != 200 {
		t.Fatalf("echo: %v %v", r, err)
	}
}
