package io.funbox.marathon.plugin.vault

import io.funbox.marathon.plugin.vault.helpers.VaultTestContext
import org.assertj.core.api.Assertions.assertThat
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.vault.VaultContainer
import java.time.Duration
import kotlin.test.Test


@Testcontainers
class EnvReaderTest {

    companion object {
        const val VAULT_PORT = 8200
    }

    class KVaultContainer : VaultContainer<KVaultContainer>("vault:0.9.6")

    @Container
    private val vaultContainer = KVaultContainer()
        .withVaultToken(VaultTestContext.ROOT_TOKEN)
        .withVaultPort(VAULT_PORT)
        .waitingFor(
            Wait.forHttp("/v1/sys/active")
                .forStatusCode(400)
                .withStartupTimeout(Duration.ofSeconds(5))
        )

    private val vaultURL by lazy {
        "http://${vaultContainer.containerIpAddress}:${vaultContainer.getMappedPort(VAULT_PORT)}"
    }

    private val conf by lazy {
        PluginConf(
            vaultOptions = VaultClient.Options(
                url = vaultURL,
                timeout = 1,
                roleID = VaultTestContext.PLUGIN_ROLE_ID,
                secretID = VaultTestContext.PLUGIN_SECRET_ID
            ),
            rolePrefix = "mesos",
            defaultSecretsPath = "/secret/mesos"
        )
    }

    private val vaultContext by lazy {
        VaultTestContext(vaultURL)
    }

    @Test
    fun `returns env variables for application`() {
        vaultContext.init()

        vaultContext.writeSecret(
            "secret/mesos/${VaultTestContext.TEST_APP_NAME}/passwords",
            mapOf(
                "some-secret-key" to "some-secret-value",
                "other-secret-key" to "other-secret-value"
            )
        )

        vaultContext.writeSecret(
            "secret/mesos/${VaultTestContext.TEST_APP_NAME}/other-passwords",
            mapOf("some-secret-key" to "some-secret-value")
        )

        val result = EnvReader(conf).envsFor(VaultTestContext.TEST_APP_NAME)

        assertThat(result.envs).containsAllEntriesOf(
            mapOf(
                "PASSWORDS_SOME_SECRET_KEY" to "some-secret-value",
                "PASSWORDS_OTHER_SECRET_KEY" to "other-secret-value",
                "OTHER_PASSWORDS_SOME_SECRET_KEY" to "some-secret-value"
            )
        )
    }

    @Test
    fun `uses top level app role if specific role for app does not exist`() {
        vaultContext.initPluginRoles()
        vaultContext.createTestAppRole("mesos-some-namespace")

        vaultContext.writeSecret(
            "secret/mesos/some-namespace/test-app/passwords",
            mapOf(
                "some-secret-key" to "some-secret-value"
            )
        )

        val result = EnvReader(conf).envsFor("/some-namespace/test-app")

        assertThat(result.envs).containsEntry("PASSWORDS_SOME_SECRET_KEY", "some-secret-value")
        assertThat(result.appRole).isEqualTo("mesos-some-namespace")
    }


}
