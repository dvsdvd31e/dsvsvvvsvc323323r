package searchengine.services;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import searchengine.model.*;
import searchengine.config.SitesList;
import java.net.URL;
import java.time.LocalDateTime;

import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.utils.LemmaProcessor;

import java.io.IOException;
import java.util.*;

public class PageIndexingService   {
    private static final Logger logger = LoggerFactory.getLogger(searchengine.services.PageCrawler.class);
    private final Site site;
    private final String url;
    private final Set<String> visitedUrls;
    private final PageRepository pageRepository;
    private final IndexingService indexingService;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SiteRepository siteRepository;
    private final SitesList sitesList;

    public PageIndexingService(Site site,LemmaRepository lemmaRepository,SitesList sitesList,SiteRepository siteRepository,IndexRepository indexRepository, String url, Set<String> visitedUrls, PageRepository pageRepository, IndexingService indexingService) {
        this.site = site;
        this.url = url;
        this.sitesList = sitesList;
        this.visitedUrls = visitedUrls;
        this.pageRepository = pageRepository;
        this.indexingService = indexingService;
        this.indexRepository = indexRepository;
        this.lemmaRepository = lemmaRepository;
        this.siteRepository = siteRepository;
    }


    public void indexPage(String url) {
        try {
            if (!isValidSite(url)) {
                logger.warn("Сайт с таким URL не найден в списке конфигурации: {}", url);
                return;
            }

            Site site = siteRepository.findByUrl(url);
            if (site == null) {
                logger.info("Создаём новый сайт для URL: {}", url);
                site = new Site();
                site.setUrl(url);
                site.setName("Unknown Site");
                site.setStatus(IndexingStatus.INDEXING);
                site.setStatusTime(LocalDateTime.now());

                siteRepository.save(site);
                logger.info("Новый сайт успешно добавлен: {}", url);
            }

            // Выполняем запрос к странице
            Connection.Response response = Jsoup.connect(url).execute();

            // Вызываем handleResponse для обработки страницы
            handleResponse(response, site, url);

        } catch (IOException e) {
            logger.error("Ошибка при индексации страницы: {}", url, e);
        }
    }



    // Метод для проверки, есть ли сайт в списке конфигурации
    private boolean isValidSite(String url) {
        return sitesList.getSites().stream()
                .anyMatch(configSite -> configSite.getUrl().equals(url));
    }

    public void handleResponse(Connection.Response response, Site site, String url) throws IOException {
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
            processLinks(document, site);
        } else {
            page.setContent("Unhandled content type: " + contentType);
            logger.info("Контент с неизвестным типом добавлен: {}", url);
        }
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

    private String extractText(Document document) {
        return document.text();
    }


    private    Map<String, Integer> lemmatizeText(String text) {
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

    public void processLinks(Document document, Site site) {
        Elements links = document.select("a[href]");
        for (Element link : links) {
            String childUrl = link.absUrl("href");

            if (!childUrl.startsWith(site.getUrl())) {
                logger.debug("Ссылка {} находится за пределами корневого сайта. Пропускаем.", childUrl);
                continue;
            }

            if (childUrl.startsWith("javascript:") || childUrl.startsWith("tel:")) {
                logger.info("Пропущена неподходящая ссылка: {}", childUrl);
                continue;
            }

            String childPath;
            try {
                childPath = new URL(childUrl).getPath();
            } catch (Exception e) {
                logger.warn("Ошибка извлечения пути из URL: {}", childUrl);
                continue;
            }

            synchronized (visitedUrls) {
                if (!visitedUrls.contains(childPath)) {
                    visitedUrls.add(childPath);
                    new PageCrawler(site, lemmaRepository, indexRepository, childUrl, visitedUrls, pageRepository, indexingService).compute();
                    logger.debug("Добавлена ссылка в обработку: {}", childUrl);
                } else {
                    logger.debug("Ссылка уже обработана: {}", childUrl);
                }
            }
        }
    }



}
