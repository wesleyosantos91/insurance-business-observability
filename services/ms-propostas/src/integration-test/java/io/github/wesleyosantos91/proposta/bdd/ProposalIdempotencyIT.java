package io.github.wesleyosantos91.proposta.bdd;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue", value = "io.github.wesleyosantos91.proposta.bdd")
@ConfigurationParameter(key = "cucumber.plugin",
        value = "pretty,json:target/cucumber/idempotency.json,html:target/cucumber/idempotency.html")
@ConfigurationParameter(key = "cucumber.execution.parallel.enabled", value = "false")
public class ProposalIdempotencyIT {
}
