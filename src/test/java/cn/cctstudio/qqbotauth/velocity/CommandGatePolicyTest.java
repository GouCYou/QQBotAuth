package cn.cctstudio.qqbotauth.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGatePolicyTest {
    private final CommandGatePolicy policy = CommandGatePolicy.fromCsv(
            CommandGatePolicy.DEFAULT_ALLOWED_COMMANDS);

    @Test
    void allowsOnlyAuthenticationAndVerificationCommands() {
        assertTrue(policy.allows("login password"));
        assertTrue(policy.allows("/REGISTER password password"));
        assertTrue(policy.allows("email recovery example@example.com"));
        assertTrue(policy.allows("captcha value"));
        assertTrue(policy.allows("qqverify dialog"));
        assertTrue(policy.allows("/qqbotauth:qqverify status"));

        assertFalse(policy.allows("server lobby"));
        assertFalse(policy.allows("/velocity:server lobby"));
        assertFalse(policy.allows("plugins"));
        assertFalse(policy.allows("minecraft:help"));
    }

    @Test
    void emptyConfigurationFallsBackToSafeDefaults() {
        CommandGatePolicy fallback = CommandGatePolicy.fromCsv(" , , ");
        assertTrue(fallback.allows("login value"));
        assertFalse(fallback.allows("server lobby"));
    }
}
