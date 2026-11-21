// iso-adapter: JSON over HTTP (:8080) in, ISO 8583 to the card network (NETWORK_ADDR, default the scheme simulator) out.
// It also serves the acquirer for the PSP simulator under /dummy-connector (Hyperswitch's test-processor contract);
// ACQUIRER_API_KEY, when set, is the api-key Hyperswitch must send.
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/acquirer"
	"github.com/pratikmohanty1294/difme/services/scheme-sim/internal/adapter"
)

func main() {
	addr := os.Getenv("NETWORK_ADDR")
	if addr == "" {
		addr = "scheme-sim.rails.svc:8583"
	}
	listen := os.Getenv("HTTP_LISTEN")
	if listen == "" {
		listen = ":8080"
	}
	c := adapter.NewClient(addr, 5*time.Second)
	mux := http.NewServeMux()
	mux.Handle("/dummy-connector/", acquirer.New(c, os.Getenv("ACQUIRER_API_KEY")).Handler("/dummy-connector"))
	mux.Handle("/", adapter.HTTP(c))
	srv := &http.Server{Addr: listen, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	log.Printf("iso-adapter: HTTP on %s, network %s", listen, addr)
	log.Fatal(srv.ListenAndServe())
}
