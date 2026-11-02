package io.github.wesleyosantos91.proposta;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS, properties = {
        "spring.kafka.listener.auto-startup=false", "insurance.persistence.initialize=false",
        "spring.datasource.url=jdbc:h2:mem:propostas;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class PropostaApplicationTests {

    @Test
    void contextLoads() {
    }

}
