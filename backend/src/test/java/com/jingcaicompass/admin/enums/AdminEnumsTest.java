package com.jingcaicompass.admin.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminEnumsTest {

    @Test
    void resolvesPersistedAdministratorCodes() {
        assertThat(AdminRoleEnum.fromCode("ADMIN")).isEqualTo(AdminRoleEnum.ADMIN);
        assertThat(AdminAccountStatusEnum.fromCode("ACTIVE"))
                .isEqualTo(AdminAccountStatusEnum.ACTIVE);
        assertThat(AdminAccountStatusEnum.fromCode("DISABLED"))
                .isEqualTo(AdminAccountStatusEnum.DISABLED);
        assertThat(AdminLoginFailureReasonEnum.fromCode("INVALID_PASSWORD"))
                .isEqualTo(AdminLoginFailureReasonEnum.INVALID_PASSWORD);
    }

    @Test
    void returnsNullForUnknownCodes() {
        assertThat(AdminRoleEnum.fromCode("USER")).isNull();
        assertThat(AdminAccountStatusEnum.fromCode("UNKNOWN")).isNull();
        assertThat(AdminLoginFailureReasonEnum.fromCode("UNKNOWN")).isNull();
    }
}
