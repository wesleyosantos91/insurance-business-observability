package io.github.wesleyosantos91.cadastro;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false", "insurance.persistence.initialize=false",
        "spring.datasource.url=jdbc:h2:mem:cadastro;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class CadastroApplicationTests {

    @Test
    void contextLoads() {
    }
}
