package net.corda.bridge

import com.typesafe.config.ConfigException
import net.corda.bridge.services.api.FirewallMode
import net.corda.bridge.services.config.BridgeConfigHelper
import net.corda.bridge.services.config.BridgeConfigHelper.asString
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.div
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.Try
import net.corda.node.services.config.ConfigHelper
import net.corda.node.services.config.parseAsNodeConfiguration
import net.corda.nodeapi.internal.config.UnknownConfigurationKeysException
import net.corda.nodeapi.internal.config.toConfig
import net.corda.nodeapi.internal.cryptoservice.SupportedCryptoServices
import net.corda.nodeapi.internal.protonwrapper.netty.ProxyVersion
import net.corda.nodeapi.internal.protonwrapper.netty.RevocationConfig
import net.corda.testing.common.internal.ProjectStructure.projectRootDir
import net.corda.testing.core.SerializationEnvironmentRule
import org.assertj.core.api.Assertions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Consumer
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val serializationEnvironment = SerializationEnvironmentRule()

    @Test
    fun `Load simple config`() {
        val configResource = "/net/corda/bridge/singleprocess/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(FirewallMode.SenderReceiver, config.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), config.outboundConfig!!.artemisBrokerAddress)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), config.inboundConfig!!.listeningAddress)
        assertNull(config.bridgeInnerConfig)
        assertNull(config.floatOuterConfig)
    }

    @Test
    fun `Load simple bridge config`() {
        val configResource = "/net/corda/bridge/withfloat/bridge/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(FirewallMode.BridgeInner, config.firewallMode)
        assertEquals(NetworkHostAndPort("localhost", 11005), config.outboundConfig!!.artemisBrokerAddress)
        assertNull(config.inboundConfig)
        assertEquals(listOf(NetworkHostAndPort("localhost", 12005)), config.bridgeInnerConfig!!.floatAddresses)
        assertEquals(CordaX500Name.parse("O=Bank A, L=London, C=GB"), config.bridgeInnerConfig!!.expectedCertificateSubject)
        assertNull(config.floatOuterConfig)
    }

    @Test
    fun `Load simple float config`() {
        val configResource = "/net/corda/bridge/withfloat/float/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(FirewallMode.FloatOuter, config.firewallMode)
        assertNull(config.outboundConfig)
        assertEquals(NetworkHostAndPort("0.0.0.0", 10005), config.inboundConfig!!.listeningAddress)
        assertNull(config.bridgeInnerConfig)
        assertEquals(NetworkHostAndPort("localhost", 12005), config.floatOuterConfig!!.floatAddress)
        assertEquals(CordaX500Name.parse("O=Bank A, L=London, C=GB"), config.floatOuterConfig!!.expectedCertificateSubject)
        assertNull(config.networkParametersPath)
    }

    @Test
    fun `Load overridden cert config`() {
        val configResource = "/net/corda/bridge/custombasecerts/firewall.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertEquals(baseDirectory.resolve(Paths.get("customcerts/mysslkeystore.jks")), config.publicSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("customcerts/mytruststore.jks")), config.publicSSLConfiguration.trustStore.path)
    }

    @Test
    fun `Load custom inner certificate config`() {
        val configResource = "/net/corda/bridge/separatedwithcustomcerts/bridge/firewall.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        val artemisSSLConfiguration = config.outboundConfig!!.artemisSSLConfiguration!!
        assertEquals(baseDirectory.resolve(Paths.get("outboundcerts/outboundkeys.jks")), artemisSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("outboundcerts/outboundtrust.jks")), artemisSSLConfiguration.trustStore.path)
        assertEquals("outboundkeypassword", artemisSSLConfiguration.keyStore.storePassword)
        assertEquals("outboundtrustpassword", artemisSSLConfiguration.trustStore.storePassword)
        assertNull(config.inboundConfig)
        val tunnelSSLConfiguration = config.bridgeInnerConfig!!.tunnelSSLConfiguration!!
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunnelkeys.jks")), tunnelSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunneltrust.jks")), tunnelSSLConfiguration.trustStore.path)
        assertEquals("tunnelkeypassword", tunnelSSLConfiguration.keyStore.storePassword)
        assertEquals("tunneltrustpassword", tunnelSSLConfiguration.trustStore.storePassword)
        assertNull(config.floatOuterConfig)
    }

    @Test
    fun `Load custom inner certificate config V3`() {
        val configResource = "/net/corda/bridge/separatedwithcustomcerts/bridge/firewall_v3.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        val artemisSSLConfiguration = config.outboundConfig!!.artemisSSLConfiguration!!
        assertEquals(baseDirectory.resolve(Paths.get("outboundcerts/outboundkeys.jks")), artemisSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("outboundcerts/outboundtrust.jks")), artemisSSLConfiguration.trustStore.path)
        assertEquals("outboundkeypassword", artemisSSLConfiguration.keyStore.storePassword)
        assertEquals("outboundtrustpassword", artemisSSLConfiguration.trustStore.storePassword)
        assertNull(config.inboundConfig)
        val tunnelSSLConfiguration = config.bridgeInnerConfig!!.tunnelSSLConfiguration!!
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunnelkeys.jks")), tunnelSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunneltrust.jks")), tunnelSSLConfiguration.trustStore.path)
        assertEquals("tunnelkeypassword", tunnelSSLConfiguration.keyStore.storePassword)
        assertEquals("tunneltrustpassword", tunnelSSLConfiguration.trustStore.storePassword)
        assertNull(config.floatOuterConfig)
        assertFalse(config.useProxyForCrls)
    }

    @Test
    fun `Load custom inner certificate config diff passwords`() {
        val configResource = "/net/corda/bridge/separatedwithcustomcerts/bridge/firewall_diffPasswords.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        val outboundSSLConfiguration = config.outboundConfig!!.artemisSSLConfiguration!!
        assertEquals("outboundkeypassword", outboundSSLConfiguration.keyStore.storePassword)
        assertEquals("outboundprivatekeypassword", outboundSSLConfiguration.keyStore.entryPassword)
        assertEquals("outboundtrustpassword", outboundSSLConfiguration.trustStore.storePassword)
        assertNull(config.inboundConfig)
        val innerSLConfiguration = config.bridgeInnerConfig!!.tunnelSSLConfiguration!!
        assertEquals("tunnelkeypassword", innerSLConfiguration.keyStore.storePassword)
        assertEquals("tunneltrustpassword", innerSLConfiguration.trustStore.storePassword)
        assertNull(config.floatOuterConfig)
    }

    @Test
    fun `Load custom outer certificate config`() {
        val configResource = "/net/corda/bridge/separatedwithcustomcerts/float/firewall.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertNull(config.outboundConfig)
        val tunnelSSLConfiguration = config.floatOuterConfig!!.tunnelSSLConfiguration!!
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunnelkeys.jks")), tunnelSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunneltrust.jks")), tunnelSSLConfiguration.trustStore.path)
        assertEquals("tunnelkeypassword", tunnelSSLConfiguration.keyStore.storePassword)
        assertEquals("tunneltrustpassword", tunnelSSLConfiguration.trustStore.storePassword)
        assertNull(config.bridgeInnerConfig)
    }

    @Test
    fun `Load custom outer certificate config V3`() {
        val configResource = "/net/corda/bridge/separatedwithcustomcerts/float/firewall_v3.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertNull(config.outboundConfig)
        val tunnelSSLConfiguration = config.floatOuterConfig!!.tunnelSSLConfiguration!!
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunnelkeys.jks")), tunnelSSLConfiguration.keyStore.path)
        assertEquals(baseDirectory.resolve(Paths.get("tunnelcerts/tunneltrust.jks")), tunnelSSLConfiguration.trustStore.path)
        assertEquals("tunnelkeypassword", tunnelSSLConfiguration.keyStore.storePassword)
        assertEquals("tunneltrustpassword", tunnelSSLConfiguration.trustStore.storePassword)
        assertNull(config.bridgeInnerConfig)
    }

    @Test
    fun `Load config withsocks support`() {
        val configResource = "/net/corda/bridge/withsocks/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(ProxyVersion.SOCKS5, config.outboundConfig!!.proxyConfig!!.version)
        assertEquals(NetworkHostAndPort("localhost", 12345), config.outboundConfig!!.proxyConfig!!.proxyAddress)
        assertEquals("proxyUser", config.outboundConfig!!.proxyConfig!!.userName)
        assertEquals("pwd", config.outboundConfig!!.proxyConfig!!.password)
        assertEquals(null, config.outboundConfig!!.proxyConfig!!.proxyTimeoutMS)
        assertTrue(config.useProxyForCrls)
        val badConfigResource4 = "/net/corda/bridge/withsocks/badconfig/badsocksversion4.conf"
        assertFailsWith<IllegalArgumentException> {
            createAndLoadConfigFromResource(tempFolder.root.toPath() / "4", badConfigResource4)
        }
        val badConfigResource5 = "/net/corda/bridge/withsocks/badconfig/badsocksversion5.conf"
        assertFailsWith<IllegalArgumentException> {
            createAndLoadConfigFromResource(tempFolder.root.toPath() / "5", badConfigResource5)
        }
        val badConfigResource = "/net/corda/bridge/withsocks/badconfig/socks4passwordsillegal.conf"
        assertFailsWith<IllegalArgumentException> {
            createAndLoadConfigFromResource(tempFolder.root.toPath() / "bad", badConfigResource)
        }

        assertEquals(60, config.auditServiceConfiguration.loggingIntervalSec)
    }

    @Test
    fun `Load audit service config`() {
        val configResource = "/net/corda/bridge/withaudit/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(34, config.auditServiceConfiguration.loggingIntervalSec)
        assertNull(config.healthCheckPhrase)

        assertFailsWith<ConfigException.WrongType> {
            createAndLoadConfigFromResource(tempFolder.root.toPath() / "err1", "/net/corda/bridge/withaudit/badconfig/badInterval.conf")
        }
    }

    @Test
    fun `Load healthCheckPhrase config`() {
        val configResource = "/net/corda/bridge/healthcheckphrase/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals("ISpeakAMQP!", config.healthCheckPhrase)
    }

    @Test
    fun `Load old style config`() {
        val configResource = "/net/corda/bridge/version3/bridge.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals("HelloCorda!", config.healthCheckPhrase)
        assertEquals("proxyUser", config.outboundConfig?.proxyConfig?.userName)
    }

    @Test
    fun `Load config with HTTP proxy support`() {
        val configResource = "/net/corda/bridge/withhttpproxy/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(ProxyVersion.HTTP, config.outboundConfig!!.proxyConfig!!.version)
        assertEquals(NetworkHostAndPort("proxyHost", 12345), config.outboundConfig!!.proxyConfig!!.proxyAddress)
        assertEquals("proxyUser", config.outboundConfig!!.proxyConfig!!.userName)
        assertEquals("pwd", config.outboundConfig!!.proxyConfig!!.password)
        assertEquals(30000L, config.outboundConfig!!.proxyConfig!!.proxyTimeoutMS)
    }

    @Test
    fun `Load invalid option config`() {
        val configResource = "/net/corda/bridge/invalidoption/firewall.conf"

        Assertions.assertThatThrownBy { createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource) }
                .isInstanceOf(UnknownConfigurationKeysException::class.java)
                .hasMessageContaining("invalidOption")
    }

    @Test
    fun `Load with sslKeystore path overridden`() {
        val configResource = "/net/corda/bridge/keystoreoverride/firewall.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertEquals(baseDirectory / "opt" / "myCertificates", config.certificatesDirectory)
        assertEquals(baseDirectory / "opt" / "myCertificates" / "ssl" / "mySslKeystore.jks", config.sslKeystore)
        assertEquals(baseDirectory / "opt" / "myCertificates" / "truststore.jks", config.trustStoreFile)
    }

    @Test
    fun `passwords are hidden in logging`() {
        listOf("/net/corda/bridge/singleprocess/firewall.conf",
                "/net/corda/bridge/withfloat/bridge/firewall.conf",
                "/net/corda/bridge/withfloat/float/firewall.conf",
                "/net/corda/bridge/custombasecerts/firewall.conf",
                "/net/corda/bridge/separatedwithcustomcerts/bridge/firewall.conf",
                "/net/corda/bridge/separatedwithcustomcerts/bridge/firewall_v3.conf",
                "/net/corda/bridge/separatedwithcustomcerts/bridge/firewall_diffPasswords.conf",
                "/net/corda/bridge/separatedwithcustomcerts/float/firewall.conf",
                "/net/corda/bridge/separatedwithcustomcerts/float/firewall_v3.conf",
                "/net/corda/bridge/withsocks/firewall.conf",
                "/net/corda/bridge/withaudit/firewall.conf",
                "/net/corda/bridge/healthcheckphrase/firewall.conf",
                "/net/corda/bridge/version3/bridge.conf",
                "/net/corda/bridge/withhttpproxy/firewall.conf",
                "/net/corda/bridge/keystoreoverride/firewall.conf")
                .forEachIndexed { index, path ->
                    val config = createAndLoadConfigFromResource(tempFolder.root.toPath() / "test$index", path)
                    val configString = config.toConfig().asString()

                    val possiblePasswordFromConfig = listOf("pwd",
                            "mySecretArtemisKeyStorePassword",
                            "mySecretArtemisTrustStorePassword",
                            "mySecretTunnelKeyStorePassword",
                            "mySecretTunnelPrivateKeyPassword",
                            "mySecretTunnelTrustStorePassword",
                            "trustpass",
                            "cordacadevpass",
                            "tunnelkeypassword",
                            "tunneltrustpassword",
                            "outboundtrustpassword",
                            "outboundkeypassword",
                            "inboundkeypassword",
                            "inboundtrustpassword")

                    possiblePasswordFromConfig.forEach {
                        assertFalse(configString.contains(it))
                    }
                }
    }

    @Test
    fun `crlCheckSoftFail old style implicit`() {
        val configResource = "/net/corda/bridge/crlCheckSoftFail/firewall_old_implicit.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(RevocationConfig.Mode.SOFT_FAIL, config.revocationConfig.mode)
    }

    @Test
    fun `crlCheckSoftFail old style explicit`(){
        val configResource = "/net/corda/bridge/crlCheckSoftFail/firewall_old_explicit.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(RevocationConfig.Mode.HARD_FAIL, config.revocationConfig.mode)
    }

    @Test
    fun `crlCheckSoftFail new style`() {
        val configResource = "/net/corda/bridge/crlCheckSoftFail/firewall_new.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(RevocationConfig.Mode.OFF, config.revocationConfig.mode)
    }

    @Test
    fun `External CRL config`() {
        val configResource = "/net/corda/bridge/externalSourceCrl/firewall.conf"
        val config = createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource)
        assertEquals(RevocationConfig.Mode.EXTERNAL_SOURCE, config.revocationConfig.mode)
    }

    @Test
    fun `load hsm configs from BridgeInner mode`() {
        val configResource = "/net/corda/bridge/hsm/all_hsms_bridge_inner.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertEquals(SupportedCryptoServices.UTIMACO, config.p2pTlsSigningCryptoServiceConfig?.name)
        assertEquals(baseDirectory.resolve(Paths.get("./utimaco.conf")), config.p2pTlsSigningCryptoServiceConfig?.conf)
        assertEquals(SupportedCryptoServices.AZURE_KEY_VAULT, config.artemisCryptoServiceConfig?.name)
        assertEquals(baseDirectory.resolve(Paths.get("./azure.conf")), config.artemisCryptoServiceConfig?.conf)
        assertEquals(SupportedCryptoServices.GEMALTO_LUNA, config.tunnelingCryptoServiceConfig?.name)
        assertEquals(baseDirectory.resolve(Paths.get("./gemalto.conf")), config.tunnelingCryptoServiceConfig?.conf)
        assertEquals(baseDirectory.resolve("my" / "network-parameters"), config.networkParametersPath)
    }

    @Test
    fun `load hsm configs from FloatOuter mode`() {
        val configResource = "/net/corda/bridge/hsm/all_hsms_float_outer.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertEquals(SupportedCryptoServices.FUTUREX, config.tunnelingCryptoServiceConfig?.name)
        assertEquals(baseDirectory.resolve(Paths.get("./futurex.conf")), config.tunnelingCryptoServiceConfig?.conf)
    }

    @Test
    fun `Invalid Zero address config`() {
        val configResource = "/net/corda/bridge/zeroAddress/bridge/firewall.conf"
        Assertions.assertThatThrownBy { createAndLoadConfigFromResource(tempFolder.root.toPath(), configResource) }
                .hasMessage("0.0.0.0 is not allowed in floatAddresses")
    }

    @Test
    fun `Check useProxyForCrls can be changed`() {
        val configResource = "/net/corda/bridge/useProxyForCrls/firewall.conf"
        val baseDirectory = tempFolder.root.toPath()
        val config = createAndLoadConfigFromResource(baseDirectory, configResource)
        assertTrue(config.useProxyForCrls)
    }

    @Test
    fun `Check configs for docs`() {
        val docsResources = projectRootDir.resolve("docs/source/resources")
        var firewallConfCount = 0
        var nodeConfCount = 0
        Files.walk(docsResources).filter { it.toString().endsWith(".conf") }.forEach {
            val fileName = it.fileName.toString()
            // Validate firewall configuration
            if (fileName.contains("float") || fileName.contains("bridge")) {
                Try.on { BridgeConfigHelper.loadConfig(it.parent, it) }
                        .doOnFailure(Consumer { e -> throw Exception("Invalid config $it", e) })
                firewallConfCount++
            }
            // Validate node configuration
            if (fileName.contains("node")) {
                assertTrue(ConfigHelper.loadConfig(it.parent, it).parseAsNodeConfiguration().isValid, "Invalid config $it")
                nodeConfCount++
            }
        }
        // Make sure that resources were checked
        assertTrue(firewallConfCount > 0)
        assertTrue(nodeConfCount > 0)
    }
}