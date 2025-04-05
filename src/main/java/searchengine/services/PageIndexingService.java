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

        // Очистка данных
        deleteSiteData(url);

        // Создание новой записи сайта
        Site site = new Site();
        site.setUrl(configSite.getUrl());
        site.setName(configSite.getName()); // <-- используем имя из конфигурации
        site.setStatus(IndexingStatus.INDEXING);
        site.setStatusTime(LocalDateTime.now());

        // Сохраняем в базе данных
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
                // Генерация случайной задержки между 0,5 и 5 секундами
                int delay = (int) (6 + Math.random() * 66);  // задержка в диапазоне от 500 мс до 5000 мс
                Thread.sleep(delay);  // Пауза

                // Запрос страницы
                Document document = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .referrer("https://www.google.com")
                        .get();

                String title = document.title();
                String content = document.body().text();  // Только текстовая часть страницы
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

                    // Лемматизация текста страницы и сохранение лемм в базу данных
                    Map<String, Integer> lemmaFrequencies = lemmatizeText(content);
                    saveLemmasAndIndexes(lemmaFrequencies, page);
                }

                // Извлекаем ссылки
                Elements links = document.select("a[href]");
                List<PageIndexingTask> subtasks = new ArrayList<>();

                for (Element link : links) {
                    String absUrl = link.absUrl("href");
                    if (isValidInternalUrl(absUrl, site.getUrl())) {
                        subtasks.add(new PageIndexingTask(absUrl, site));
                    }
                }

                // Запускаем новые задачи для ссылок
                invokeAll(subtasks);

                // После завершения обхода, обновляем статус на INDEXED
                updateSiteStatus(site, IndexingStatus.INDEXED, null);

            } catch (IOException e) {
                logger.error("Ошибка при индексации страницы: {}", url, e);
                updateSiteStatus(site, IndexingStatus.FAILED, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();  // Восстанавливаем флаг прерывания
                logger.error("Ошибка в потоке индексации, прерывание выполнения", e);
                updateSiteStatus(site, IndexingStatus.FAILED, e.getMessage());
            }
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

        // Обновление статуса сайта
        private void updateSiteStatus(Site site, IndexingStatus status, String errorMessage) {
            site.setStatus(status);
            site.setStatusTime(LocalDateTime.now());

            if (errorMessage != null) {
                site.setLastError(errorMessage);
            }

            siteRepository.save(site);
            logger.info("Статус сайта {} изменён на {}", site.getUrl(), status);
        }
    }
}
