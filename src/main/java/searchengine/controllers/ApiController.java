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
import java.util.List;
import searchengine.config.ConfigSite ;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam ;
import searchengine.services.PageIndexingService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import searchengine.services.SearchService;
import searchengine.dto.search.SearchResponse;
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
        if (indexingService.isIndexingInProgress()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Индексация уже запущена");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        // Запуск асинхронной индексации
        executorService.submit(indexingService::startFullIndexing);

        Map<String, Object> successResponse = new HashMap<>();
        successResponse.put("result", true);
        return ResponseEntity.ok(successResponse);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        if (!indexingService.isIndexingInProgress()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Индексация не запущена");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        indexingService.stopIndexing();

        Map<String, Object> successResponse = new HashMap<>();
        successResponse.put("result", true);
        return ResponseEntity.ok(successResponse);
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


    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam String url) {
        Map<String, Object> response = new HashMap<>();

        if (url == null || url.trim().isEmpty()) {
            response.put("result", false);
            response.put("error", "URL не должен быть пустым");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            boolean success = pageIndexingService.indexPage(url);
            if (success) {
                response.put("result", true);
            } else {
                response.put("result", false);
                response.put("error", "Не удалось индексировать страницу");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Ошибка индексации страницы {}: {}", url, e.getMessage(), e);
            response.put("result", false);
            response.put("error", "Внутренняя ошибка сервера");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }


}
