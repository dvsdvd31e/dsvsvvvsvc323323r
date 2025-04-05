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
    private final PageIndexingService pageIndexingService;
    private final SearchService searchService;
    private final SitesList sitesList;
    private boolean indexingInProgress = false;

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
        if (indexingInProgress) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Индексация уже запущена");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        indexingInProgress = true;

        try {
            CompletableFuture.runAsync(() -> {
                try {
                    indexingService.startFullIndexing();
                } catch (Exception e) {
                    System.err.println("Ошибка при индексации: " + e.getMessage());
                } finally {
                    indexingInProgress = false;
                }
            });

            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            response.put("message", "Индексация началась асинхронно.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            indexingInProgress = false;
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Ошибка при запуске индексации: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
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
        if (indexingInProgress) {
            Map<String, Object> response = new HashMap<>();
            response.put("result", false);
            response.put("error", "Индексация уже запущена");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        indexingInProgress = true;

        try {
            CompletableFuture.runAsync(() -> {
                try {
                    pageIndexingService.indexPage(url);
                } catch (Exception e) {
                    logger.error("Ошибка при индексации страницы: {}", e.getMessage(), e);
                } finally {
                    indexingInProgress = false;
                }
            });

            Map<String, Object> response = new HashMap<>();
            response.put("result", true);
            response.put("message", "Индексация страницы началась асинхронно.");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
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
