// Package acquirer is the card acquirer behind the PSP simulator (Hyperswitch). Hyperswitch's built-in test
// processor speaks a small JSON contract (POST /payment, GET /payments/{id}, POST /{id}/refund, GET /refunds/{id}) to
// a configurable base URL (connectors.dummyconnector.base_url). This package serves that contract and turns each call
// into ISO 8583 to the card network through the adapter, so PSP payments are approved or declined by the scheme's
// card ledger: PSP → acquirer → network → issuer, with no change to Hyperswitch.
package acquirer

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/adapter"
)

// Network is what the acquirer needs from the ISO adapter client.
type Network interface {
	Authorise(adapter.AuthRequest) (adapter.AuthResult, error)
	Refund(origRRN, pan string, amountMinor int64, currency, merchantID string) (adapter.AuthResult, error)
}

// ISO 4217 alpha → numeric for the currencies DIFME uses.
var numeric = map[string]string{"INR": "356", "USD": "840", "EUR": "978", "GBP": "826", "SGD": "702", "AED": "784"}

// Reasons for the response codes the network answers (shown by Hyperswitch as the error message).
var reason = map[string]string{
	"05": "Do not honour", "13": "Invalid amount", "14": "Invalid card number", "25": "Original transaction not found",
	"30": "Format error", "51": "Insufficient funds", "54": "Expired card", "62": "Restricted card", "91": "Issuer unavailable",
}

type payment struct {
	ID, RRN, pan, Currency, Created string
	AmountMinor, RefundedMinor      int64
}

type refund struct {
	ID, Currency, Created, Status string
	PaymentMinor, RefundMinor     int64
}

// Acquirer keeps the payments it acquired (in memory: a simulator; the network holds the authoritative card ledger).
type Acquirer struct {
	net      Network
	apiKey   string // when set, Hyperswitch's connector api-key header must match
	mu       sync.Mutex
	payments map[string]*payment
	refunds  map[string]*refund
	order    []string // payment ids, oldest first, for the size bound
	keep     int
}

func New(n Network, apiKey string) *Acquirer {
	return &Acquirer{net: n, apiKey: apiKey, payments: map[string]*payment{}, refunds: map[string]*refund{}, keep: 100_000}
}

type card struct {
	Number      string `json:"number"`
	ExpiryMonth string `json:"expiry_month"`
	ExpiryYear  string `json:"expiry_year"`
}

// Handler serves the processor contract under prefix (e.g. "/dummy-connector").
func (a *Acquirer) Handler(prefix string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST "+prefix+"/payment", a.auth(a.pay))
	mux.HandleFunc("GET "+prefix+"/payments/{id}", a.auth(a.getPayment))
	mux.HandleFunc("POST "+prefix+"/{id}/refund", a.auth(a.refund))
	mux.HandleFunc("GET "+prefix+"/refunds/{id}", a.auth(a.getRefund))
	return mux
}

func (a *Acquirer) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if a.apiKey != "" && subtle.ConstantTimeCompare([]byte(r.Header.Get("api-key")), []byte(a.apiKey)) != 1 {
			fail(w, 401, "IR_01", "Unauthorised", "api-key does not match")
			return
		}
		h(w, r)
	}
}

func (a *Acquirer) pay(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Amount            int64  `json:"amount"`
		Currency          string `json:"currency"`
		PaymentMethodData struct {
			Card *card `json:"card"`
		} `json:"payment_method_data"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil {
		fail(w, 400, "30", reason["30"], "body is not the processor's payment request")
		return
	}
	c := req.PaymentMethodData.Card
	cur, known := numeric[strings.ToUpper(req.Currency)]
	switch {
	case c == nil:
		fail(w, 400, "30", "Only card payments are acquired here", "")
		return
	case !known:
		fail(w, 400, "30", "Unsupported currency "+req.Currency, "")
		return
	case req.Amount <= 0:
		fail(w, 400, "13", reason["13"], "")
		return
	}
	year := c.ExpiryYear
	if len(year) == 4 {
		year = year[2:]
	}
	month := c.ExpiryMonth
	if len(month) == 1 {
		month = "0" + month
	}
	res, err := a.net.Authorise(adapter.AuthRequest{PAN: strings.ReplaceAll(c.Number, " ", ""), Expiry: year + month,
		AmountMinor: req.Amount, Currency: cur, MCC: "5999", MerchantID: "HYPERSWITCH", TerminalID: "PSP00001", Capture: true})
	if err != nil {
		fail(w, 502, "91", reason["91"], err.Error())
		return
	}
	if !res.Approved {
		fail(w, 400, res.ResponseCode, reason[res.ResponseCode], "declined by the card network (ISO 8583 field 39 = "+res.ResponseCode+")")
		return
	}
	p := &payment{ID: "acq_" + res.RRN, RRN: res.RRN, pan: strings.ReplaceAll(c.Number, " ", ""), Currency: strings.ToUpper(req.Currency),
		Created: time.Now().UTC().Format(time.RFC3339), AmountMinor: req.Amount}
	a.mu.Lock()
	a.payments[p.ID] = p
	a.order = append(a.order, p.ID)
	if len(a.order) > a.keep {
		delete(a.payments, a.order[0])
		a.order = a.order[1:]
	}
	a.mu.Unlock()
	writeJSON(w, 200, paymentBody(p))
}

func paymentBody(p *payment) map[string]any {
	return map[string]any{"status": "succeeded", "id": p.ID, "amount": p.AmountMinor, "currency": p.Currency, "created": p.Created,
		"payment_method_type": "card", "next_action": nil}
}

func (a *Acquirer) getPayment(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	p, ok := a.payments[r.PathValue("id")]
	a.mu.Unlock()
	if !ok {
		fail(w, 404, "25", reason["25"], "")
		return
	}
	writeJSON(w, 200, paymentBody(p))
}

func (a *Acquirer) refund(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Amount int64 `json:"amount"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req); err != nil || req.Amount <= 0 {
		fail(w, 400, "13", reason["13"], "a positive amount is required")
		return
	}
	a.mu.Lock()
	p, ok := a.payments[r.PathValue("id")]
	a.mu.Unlock()
	if !ok {
		fail(w, 404, "25", reason["25"], "")
		return
	}
	res, err := a.net.Refund(p.RRN, p.pan, req.Amount, numeric[p.Currency], "HYPERSWITCH")
	if err != nil {
		fail(w, 502, "91", reason["91"], err.Error())
		return
	}
	if !res.Approved {
		fail(w, 400, res.ResponseCode, reason[res.ResponseCode], "refund declined by the card network")
		return
	}
	rf := &refund{ID: "acr_" + res.RRN, Currency: p.Currency, Created: time.Now().UTC().Format(time.RFC3339), Status: "succeeded",
		PaymentMinor: p.AmountMinor, RefundMinor: req.Amount}
	a.mu.Lock()
	p.RefundedMinor += req.Amount
	a.refunds[rf.ID] = rf
	if len(a.refunds) > a.keep {
		a.refunds = map[string]*refund{rf.ID: rf}
	}
	a.mu.Unlock()
	writeJSON(w, 200, refundBody(rf))
}

func refundBody(rf *refund) map[string]any {
	return map[string]any{"id": rf.ID, "status": rf.Status, "currency": rf.Currency, "created": rf.Created,
		"payment_amount": rf.PaymentMinor, "refund_amount": rf.RefundMinor}
}

func (a *Acquirer) getRefund(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	rf, ok := a.refunds[r.PathValue("id")]
	a.mu.Unlock()
	if !ok {
		fail(w, 404, "25", reason["25"], "")
		return
	}
	writeJSON(w, 200, refundBody(rf))
}

func fail(w http.ResponseWriter, status int, code, message, why string) {
	body := map[string]any{"code": code, "message": message}
	if why != "" {
		body["reason"] = why
	}
	writeJSON(w, status, map[string]any{"error": body})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}
