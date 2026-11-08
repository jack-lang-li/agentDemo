package com.example.agent.business.identity.adapter.persistence;

import com.example.agent.business.identity.adapter.persistence.mapper.LocalAccountMapper;
import com.example.agent.business.identity.adapter.persistence.model.LocalAccountRow;
import com.example.agent.business.identity.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 数据库账号认证适配器。
 *
 * 普通运行使用数据库来源。
 * dev-memory 仅用于显式选择内存开发账号。
 */
@Service
@Profile("!dev-memory")
public class DatabaseUserDetailsService implements UserDetailsService {

    private final LocalAccountMapper accountMapper;
    private final String tenantId;

    public DatabaseUserDetailsService(
            LocalAccountMapper accountMapper,
            @Value("${app.security.tenant-id}") String tenantId) {

        if (tenantId == null
                || tenantId.isBlank()
                || !tenantId.equals(tenantId.strip())) {
            throw new IllegalArgumentException(
                    "部署租户ID不能为空或包含首尾空白");
        }

        this.accountMapper = accountMapper;
        this.tenantId = tenantId;
    }

    /**
     * 在同一个只读事务中读取账号和权限快照。
     * 密码比较仍由 DaoAuthenticationProvider 完成。
     */
    @Override
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("账号不存在");
        }

        LocalAccountRow account =
                accountMapper.findLoginAccount(tenantId, username);

        if (account == null) {
            throw new UsernameNotFoundException("账号不存在");
        }

        // 数据库查询与部署租户必须一致，异常时明确失败。
        if (!tenantId.equals(account.getTenantId())) {
            throw new InternalAuthenticationServiceException(
                    "账号租户与部署配置不一致");
        }

        boolean enabled =
                Integer.valueOf(1).equals(account.getUserEnabled())
                && Integer.valueOf(1).equals(account.getAccountEnabled());

        List<String> permissionCodes = enabled
                ? accountMapper.findPermissionCodes(
                        tenantId, account.getUserId())
                : List.of();

        // 数据库里已经是密码哈希，直接交给认证框架，
        // 不对已保存的哈希再次执行 encode。
        return new AuthenticatedUser(
                account.getTenantId(),
                account.getUserId(),
                account.getUsername(),
                account.getPasswordHash(),
                permissionCodes,
                enabled
        );
    }
}