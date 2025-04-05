package searchengine.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.config.SitesList;
import java.time.LocalDateTime;
import searchengine.model.IndexingStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;
import java.net.URL;
import org.jsoup.select.Elements;
import org.jsoup.nodes.Element;

import java.util.concurrent.*;

@Service
public class PageIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(PageIndexingService.class);

    @Autowired
    private PageRepository pageRepository;

    @Autowired
    private SiteRepository siteRepository;

    @Autowired
    private SitesList sitesList;

    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final ForkJoinPool forkJoinPool = new ForkJoinPool();

    public void indexPage(String url) {
        if (!isValidSite(url)) {
            logger.warn("Сайт с таким URL не найден в конфигурации: {}", url);
            return;
        }

        // Очистка данных
        deleteSiteData(url);

        // Создание новой записи сайта
        Site site = new Site();
        site.setUrl(url);
        site.setName("Unknown Site");
        site.setStatus(IndexingStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);
        logger.info("Добавлен новый сайт в индексацию: {}", url);

        // Очистка посещённых ссылок (на случай повторной индексации)
        visitedUrls.clear();

        // Запуск ForkJoinTask
        forkJoinPool.invoke(new PageIndexingTask(url, site));
    }

    @Transactional
    private void deleteSiteData(String siteUrl) {
        searchengine.model.Site site = siteRepository.findByUrl(siteUrl);
        if (site != null) {
            Long siteId = (long) site.getId();
            int pagesDeleted = pageRepository.deleteAllBySiteId(site.getId());
            siteRepository.delete(site);
            logger.info("Удалено {} записей из таблицы page для сайта {}.", pagesDeleted, siteUrl);
            logger.info("Сайт {} успешно удален.", siteUrl);
        } else {
            logger.warn("Сайт {} не найден в базе данных.", siteUrl);
        }
    }

    private boolean isValidSite(String url) {
        return sitesList.getSites().stream()
                .anyMatch(configSite -> configSite.getUrl().equals(url));
    }

    private boolean isValidInternalUrl(String url, String baseUrl) {
        return url.startsWith(baseUrl) && !url.contains("#") && !url.endsWith(".pdf") && !url.endsWith(".jpg");
    }

    private class PageIndexingTask extends RecursiveAction {
        private final String url;
        private final Site site;

        public PageIndexingTask(String url, Site site) {
            this.url = url;
            this.site = site;
        }

        @Override
        protected void compute() {
            if (!visitedUrls.add(url)) {
                return;
            }

            try {
                Document document = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .referrer("https://www.google.com")
                        .get();

                String title = document.title();
                String content = document.html();
                String path = new URL(url).getPath();

                if (!pageRepository.existsByPathAndSiteId(path, site.getId())) {
                    Page page = new Page();
                    page.setSite(site);
                    page.setPath(path);
                    page.setContent(content);
                    page.setCode(200);
                    page.setTitle(title);
                    pageRepository.save(page);
                    logger.info("Страница добавлена: {}", url);
                }

                Elements links = document.select("a[href]");
                List<PageIndexingTask> subtasks = new ArrayList<>();

                for (Element link : links) {
                    String absUrl = link.absUrl("href");
                    if (isValidInternalUrl(absUrl, site.getUrl())) {
                        subtasks.add(new PageIndexingTask(absUrl, site));
                    }
                }

                invokeAll(subtasks);

            } catch (IOException e) {
                logger.error("Ошибка при индексации страницы: {}", url, e);
            }
        }
    }
}