package dev.difme.core.qry.read;

import dev.difme.core.common.projection.PaymentView;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** MyBatis hand-written projections (PDF §5.4) — no ORM persistence context, no N+1. */
@Mapper
public interface PaymentViewMapper {
    @Select("""
            SELECT payment_id::text AS payment_id, status, amount_minor, currency, channel, workflow_id,
                   debit_account_id::text AS debit_account_id, credit_account_id::text AS credit_account_id,
                   version, updated_at
              FROM read.payment_view WHERE payment_id = CAST(#{id} AS uuid)
            """)
    PaymentView findById(@Param("id") String id);

    @Select("""
            <script>
            SELECT payment_id::text AS payment_id, status, amount_minor, currency, channel, workflow_id,
                   debit_account_id::text AS debit_account_id, credit_account_id::text AS credit_account_id,
                   version, updated_at
              FROM read.payment_view
              <where><if test="status != null">status = #{status}</if></where>
             ORDER BY payment_id DESC LIMIT #{limit}
            </script>
            """)
    List<PaymentView> list(@Param("status") String status, @Param("limit") int limit);
}
