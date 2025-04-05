package searchengine.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final BlockingQueue<String> urlQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(10); // 10 потоков

    public void indexPage(String url) {
        if (!isValidSite(url)) {
            logger.warn("Сайт с таким URL не найден в конфигурации: {}", url);
            return;
        }

        Site site = siteRepository.findByUrl(url);

        if (site == null) {
            site = new Site(); // присваиваем напрямую в site
            site.setUrl(url);
            site.setName("Unknown Site");
            site.setStatus(IndexingStatus.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            logger.info("Добавлен новый сайт в индексацию: {}", url);
        }

        // Добавляем стартовый URL в очередь
        urlQueue.add(url);

        // Запускаем 10 потоков, которые будут обрабатывать queue
        for (int i = 0; i < 10; i++) {
            Site finalSite = site; // effectively final для использования в лямбде
            executor.submit(() -> crawlSite(finalSite));
        }
    }



    private void crawlSite(Site site) {
        while (true) {
            try {
                String url = urlQueue.take(); // Ожидаем URL (поток не завершится, пока есть задачи)
                if (!visitedUrls.add(url)) {
                    continue; // Пропускаем уже посещённые страницы
                }
                processPage(site, url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Ошибка в потоке обхода", e);
                break;
            }
        }
    }

    private void processPage(Site site, String url) {
        try {
            Document document = Jsoup.connect(url).get();
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
            for (Element link : links) {
                String absUrl = link.absUrl("href");
                if (isValidInternalUrl(absUrl, site.getUrl())) {
                    urlQueue.add(absUrl);
                }
            }
        } catch (IOException e) {
            logger.error("Ошибка при индексации страницы: {}", url, e);
        }
    }

    private boolean isValidSite(String url) {
        return sitesList.getSites().stream()
                .anyMatch(configSite -> configSite.getUrl().equals(url));
    }

    private boolean isValidInternalUrl(String url, String baseUrl) {
        return url.startsWith(baseUrl) && !url.contains("#") && !url.endsWith(".pdf") && !url.endsWith(".jpg");
    }
}
