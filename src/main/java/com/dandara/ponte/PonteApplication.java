package com.dandara.ponte;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PonteApplication {
    public static void main(String[] args) {
        SpringApplication.run(PonteApplication.class, args);
    }

    @Bean("origem")
    HikariDataSource origem(@Value("${ponte.origem.url}") String url,
                            @Value("${ponte.username}") String user,
                            @Value("${ponte.password}") String password) {
        return banco("origem", url, user, password);
    }

    @Bean("destino")
    HikariDataSource destino(@Value("${ponte.destino.url}") String url,
                             @Value("${ponte.username}") String user,
                             @Value("${ponte.password}") String password) {
        return banco("destino", url, user, password);
    }

    @Bean
    MigracaoService migracao(@Qualifier("origem") HikariDataSource origem,
                             @Qualifier("destino") HikariDataSource destino) {
        inicializar(origem, "origem.sql");
        inicializar(destino, "destino.sql");
        return new MigracaoService(origem, destino);
    }

    private static HikariDataSource banco(String nome, String url, String user, String password) {
        var banco = new HikariDataSource();
        banco.setPoolName(nome);
        banco.setJdbcUrl(url);
        banco.setUsername(user);
        banco.setPassword(password);
        banco.setMaximumPoolSize(4);
        banco.setConnectionTimeout(2500);
        banco.addDataSourceProperty("socketTimeout", 10);
        return banco;
    }

    private static void inicializar(HikariDataSource banco, String arquivo) {
        var script = new ResourceDatabasePopulator(new ClassPathResource(arquivo));
        script.setSeparator("^^^");
        script.execute(banco);
    }
}
