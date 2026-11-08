package com.example.agent.business.identity.adapter.persistence.mapper;

import com.example.agent.business.identity.adapter.persistence.model.LocalAccountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LocalAccountMapper {

    /**
     * 同时限定租户和登录名。
     * 未找到账号时返回 null。
     */
    LocalAccountRow findLoginAccount(
            @Param("tenantId") String tenantId,
            @Param("username") String username);

    /**
     * 汇总用户所属的已启用角色授予的权限。
     * 没有权限时返回空集合。
     */
    List<String> findPermissionCodes(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId);
}