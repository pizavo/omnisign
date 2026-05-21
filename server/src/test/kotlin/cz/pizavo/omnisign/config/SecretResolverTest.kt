package cz.pizavo.omnisign.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [oidcClientSecretEnvVar].
 *
 * The env-var derivation rule is operator-facing — once a deployment chooses provider
 * names, the resulting env var names are part of the contract. Pin every shape with a
 * test so the rule cannot accidentally drift.
 *
 * [ServerSecrets.resolveFromEnv] itself is not unit-tested here because it reads
 * `System.getenv`, which is process-wide and brittle to override across tests. Its
 * branches are exercised indirectly via the consumer-side tests (auth-enabled tests
 * pass an explicit [ServerSecrets] rather than relying on env vars).
 */
class SecretResolverTest : FunSpec({

    test("simple lowercase name produces an uppercase env var") {
        oidcClientSecretEnvVar("google") shouldBe "OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET"
    }

    test("hyphenated name maps each hyphen to underscore") {
        oidcClientSecretEnvVar("google-workspace") shouldBe "OMNISIGN_OIDC_GOOGLE_WORKSPACE_CLIENT_SECRET"
    }

    test("dotted name maps each dot to underscore (eduID.cz pattern)") {
        oidcClientSecretEnvVar("eduid.cz") shouldBe "OMNISIGN_OIDC_EDUID_CZ_CLIENT_SECRET"
    }

    test("mixed-case name is uppercased") {
        oidcClientSecretEnvVar("Auth0") shouldBe "OMNISIGN_OIDC_AUTH0_CLIENT_SECRET"
    }

    test("digits are preserved") {
        oidcClientSecretEnvVar("provider1") shouldBe "OMNISIGN_OIDC_PROVIDER1_CLIENT_SECRET"
    }

    test("multiple non-alphanumeric characters collapse to single underscores at each position") {
        oidcClientSecretEnvVar("my-weird.name+extra") shouldBe "OMNISIGN_OIDC_MY_WEIRD_NAME_EXTRA_CLIENT_SECRET"
    }
})
