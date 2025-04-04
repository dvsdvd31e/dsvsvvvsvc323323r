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
        // Проверка, не запущена ли уже индексация
        if (indexingInProgress) {
            logger.warn("Индексация уже запущена. Перезапуск невозможен.");
            return;
        }

        // Устанавливаем флаг, что индексация началась
        indexingInProgress = true;
        logger.info("Индексация начата.");

        // Создаем executorService для выполнения индексации в отдельном потоке
        executorService = Executors.newSingleThreadExecutor();

        // Запускаем индексацию
        executorService.submit(() -> {
            try {
                logger.info("Выполняем индексацию...");
                performIndexing();
            } catch (Exception e) {
                logger.error("Ошибка во время индексации: ", e);
            } finally {
                // После завершения индексации сбрасываем флаг
                synchronized (this) {
                    indexingInProgress = false;
                    logger.info("Индексация завершена.");
                }
            }
        });

        // Закрываем executorService после выполнения всех задач
        executorService.shutdown();
    }

    public synchronized void stopIndexing() {
        if (!indexingInProgress) {
            System.out.println("Индексация не запущена.");
            return;
        }

        indexingInProgress = false; // Обновляем флаг

        // Ожидаем завершения всех задач
        for (CompletableFuture<Void> task : runningTasks) {
            task.cancel(true); // Прерываем задачу
            System.out.println("Задача индексации отменена.");
        }

        // Завершаем выполнение индексации
        runningTasks.clear();
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
                    if (!indexingInProgress) {
                        logger.info("Индексация остановлена. Прекращаем индексацию сайта: {}", site.getUrl());
                        return; // Прерываем индексацию, если она была остановлена
                    }

                    logger.info("Индексация сайта: {} ({})", site.getName(), site.getUrl());
                    try {
                        deleteSiteData(site.getUrl());
                        searchengine.model.Site newSite = new searchengine.model.Site();
                        newSite.setName(site.getName());
                        newSite.setUrl(site.getUrl());
                        newSite.setStatusTime(LocalDateTime.now());
                        newSite.setStatus(IndexingStatus.INDEXING);  // Устанавливаем статус в процессе

                        siteRepository.save(newSite);
                        crawlAndIndexPages(newSite, site.getUrl());

                        if (indexingInProgress) {
                            updateSiteStatus(site.getUrl(), IndexingStatus.INDEXED);
                        } else {
                            logger.warn("Индексация была прервана. Статус сайта {} не обновлен на INDEXED.", site.getName());
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


    // Метод для обновления статуса сайта
    private void updateSiteStatus(String url, IndexingStatus status) {
        updateSiteStatus(url, status, null);  // Если ошибок нет, передаем null
    }

    private void updateSiteStatus(String url, IndexingStatus status, String errorMessage) {
        Site site = siteRepository.findByUrl(url);
        if (site != null) {
            site.setStatus(status);  // Устанавливаем новый статус
            if (errorMessage != null) {
                site.setLastError(errorMessage);  // Устанавливаем описание ошибки, если оно есть
            }
            site.setStatusTime(LocalDateTime.now());  // Обновляем время статуса
            siteRepository.save(site);  // Сохраняем сайт в базе
            logger.info("Статус сайта обновлен: {} — {}", url, status);
        }
    }


    private void crawlAndIndexPages(Site site, String startUrl) {
        forkJoinPool = new ForkJoinPool();

        try {
            // Запускаем задачу обхода страниц, проверяя флаг остановки индексации
            if (!indexingInProgress) {
                logger.info("Индексация остановлена. Прекращаем обход сайта.");
                return;
            }

            // Запуск обхода страниц
            forkJoinPool.invoke(new PageCrawler(site, lemmaRepository, indexRepository, startUrl, new HashSet<>(),
                    pageRepository, this, 0, 2)); // Пример глубины 2
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
