package com.example.agent.business.logistics.adapter.persistence.mapper;

import com.example.agent.business.logistics.adapter.persistence.model.LogisticsQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LogisticsQueryMapper {

    LogisticsQueryRow findOwnedLogistics(
            @Param("tenantId") String tenantId,
            @Param("ownerUserId") String ownerUserId,
            @Param("trackingNo") String trackingNo);
}
