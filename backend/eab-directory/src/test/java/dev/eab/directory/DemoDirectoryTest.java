package dev.eab.directory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DemoDirectoryTest {

    @Test
    @DisplayName("角色只授予 BO_OPERATIONS 里的操作，用户的角色都存在")
    void rolesAndUsersConsistent() {
        DemoDirectory.ROLES
                .values()
                .forEach(role -> assertThat(role.operations()).allMatch(DemoDirectory::isBoOperation));
        DemoDirectory.USERS.forEach(
                user -> assertThat(DemoDirectory.ROLES.keySet()).containsAll(user.roles()));
    }

    @Test
    @DisplayName("业务权限是各角色操作的并集；开发者没有任何业务权限")
    void permittedOperations() {
        var zhangmin = DemoDirectory.findUser("zhangmin").orElseThrow();
        assertThat(DemoDirectory.permittedOperations(zhangmin))
                .contains("purchase_order_change_approve", "followup_task_create")
                .doesNotContain("transaction_anomaly_list");
        assertThat(DemoDirectory.permittedOperations(
                        DemoDirectory.findUser("chengong").orElseThrow()))
                .isEmpty();
    }

    @Test
    @DisplayName("对外展示的用户信息带租户名和角色名")
    void publicUser() {
        var user = DemoDirectory.toPublicUser(DemoDirectory.findUser("wangfang").orElseThrow());
        assertThat(user.tenantName()).isEqualTo("星海制造");
        assertThat(user.role()).isEqualTo("财务经理");
        assertThat(DemoDirectory.toPublicUser(
                                DemoDirectory.findUser("vendordev").orElseThrow())
                        .tenantName())
                .isEqualTo("厂商");
    }
}
