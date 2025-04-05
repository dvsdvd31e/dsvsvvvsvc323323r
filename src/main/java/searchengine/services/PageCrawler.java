package searchengine.services;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Lemma;
import searchengine.model.Index;
import searchengine.repository.PageRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.IndexRepository;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.RecursiveAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import searchengine.utils.LemmaProcessor;
import java.util.List;


public class PageCrawler extends RecursiveAction {
    private static final Logger logger = LoggerFactory.getLogger(PageCrawler.class);
    private final Site site;
    private final String url;
    private final Set<String> visitedUrls;
    private final PageRepository pageRepository;
    private final IndexingService indexingService;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    public PageCrawler(Site site,LemmaRepository lemmaRepository,IndexRepository indexRepository, String url, Set<String> visitedUrls, PageRepository pageRepository, IndexingService indexingService) {
        this.site = site;
        this.url = url;
        this.visitedUrls = visitedUrls;
        this.pageRepository = pageRepository;
        this.indexingService = indexingService;
        this.indexRepository = indexRepository;
        this.lemmaRepository = lemmaRepository;

    }

    @Override
    protected void compute() {
        if (!checkAndLogStopCondition("Начало обработки")) return;

        synchronized (visitedUrls) {
            if (visitedUrls.contains(url)) {
                logger.debug("URL уже обработан: {}", url);
                return;
            }
            visitedUrls.add(url);
        }

        try {
            String path = new URL(url).getPath();

            if (pageRepository.existsByPathAndSiteId(path, site.getId())) {
                logger.info("Пропускаем ранее проиндексированную страницу: {}", url);
                return;
            }

            long delay = 6 + new Random().nextInt(66);
            logger.debug("Задержка перед запросом: {} ms для URL: {}", delay, url);
            Thread.sleep(delay);

            if (!checkAndLogStopCondition("Перед запросом")) return;

            logger.info("Обработка URL: {}", url);
            Connection.Response response = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) Gecko/20070725 Firefox/2.0.0.6")
                    .referrer("http://www.google.com")
                    .ignoreContentType(true)
                    .execute();

            handleResponse(response);

        } catch (IOException e) {
            handleError(e);
        } catch (InterruptedException e) {
            logger.warn("Индексация прервана для URL {}: поток остановлен.", url);
            Thread.currentThread().interrupt();
        }
    }

    public void handleResponse(Connection.Response response) throws IOException {
        String contentType = response.contentType();
        int statusCode = response.statusCode();
        String path = new URL(url).getPath();

        if (pageRepository.existsByPathAndSiteId(path, site.getId())) {
            logger.info("Страница {} уже существует. Пропускаем сохранение.", url);
            return;
        }

        Page page = new Page();
        page.setSite(site);
        page.setPath(path);
        page.setCode(statusCode);

        if (contentType != null && contentType.startsWith("image/")) {
            page.setContent("Image content: " + contentType);
            logger.info("Изображение добавлено: {}", url);
        } else if (contentType != null && contentType.contains("text/html")) {
            Document document = response.parse();
            String text = extractText(document);
            Map<String, Integer> lemmaFrequencies = lemmatizeText(text);

            page.setContent(text);
            pageRepository.save(page);

            saveLemmasAndIndexes(lemmaFrequencies, page);

            logger.info("HTML-страница добавлена: {}", url);
            processLinks(document);
        } else {
            page.setContent("Unhandled content type: " + contentType);
            logger.info("Контент с неизвестным типом добавлен: {}", url);
        }
    }

    public Map<String, Integer> lemmatizeText(String text) {
        Map<String, Integer> lemmaFrequencies = new HashMap<>();

        try {
            LemmaProcessor lemmaProcessor = new LemmaProcessor();

            List<String> words = lemmaProcessor.extractLemmas(text);

            for (String word : words) {
                lemmaFrequencies.put(word, lemmaFrequencies.getOrDefault(word, 0) + 1);
            }
        } catch (Exception e) {
            System.err.println("Ошибка лемматизации текста: " + e.getMessage());
            e.printStackTrace();
        }

        return lemmaFrequencies;
    }

    private String extractText(Document document) {
        return document.text();
    }

    public void saveLemmasAndIndexes(Map<String, Integer> lemmaFrequencies, Page page) {
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

    private void processLinks(Document document) {
        Elements links = document.select("a[href]");
        List<PageCrawler> subtasks = new ArrayList<>();
        for (Element link : links) {
            if (!checkAndLogStopCondition("При обработке ссылок")) return;

            String childUrl = link.absUrl("href");

            if (!childUrl.startsWith(site.getUrl())) {
                logger.debug("Ссылка {} находится за пределами корневого сайта. Пропускаем.", childUrl);
                continue;
            }

            if (childUrl.startsWith("javascript:")) {
                logger.info("Обнаружена JavaScript ссылка: {}", childUrl);
                continue;
            }

            if (childUrl.startsWith("tel:")) {
                logger.info("Обнаружена телефонная ссылка: {}", childUrl);
                continue;
            }

            String childPath = null;
            try {
                childPath = new URL(childUrl).getPath();
            } catch (Exception e) {
                logger.warn("Ошибка извлечения пути из URL: {}", childUrl);
            }

            synchronized (visitedUrls) {
                if (childPath != null && !visitedUrls.contains(childPath)) {
                    visitedUrls.add(childPath);
                    subtasks.add(new PageCrawler(site, lemmaRepository, indexRepository, childUrl, visitedUrls, pageRepository, indexingService));
                    logger.debug("Добавлена ссылка в обработку: {}", childUrl);
                } else {
                    logger.debug("Ссылка уже обработана: {}", childUrl);
                }
            }
        }
        invokeAll(subtasks);
    }


    private void handleError(IOException e) {
        logger.warn("Ошибка обработки URL {}: {}", url, e.getMessage());
        Page page = new Page();
        page.setSite(site);
        page.setPath(url);
        page.setCode(0);
        page.setContent("Ошибка обработки: " + e.getMessage());
        pageRepository.save(page);
    }

    private boolean checkAndLogStopCondition(String stage) {
        if (!indexingService.isIndexingInProgress()) {
            logger.info("Индексация прервана на этапе {} для URL: {}", stage, url);
            return false;
        }
        return true;
    }
}
