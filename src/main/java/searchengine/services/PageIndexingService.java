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
import searchengine.model.Lemma;
import searchengine.model.Index;
import searchengine.config.SitesList;
import searchengine.config.ConfigSite;
import java.time.LocalDateTime;
import searchengine.model.IndexingStatus;
import searchengine.repository.PageRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.IndexRepository;
import searchengine.services.IndexingService ;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URL;
import org.jsoup.select.Elements;
import org.jsoup.nodes.Element;
import searchengine.utils.LemmaProcessor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

@Service
public class PageIndexingService {
    private static final Logger logger = LoggerFactory.getLogger(PageIndexingService.class);
    @Autowired
    private LemmaRepository lemmaRepository;
    @Autowired
    private IndexRepository indexRepository;
    @Autowired
    private PageRepository pageRepository;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private SitesList sitesList;

    private IndexingService indexingService;
    private final Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
    private final ForkJoinPool forkJoinPool = new ForkJoinPool();

    public void indexPage(String url) {
        Optional<ConfigSite> optionalConfigSite = sitesList.getSites().stream()
                .filter(configSite -> configSite.getUrl().equals(url))
                .findFirst();

        if (optionalConfigSite.isEmpty()) {
            logger.warn("Сайт с таким URL не найден в конфигурации: {}", url);
            return;
        }

        ConfigSite configSite = optionalConfigSite.get();

        indexingService.deleteSiteData(url);

        Site site = new Site();
        site.setUrl(configSite.getUrl());
        site.setName(configSite.getName());
        site.setStatus(IndexingStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        siteRepository.save(site);

        logger.info("Добавлен новый сайт в индексацию: {}", url);

        visitedUrls.clear();

        try {
            forkJoinPool.invoke(new PageIndexingTask(url, site));

            site.setStatus(IndexingStatus.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            siteRepository.save(site);
            logger.info("Индексация завершена успешно для сайта: {}", url);

        } catch (Exception e) {
            site.setLastError("Ошибка при выполнении индексации: " + e.getMessage());
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            logger.error("Индексация завершилась с ошибкой: {}", e.getMessage(), e);
        }
    }

    private class PageIndexingTask extends RecursiveAction {
        private final String url;
        private final Site site;

        public PageIndexingTask(String url, Site site) {
            this.url = url;
            this.site = site;
        }

        private boolean isValidInternalUrl(String url, String baseUrl) {
            if (url.matches(".*\\/institute\\/staff\\/[^\\/]+")) {
                return false;
            }

            if (url.matches(".*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+\\d{2}:\\d{2}.*")) {
                return false;
            }

            if (url.matches(".*[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,6}.*")) {
                return false;
            }

            return url.startsWith(baseUrl) &&
                    !url.contains("#") &&
                    !url.matches(".*\\.(pdf|jpg|jpeg|png|gif|docx|doc|xlsx|xls|zip|tar|rar|mp3|mp4|avi|exe|mrs1\\.fig|nc|dat|ppt|pptx)(\\?.*)?$") &&
                    !url.matches(".*[\\sА-Яа-яЁё].*");
        }

        @Override
        protected void compute() {
            if (!visitedUrls.add(url)) {
                return;
            }

            try {
                if (!isValidInternalUrl(url, site.getUrl())) {
                    logger.info("Пропускаем страницу (не HTML или неподдерживаемый формат): {}", url);
                    return;
                }

                String path = new URL(url).getPath();
                if (pageRepository.existsByPathAndSiteId(path, site.getId())) {
                    logger.info("Пропускаем ранее проиндексированную страницу: {}", url);
                    return;
                }

                int delay = (int) (500 + Math.random() * 1000);
                Thread.sleep(delay);

                org.jsoup.Connection.Response response = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .referrer("https://www.google.com")
                        .execute();

                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    logger.warn("Ошибка при доступе к странице {}: HTTP статус {}", url, statusCode);
                    return;
                }

                String contentType = response.contentType();
                if (contentType == null || !contentType.contains("text/html")) {
                    logger.info("Пропускаем страницу (не HTML, content-type: {}): {}", contentType, url);
                    return;
                }

                Document document = response.parse();
                String title = document.title();
                String content = document.body().text();

                Page page = new Page();
                page.setSite(site);
                page.setPath(path);
                page.setContent(content);
                page.setCode(200);
                page.setTitle(title);
                pageRepository.save(page);
                logger.info("Страница добавлена: {}", url);

                Map<String, Integer> lemmaFrequencies = lemmatizeText(content);
                saveLemmasAndIndexes(lemmaFrequencies, page);

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
                updateSiteStatus(site.getUrl(), IndexingStatus.FAILED, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Ошибка в потоке индексации, прерывание выполнения", e);
                updateSiteStatus(site.getUrl(), IndexingStatus.FAILED, e.getMessage());
            }
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

        private Map<String, Integer> lemmatizeText(String text) {
            Map<String, Integer> lemmaFrequencies = new HashMap<>();

            try {
                LemmaProcessor lemmaProcessor = new LemmaProcessor();

                List<String> words = lemmaProcessor.extractLemmas(text);

                for (String word : words) {
                    lemmaFrequencies.put(word, lemmaFrequencies.getOrDefault(word, 0) + 1);
                }
            } catch (Exception e) {
                logger.error("Ошибка лемматизации текста: {}", e.getMessage());
            }
            return lemmaFrequencies;
        }

        private void saveLemmasAndIndexes(Map<String, Integer> lemmaFrequencies, Page page) {
            int newLemmas = 0;
            int updatedLemmas = 0;
            int savedIndexes = 0;

            StringBuilder lemmaLog = new StringBuilder("Найденные леммы: ");

            for (Map.Entry<String, Integer> entry : lemmaFrequencies.entrySet()) {
                String lemmaText = entry.getKey();
                int rank = entry.getValue();

                lemmaLog.append(lemmaText).append(" (").append(rank).append("), ");

                Optional<Lemma> optionalLemma = lemmaRepository.findByLemmaAndSite(lemmaText, page.getSite());

                Lemma lemma;
                try {
                    if (optionalLemma.isPresent()) {
                        lemma = optionalLemma.get();
                        lemma.setFrequency(lemma.getFrequency() + 1);
                        lemmaRepository.save(lemma);
                        updatedLemmas++;
                    } else {
                        lemma = new Lemma();
                        lemma.setLemma(lemmaText);
                        lemma.setSite(page.getSite());
                        lemma.setFrequency(1);
                        lemmaRepository.save(lemma);
                        newLemmas++;
                    }

                    Index index = new Index();
                    index.setPage(page);
                    index.setLemma(lemma);
                    index.setRank((float) rank);

                    try {
                        indexRepository.save(index);
                        savedIndexes++;
                    } catch (org.hibernate.exception.ConstraintViolationException e) {
                        logger.warn("Дублирующаяся запись для леммы '{}', пропускаем индекс.", lemmaText);
                    }

                } catch (Exception e) {
                    logger.error("Ошибка при обработке леммы '{}': {}", lemmaText, e.getMessage());
                }
            }

            logger.info(lemmaLog.toString());

            logger.info("Страница '{}' обработана. Новых лемм: {}, Обновленных лемм: {}, Связок (индексов): {}",
                    page.getPath(), newLemmas, updatedLemmas, savedIndexes);
        }
    }
}
