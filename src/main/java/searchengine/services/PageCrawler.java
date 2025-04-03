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
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.RecursiveAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final int maxDepth;
    private final int currentDepth;

    public PageCrawler(Site site,LemmaRepository lemmaRepository,IndexRepository indexRepository, String url, Set<String> visitedUrls, PageRepository pageRepository, IndexingService indexingService, int maxDepth, int currentDepth) {
        this.site = site;
        this.url = url;
        this.visitedUrls = visitedUrls;
        this.pageRepository = pageRepository;
        this.indexingService = indexingService;
        this.indexRepository = indexRepository;
        this.lemmaRepository = lemmaRepository;
        this.maxDepth = maxDepth;
        this.currentDepth = currentDepth;
    }

    @Override
    protected void compute() {
        if (currentDepth > maxDepth) {
            logger.info("Превышена максимальная глубина для URL: {}", url);
            return;  // Прерываем выполнение, если глубина больше максимальной
        }

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
            processLinks(document);
        } else {
            page.setContent("Unhandled content type: " + contentType);
            logger.info("Контент с неизвестным типом добавлен: {}", url);
        }
    }

    private String extractText(Document document) {
        return document.text();
    }

    private Map<String, Integer> lemmatizeText(String text) throws IOException {
        Map<String, Integer> lemmaFrequencies = new HashMap<>();

        // Используем LuceneMorphology для русского и английского языков
        LuceneMorphology russianMorph = new RussianLuceneMorphology();
        LuceneMorphology englishMorph = new EnglishLuceneMorphology();

        // Приводим текст к нижнему регистру и разбиваем на слова, игнорируя символы, не являющиеся буквами
        String[] words = text.toLowerCase().split("[^a-zа-яё]+");

        for (String word : words) {
            if (word.length() < 2) continue; // Игнорируем слишком короткие слова

            List<String> normalForms = null;
            // Проверяем, русский ли это текст
            if (word.matches("[а-яё]+")) {
                normalForms = russianMorph.getNormalForms(word);
            }
            // Проверяем, английский ли это текст
            else if (word.matches("[a-z]+")) {
                normalForms = englishMorph.getNormalForms(word);
            }

            if (normalForms != null) {
                // Для каждой нормальной формы увеличиваем частоту
                for (String lemma : normalForms) {
                    lemmaFrequencies.put(lemma, lemmaFrequencies.getOrDefault(lemma, 0) + 1);
                }
            }
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
            if (optionalLemma.isPresent()) {
                lemma = optionalLemma.get();
                lemma.setFrequency(lemma.getFrequency() + 1);
                updatedLemmas++;
            } else {
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
            indexRepository.save(index);
            savedIndexes++;
        }

        // Выводим в лог найденные леммы и их количество
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

            // Проверяем, что ссылка принадлежит корневому сайту
            if (!childUrl.startsWith(site.getUrl())) {
                logger.debug("Ссылка {} находится за пределами корневого сайта. Пропускаем.", childUrl);
                continue;
            }

            // Обработка JavaScript ссылок
            if (childUrl.startsWith("javascript:")) {
                logger.info("Обнаружена JavaScript ссылка: {}", childUrl);
                saveJavaScriptLink(childUrl);
                continue;
            }

            // Обработка tel: ссылок
            if (childUrl.startsWith("tel:")) {
                logger.info("Обнаружена телефонная ссылка: {}", childUrl);
                savePhoneLink(childUrl);
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
                    // Увеличиваем глубину на 1 и передаем новую задачу
                    subtasks.add(new PageCrawler(site, lemmaRepository, indexRepository,
                            childUrl, visitedUrls, pageRepository,
                            indexingService, maxDepth, currentDepth + 1));
                    logger.debug("Добавлена ссылка в обработку: {}", childUrl);
                } else {
                    logger.debug("Ссылка уже обработана: {}", childUrl);
                }
            }
        }
        invokeAll(subtasks);
    }

    private void savePhoneLink(String telUrl) {
        String phoneNumber = telUrl.substring(4); // Убираем "tel:"
        if (pageRepository.existsByPathAndSiteId(phoneNumber, site.getId())) {
            logger.info("Телефонный номер {} уже сохранён. Пропускаем.", phoneNumber);
            return;
        }

        Page page = new Page();
        page.setSite(site);
        page.setPath(phoneNumber);
        page.setCode(0); // Код 0 для телефонных ссылок
        page.setContent("Телефонный номер: " + phoneNumber);
        pageRepository.save(page);

        logger.info("Сохранён телефонный номер: {}", phoneNumber);
    }

    private void saveJavaScriptLink(String jsUrl) {
        if (pageRepository.existsByPathAndSiteId(jsUrl, site.getId())) {
            logger.info("JavaScript ссылка {} уже сохранена. Пропускаем.", jsUrl);
            return;
        }

        Page page = new Page();
        page.setSite(site);
        page.setPath(jsUrl); // Сохраняем полный jsUrl как path
        page.setCode(0); // Код 0 для JavaScript ссылок
        page.setContent("JavaScript ссылка: " + jsUrl);
        pageRepository.save(page);

        logger.info("Сохранена JavaScript ссылка: {}", jsUrl);
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