package dev.difme.core.qry.api;

import dev.difme.core.common.Uuid7;
import dev.difme.core.common.projection.PaymentView;
import dev.difme.core.consistency.Consistency;
import dev.difme.core.consistency.ConsistencyProfile;
import dev.difme.core.qry.cache.CacheChain;
import dev.difme.core.qry.ledger.BalanceReader;
import dev.difme.core.qry.read.AccountView;
import dev.difme.core.qry.read.AccountViewMapper;
import dev.difme.core.qry.read.PaymentViewMapper;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Read endpoints per api/openapi (getPayment, getAccount, getBalance). */
@Path("/v1")
@RunOnVirtualThread
public class QueryResource {
    @Inject CacheChain cache;
    @Inject PaymentViewMapper payments;
    @Inject AccountViewMapper accounts;
    @Inject BalanceReader balances;

    @GET @Path("/payments/{id}")
    @ConsistencyProfile(Consistency.EVENTUAL)
    public Response payment(@PathParam("id") String id) {
        if (!Uuid7.isValid(id)) return Response.status(422).build();
        return cache.get("payment:" + id, PaymentView.class, () -> payments.findById(id))
                .map(v -> Response.ok(v).build()).orElse(Response.status(404).build());
    }

    @GET @Path("/payments")
    @ConsistencyProfile(Consistency.EVENTUAL)
    public List<PaymentView> list(@QueryParam("status") String status, @QueryParam("limit") Integer limit) {
        return payments.list(status, limit == null ? 50 : Math.min(limit, 500));
    }

    @GET @Path("/accounts/{id}")
    @ConsistencyProfile(Consistency.EVENTUAL)
    public Response account(@PathParam("id") String id) {
        if (!Uuid7.isValid(id)) return Response.status(422).build();
        return cache.get("account:" + id, AccountView.class, () -> accounts.findById(id))
                .map(v -> Response.ok(v).build()).orElse(Response.status(404).build());
    }

    @GET @Path("/accounts/{id}/balance")
    public Response balance(@PathParam("id") String id) {
        if (!Uuid7.isValid(id)) return Response.status(422).build();
        return balances.balance(UUID.fromString(id)).map(b -> Response.ok(b).build()).orElse(Response.status(404).build());
    }
}
