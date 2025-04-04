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
    private final int currentDepth;
    private final int maxDepth;
    private volatile boolean indexingInProgress = true;

    public PageCrawler(Site site, LemmaRepository lemmaRepository, IndexRepository indexRepository,
                       String url, Set<String> visitedUrls, PageRepository pageRepository,
                       IndexingService indexingService, int currentDepth, int maxDepth) {
        this.site = site;
        this.url = url;
        this.visitedUrls = visitedUrls;
        this.pageRepository = pageRepository;
        this.indexingService = indexingService;
        this.indexRepository = indexRepository;
        this.lemmaRepository = lemmaRepository;
        this.currentDepth = currentDepth;
        this.maxDepth = maxDepth;
    }

    @Override
    protected void compute() {
        if (currentDepth > maxDepth || !indexingInProgress) {
            logger.info("Индексация остановлена на глубине {} для URL: {}", currentDepth, url);
            return;  // Прекращаем выполнение, если глубина превышена или индексация остановлена
        }

        logger.info("Текущая глубина: {} для URL: {}", currentDepth, url);  // Выводим текущую глубину

        if (!checkAndLogStopCondition("Начало обработки")) return;

        synchronized (visitedUrls) {
            if (visitedUrls.contains(url)) {
                logger.debug("URL уже обработан: {}", url);
                return;
            }
            visitedUrls.add(url);
        }

        try {
            long delay = 500 + new Random().nextInt(4500);
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

    private void handleResponse(Connection.Response response) throws IOException {
        String contentType = response.contentType();
        int statusCode = response.statusCode();
        String path = new URL(url).getPath();

        // Проверяем, есть ли страница в базе
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

            // Сохраняем страницу
            page.setContent(text);
            pageRepository.save(page);

            // Сохраняем леммы и индексы
            saveLemmasAndIndexes(lemmaFrequencies, page);

            logger.info("HTML-страница добавлена: {}", url);
            processLinks(document);  // Обработка ссылок на текущей странице
        } else {
            page.setContent("Unhandled content type: " + contentType);
            logger.info("Контент с неизвестным типом добавлен: {}", url);
        }
    }

    private String extractText(Document document) {
        return document.text();
    }

    private Map<String, Integer> lemmatizeText(String text) {
        Map<String, Integer> lemmaFrequencies = new HashMap<>();

        try {
            // Используем LemmaProcessor для лемматизации
            LemmaProcessor lemmaProcessor = new LemmaProcessor();

            // Приводим текст к нижнему регистру и разбиваем на слова, игнорируя символы, не являющиеся буквами
            List<String> words = lemmaProcessor.extractLemmas(text);

            // Подсчитываем частоту лемм
            for (String word : words) {
                lemmaFrequencies.put(word, lemmaFrequencies.getOrDefault(word, 0) + 1);
            }
        } catch (Exception e) {
            // Логирование или обработка ошибки
            System.err.println("Ошибка лемматизации текста: " + e.getMessage());
            e.printStackTrace();
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

            // Добавляем лемму в лог
            lemmaLog.append(lemmaText).append(" (").append(rank).append("), ");

            // Проверяем, есть ли лемма в базе
            Optional<Lemma> optionalLemma = lemmaRepository.findByLemmaAndSite(lemmaText, page.getSite());

            Lemma lemma;
            try {
                if (optionalLemma.isPresent()) {
                    // Если лемма уже существует, обновляем её частоту
                    lemma = optionalLemma.get();
                    lemma.setFrequency(lemma.getFrequency() + 1);
                    lemmaRepository.save(lemma);  // Обновляем лемму
                    updatedLemmas++;
                } else {
                    // Если лемма новая, сохраняем её в базе
                    lemma = new Lemma();
                    lemma.setLemma(lemmaText);
                    lemma.setSite(page.getSite());
                    lemma.setFrequency(1);
                    lemmaRepository.save(lemma);
                    newLemmas++;
                }

                // Создаем связь между страницей и леммой
                Index index = new Index();
                index.setPage(page);
                index.setLemma(lemma);
                index.setRank((float) rank);

                try {
                    indexRepository.save(index);  // Сохраняем индекс
                    savedIndexes++;
                } catch (org.hibernate.exception.ConstraintViolationException e) {
                    // Если возникает ошибка дублирования, игнорируем её
                    logger.warn("Дублирующаяся запись для леммы '{}', пропускаем индекс.", lemmaText);
                }

            } catch (Exception e) {
                // Логирование других исключений
                logger.error("Ошибка при обработке леммы '{}': {}", lemmaText, e.getMessage());
            }
        }

        // Выводим в лог найденные леммы и их количество
        logger.info(lemmaLog.toString());

        logger.info("Страница '{}' обработана. Новых лемм: {}, Обновленных лемм: {}, Связок (индексов): {}",
                page.getPath(), newLemmas, updatedLemmas, savedIndexes);
    }

    private void processLinks(Document document) {
        if (!indexingInProgress || currentDepth >= maxDepth) {
            logger.info("Индексация была прервана или достигнута максимальная глубина. Прекращаем обработку ссылок.");
            return;
        }

        Elements links = document.select("a[href]");
        List<PageCrawler> subtasks = new ArrayList<>();
        for (Element link : links) {
            if (!checkAndLogStopCondition("При обработке ссылок")) return;

            String childUrl = link.absUrl("href");

            // Пропускаем ссылки вне основного домена и ограничиваем глубину
            if (!childUrl.startsWith(site.getUrl())) {
                logger.debug("Ссылка {} находится за пределами корневого сайта. Пропускаем.", childUrl);
                continue;
            }

            // Проверка на максимальную глубину перед добавлением новой задачи
            if (currentDepth + 1 <= maxDepth && indexingInProgress) {
                subtasks.add(new PageCrawler(site, lemmaRepository, indexRepository, childUrl, visitedUrls,
                        pageRepository, indexingService, currentDepth + 1, maxDepth));
                logger.debug("Добавлена ссылка в обработку: {}", childUrl);
            } else {
                logger.debug("Ссылка {} превышает максимальную глубину или индексация была остановлена. Пропускаем.", childUrl);
            }
        }

        // Выполняем все собранные подзадачи
        invokeAll(subtasks);  // Вызываем все подзадачи
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
