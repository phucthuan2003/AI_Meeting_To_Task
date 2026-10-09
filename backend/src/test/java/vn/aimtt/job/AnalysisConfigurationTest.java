package vn.aimtt.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class AnalysisConfigurationTest {
    @TestConfiguration
    @EnableConfigurationProperties(AnalysisProperties.class)
    static class Config {}
    private ApplicationContextRunner configured() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var values = yaml.getObject().entrySet().stream().filter(e -> e.getKey().toString().startsWith("app.analysis."))
                .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class).withPropertyValues(values);
    }
    @Test void actualYamlBindsSafeLeaseAndBudgetWithoutDatabase() {
        configured().run(context -> {
            assertThat(context).hasNotFailed();
            var props = context.getBean(AnalysisProperties.class);
            assertThat(props.leaseDuration().toSeconds()).isEqualTo(30);
            assertThat(props.preparationBatchSize()).isEqualTo(100);
            assertThat(props.reservedTokens()).isLessThan(props.contextTokens());
        });
    }
    @Test void leaseTooShortIsRejectedAtStartup() {
        configured().withPropertyValues("app.analysis.lease-duration=1s").run(context -> assertThat(context).hasFailed());
    }
    @Test void invalidBudgetAndUnboundedBatchAreRejectedAtStartup() {
        configured().withPropertyValues("app.analysis.reserved-tokens=131072").run(context -> assertThat(context).hasFailed());
        configured().withPropertyValues("app.analysis.preparation-batch-size=501").run(context -> assertThat(context).hasFailed());
    }
}
