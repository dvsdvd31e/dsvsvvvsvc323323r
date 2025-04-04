package searchengine.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;
import searchengine.config.SitesList ;
import searchengine.dto.search.SearchResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.PostMapping;
import searchengine.services.PageIndexingService;
import java.util.concurrent.ExecutorService;
import searchengine.services.SearchService;
import org.springframework.context.annotation.Lazy;

@RestController
@Lazy
@RequestMapping("/api")
public class ApiController {
    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    @Lazy
    private final StatisticsService statisticsService;
    @Lazy
    private final IndexingService indexingService;
    private final ExecutorService executorService;
    private final PageIndexingService pageIndexingService;  // Исправленное имя переменной
    private final SearchService searchService;
    private final SitesList sitesList;
    private boolean indexingInProgress = false;  // Флаг индексации

    public ApiController(@Lazy StatisticsService statisticsService,SitesList sitesList,SearchService searchService,@Lazy PageIndexingService pageIndexingService,@Lazy IndexingService indexingService, ExecutorService executorService) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.sitesList = sitesList;
        this.executorService = executorService;
        this.pageIndexingService = pageIndexingService;
        this.searchService = searchService;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        StatisticsResponse statistics = statisticsService.getStatistics();
        return ResponseEntity.ok(statistics);
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<Map<String, Object>> startIndexing() {
        // Проверяем, если индексация уже идет
        if (indexingInProgress) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Индексация уже запущена");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        // Устанавливаем флаг, что индексация запущена
        indexingInProgress = true;

        try {
            // Запускаем индексацию асинхронно
            CompletableFuture.runAsync(() -> {
                try {
                    indexingService.startFullIndexing();  // Метод индексации, выполняющий долгую задачу
                } catch (Exception e) {
                    // Обработка ошибок внутри индексации (можно логировать ошибки)
                    System.err.println("Ошибка при индексации: " + e.getMessage());
                } finally {
                    // После завершения индексации сбрасываем флаг
                    indexingInProgress = false;
                }
            });

            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            response.put("message", "Индексация началась асинхронно.");
            return ResponseEntity.ok(response); // Возвращаем успешный результат
        } catch (Exception e) {
            // В случае ошибки сбрасываем флаг индексации
            indexingInProgress = false;
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Ошибка при запуске индексации: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response); // Возвращаем ошибку
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        try {
            indexingService.stopIndexing();
            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Индексация не запущена");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam String url) {
        // Проверяем, если индексация уже идет
        if (indexingInProgress) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Индексация уже запущена");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        // Устанавливаем флаг, что индексация запущена
        indexingInProgress = true;

        try {
            // Запускаем индексацию страницы
            CompletableFuture.runAsync(() -> {
                try {
                    pageIndexingService.indexPage(url);  // Метод индексации страницы
                } catch (Exception e) {
                    // Логируем ошибку индексации страницы
                    logger.error("Ошибка при индексации страницы: {}", e.getMessage(), e);
                } finally {
                    // После завершения индексации сбрасываем флаг
                    indexingInProgress = false;
                }
            });

            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            response.put("message", "Индексация страницы началась асинхронно.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            // В случае ошибки сбрасываем флаг индексации
            indexingInProgress = false;
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Ошибка при запуске индексации страницы: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam String query,
            @RequestParam(required = false) String site,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new SearchResponse("Задан пустой поисковый запрос"));
        }

        try {
            SearchResponse searchResponse = searchService.search(query, site, offset, limit);
            return ResponseEntity.ok(searchResponse);
        } catch (Exception e) {
            logger.error("Ошибка выполнения поиска: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new SearchResponse("Ошибка при выполнении поиска"));
        }
    }
}
