package searchengine.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.model.IndexingStatus;
import searchengine.model.Site;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.time.LocalDateTime;
import java.util.*;
import searchengine.repository.LemmaRepository;
import searchengine.repository.IndexRepository;
import java.util.concurrent.*;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IndexingService {

    private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final Set<CompletableFuture<Void>> runningTasks = Collections.synchronizedSet(new HashSet<>());
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
            logger.warn("Индексация уже запущена. Перезапуск невозможен.");
            return;
        }

        indexingInProgress = true;
        logger.info("Индексация начата.");

        executorService = Executors.newSingleThreadExecutor();

        executorService.submit(() -> {
            try {
                logger.info("Выполняем индексацию...");
                performIndexing();
            } catch (Exception e) {
                logger.error("Ошибка во время индексации: ", e);
            } finally {
                synchronized (this) {
                    indexingInProgress = false;
                    logger.info("Индексация завершена.");
                }
            }
        });
        executorService.shutdown();
    }

    public void stopIndexing() {
        if (!indexingInProgress) {
            System.out.println("Индексация не запущена.");
            return;
        }

        indexingInProgress = false;

        executorService.shutdownNow();
        System.out.println("Остановка индексации...");

        for (CompletableFuture<Void> task : runningTasks) {
            task.cancel(true);
            System.out.println("Задача индексации отменена.");
        }

        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                System.out.println("Некоторые задачи не завершились. Принудительное завершение.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        runningTasks.clear();

        List<Site> sites = siteRepository.findAll();
        for (Site site : sites) {
            {

                System.out.println("Статус сайта обновлен на FAILED: " + site.getUrl());
            }
        }

        indexingInProgress = false;

        System.out.println("Индексация остановлена.");
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
                            updateSiteStatus(site.getUrl(), IndexingStatus.INDEXED);
                        } else {
                            logger.warn("Индексация была прервана. Статус сайта {} не обновлен на INDEXED.", site.getName());
                            updateSiteStatus(site.getUrl(), IndexingStatus.FAILED, "Индексация была прервана.");
                        }
                    } catch (Exception e) {
                        updateSiteStatus(site.getUrl(), IndexingStatus.FAILED, e.getMessage());
                        logger.error("Ошибка индексации сайта {}: {}", site.getUrl(), e.getMessage());
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

    private void updateSiteStatus(String url, IndexingStatus status) {
        updateSiteStatus(url, status, null);
    }

    private void updateSiteStatus(String url, IndexingStatus status, String errorMessage) {
        Site site = siteRepository.findByUrl(url);
        if (site != null) {
            site.setStatus(status);
            if (errorMessage != null) {
                site.setLastError(errorMessage);
            }
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            System.out.println("Статус сайта обновлен: " + url + " — " + status);
        }
    }

    private void crawlAndIndexPages(searchengine.model.Site site, String startUrl) {
        forkJoinPool = new ForkJoinPool();
        try {
            forkJoinPool.invoke(new PageCrawler(
                    site,
                    lemmaRepository,
                    indexRepository,
                    startUrl,
                    new HashSet<>(),
                    pageRepository,
                    this
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
}
