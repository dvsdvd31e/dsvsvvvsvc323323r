package searchengine.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import searchengine.config.SitesList;
import searchengine.model.IndexingStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.time.LocalDateTime;
import java.util.*;
import searchengine.repository.LemmaRepository;
import searchengine.repository.IndexRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingService {

    private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    private volatile boolean indexingInProgress = false;
    private ExecutorService executorService;
    private ForkJoinPool forkJoinPool;

    public IndexingService(SitesList sitesList,LemmaRepository lemmaRepository,IndexRepository indexRepository, SiteRepository siteRepository,  PageRepository pageRepository ) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.indexRepository = indexRepository;
        this.lemmaRepository = lemmaRepository;

    }

    public synchronized boolean isIndexingInProgress() {
        return indexingInProgress;
    }

    public synchronized void startFullIndexing() {
        if (indexingInProgress) {
            logger.warn("Попытка запустить индексацию, которая уже выполняется.");
            throw new IllegalStateException("Индексация уже запущена.");
        }
        indexingInProgress = true;
        logger.info("Индексация начата.");

        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(() -> {
            try {
                performIndexing();
            } catch (Exception e) {
                logger.error("Ошибка во время индексации: ", e);
            } finally {
                indexingInProgress = false;
                logger.info("Индексация завершена.");
            }
        });
        executorService.shutdown();
    }

    public synchronized void stopIndexing() {
        if (!indexingInProgress) {
            logger.warn("Попытка остановить индексацию, которая не выполняется.");
            return;
        }
        logger.info("Остановка индексации по запросу пользователя.");
        indexingInProgress = false;

        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (forkJoinPool != null) {
            forkJoinPool.shutdownNow();
        }

        updateSitesStatusToFailed("Индексация остановлена пользователем");
    }


    private void performIndexing() {
        List<searchengine.config.ConfigSite> sites = sitesList.getSites();
        if (sites == null || sites.isEmpty()) {
            logger.warn("Список сайтов для индексации пуст.");
            return;
        }

        executorService = Executors.newFixedThreadPool(sites.size());
        try {
            for (searchengine.config.ConfigSite site : sites) {
                executorService.submit(() -> {
                    logger.info("Индексация сайта: {} ({})", site.getName(), site.getUrl());
                    try {
                        deleteSiteData(site.getUrl());
                        searchengine.model.Site newSite = new searchengine.model.Site();
                        newSite.setName(site.getName());
                        newSite.setUrl(site.getUrl());
                        newSite.setStatus(IndexingStatus.INDEXING);
                        newSite.setStatusTime(LocalDateTime.now());
                        siteRepository.save(newSite);
                        crawlAndIndexPages(newSite, site.getUrl());
                        if (indexingInProgress) {
                            updateSiteStatusToIndexed(newSite);
                        } else {
                            logger.warn("Индексация была прервана. Статус сайта {} не обновлен на INDEXED.", site.getName());
                        }
                    } catch (Exception e) {
                        handleIndexingError(site.getUrl(), e);
                    }
                });
            }
        } finally {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(1, TimeUnit.HOURS)) {
                    executorService.shutdownNow();
                    logger.error("Превышено время ожидания завершения индексации.");
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                logger.error("Индексация была прервана: {}", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }

    private void crawlAndIndexPages(searchengine.model.Site site, String startUrl) {
        // Определяем максимальную глубину и начальную глубину (например, 0 для начала)
        int maxDepth = 1;  // Максимальная глубина
        int currentDepth = 0;  // Начальная глубина

        forkJoinPool = new ForkJoinPool();
        try {
            forkJoinPool.invoke(new PageCrawler(
                    site,
                    lemmaRepository,  // передаем LemmaRepository
                    indexRepository,  // передаем IndexRepository
                    startUrl,
                    new HashSet<>(),
                    pageRepository,
                    this,
                    maxDepth,        // передаем максимальную глубину
                    currentDepth     // передаем начальную глубину
            ));
        } finally {
            forkJoinPool.shutdown();
        }
    }


    @Transactional
    public void deleteSiteData(String siteUrl) {
        searchengine.model.Site site = siteRepository.findByUrl(siteUrl);
        if (site != null) {
            Long siteId = (long) site.getId();

            int indexesDeleted = indexRepository.deleteBySiteId(site.getId());

            int lemmasDeleted = lemmaRepository.deleteBySiteId(siteId);

            int pagesDeleted = pageRepository.deleteAllBySiteId(site.getId());

            siteRepository.delete(site);

            logger.info("Удалено {} записей из таблицы index.", indexesDeleted);
            logger.info("Удалено {} записей из таблицы lemma.", lemmasDeleted);
            logger.info("Удалено {} записей из таблицы page для сайта {}.", pagesDeleted, siteUrl);
            logger.info("Сайт {} успешно удален.", siteUrl);
        } else {
            logger.warn("Сайт {} не найден в базе данных.", siteUrl);
        }
    }

    private void updateSiteStatusToIndexed(searchengine.model.Site site) {
        site.setStatus(IndexingStatus.INDEXED);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
        logger.info("Сайт {} изменил статус на INDEXED.", site.getUrl());
    }

    private void handleIndexingError(String siteUrl, Exception e) {
        searchengine.model.Site site = siteRepository.findByUrl(siteUrl);
        if (site != null) {
            site.setStatus(IndexingStatus.FAILED);
            site.setLastError(e.getMessage());
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            logger.error("Ошибка при индексации сайта {}: {}", site.getUrl(), e.getMessage());
        }
    }

    private void updateSitesStatusToFailed(String errorMessage) {
        List<searchengine.model.Site> sites = siteRepository.findAllByStatus(IndexingStatus.INDEXING);
        for (searchengine.model.Site site : sites) {
            site.setStatus(IndexingStatus.FAILED);
            site.setLastError(errorMessage);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            logger.info("Сайт {} изменил статус на FAILED: {}", site.getUrl(), errorMessage);
        }
    }
}
