package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

/**
 * Applies the trusted tenant context to every pooled connection borrow.
 * PostgreSQL RLS is intentionally deny-by-default when no context exists.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public final class TenantRlsDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)
                || bean instanceof TenantRlsDataSource) {
            return bean;
        }
        return new TenantRlsDataSource(dataSource);
    }

    private static final class TenantRlsDataSource extends DelegatingDataSource {

        private TenantRlsDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return configure(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return configure(super.getConnection(username, password));
        }

        private Connection configure(Connection connection) throws SQLException {
            String tenantId = "";
            String tenantCode = "";
            if (!TrustedDataAccessContext.isSystemWorker()) {
                TenantContext.TenantInfo tenant = currentTenant();
                if (tenant != null) {
                    tenantId = tenant.tenantId();
                    tenantCode = tenant.tenantCode();
                }
            }
            boolean platformAdmin = !TrustedDataAccessContext.isSystemWorker()
                    && !TrustedDataAccessContext.isAuthentication()
                    && CurrentPrincipalContext.currentOrNull() != null
                    && CurrentPrincipalContext.currentOrNull().roles().contains("PLATFORM_ADMIN");
            boolean systemWorker = TrustedDataAccessContext.isSystemWorker();
            boolean authentication = TrustedDataAccessContext.isAuthentication();
            try (PreparedStatement statement = connection.prepareStatement(
                            "select set_config('app.tenant_id', ?, false), "
                            + "set_config('app.tenant_code', ?, false), "
                            + "set_config('app.platform_admin', ?, false), "
                            + "set_config('app.system_worker', ?, false), "
                            + "set_config('app.authentication', ?, false)")) {
                statement.setString(1, tenantId);
                statement.setString(2, tenantCode);
                statement.setString(3, Boolean.toString(platformAdmin));
                statement.setString(4, Boolean.toString(systemWorker));
                statement.setString(5, Boolean.toString(authentication));
                statement.execute();
            }
            return proxy(connection);
        }

        private static TenantContext.TenantInfo currentTenant() {
            try {
                return TenantContext.current();
            } catch (IllegalStateException exception) {
                return null;
            }
        }

        private static Connection proxy(Connection delegate) {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("close".equals(method.getName())) {
                    try (PreparedStatement statement = delegate.prepareStatement(
                            "select set_config('app.tenant_id', '', false), "
                            + "set_config('app.tenant_code', '', false), "
                            + "set_config('app.platform_admin', 'false', false), "
                            + "set_config('app.system_worker', 'false', false), "
                            + "set_config('app.authentication', 'false', false)")) {
                        statement.execute();
                    } finally {
                        delegate.close();
                    }
                    return null;
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }
    }
}
