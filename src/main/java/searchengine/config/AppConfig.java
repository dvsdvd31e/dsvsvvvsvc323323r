package searchengine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import searchengine.services.PageCrawler ;

@Configuration
public class AppConfig {
    @Bean
    public ExecutorService executorService() {
        return Executors.newFixedThreadPool(4);
    }


}