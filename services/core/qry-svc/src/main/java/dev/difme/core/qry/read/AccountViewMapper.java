package dev.difme.core.qry.read;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountViewMapper {
    @Select("SELECT account_id::text AS account_id, holder_name, kyc_status, region FROM read.account_view WHERE account_id = CAST(#{id} AS uuid)")
    AccountView findById(@Param("id") String id);
}
